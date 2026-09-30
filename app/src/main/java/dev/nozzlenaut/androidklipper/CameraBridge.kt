package dev.nozzlenaut.androidklipper

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.os.Build
import android.os.SystemClock
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Owns one USB UVC camera and exposes it as MJPEG for Mainsail. */
class CameraBridge(
    private val context: Context,
    private val report: (String) -> Unit = {}
) {
    private data class Frame(val id: Long, val jpeg: ByteArray)

    private val latestFrame = AtomicReference<Frame?>(null)
    private val frameSequence = AtomicLong(0)
    private val lastEncodeAtMs = AtomicLong(0)
    private val encoding = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val requestingPermission = AtomicBoolean(false)
    private val cameraArmed = AtomicBoolean(false)
    private val lastFrameAtElapsedMs = AtomicLong(0)
    private val cameraLock = Any()
    private val encoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-camera-jpeg").apply { isDaemon = true }
    }
    private val cameraWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "androidklipper-camera-watchdog").apply { isDaemon = true }
    }
    private val server = CameraHttpServer(CAMERA_PORT)

    @Volatile private var monitor: USBMonitor? = null
    @Volatile private var camera: UVCCamera? = null
    @Volatile private var previewTexture: SurfaceTexture? = null
    @Volatile private var activeDevice: UsbDevice? = null
    @Volatile private var frameWidth = PREVIEW_WIDTH
    @Volatile private var frameHeight = PREVIEW_HEIGHT
    @Volatile private var bridgeState = "stopped"

    private val frameCallback = IFrameCallback { buffer ->
        acceptFrame(buffer)
    }
    private val deviceListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            if (!isUvcCamera(device)) return
            report("UVC attached: ${describe(device)}")
            if (!cameraArmed.get()) {
                report("UVC remains disarmed until explicit camera retry")
                return
            }
            requestPermission(device)
        }

        override fun onDettach(device: UsbDevice) {
            if (activeDevice?.deviceId == device.deviceId) {
                cameraArmed.set(false)
                report("UVC detached: ${describe(device)}; manual retry required")
                stopCamera("camera-disconnected-retry-required")
            }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            if (!isUvcCamera(device)) return
            requestingPermission.set(false)
            if (!cameraArmed.get()) {
                report("ignoring late UVC connect while camera is disarmed")
                runCatching { ctrlBlock.close() }
                return
            }
            openCamera(device, ctrlBlock)
        }
        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            if (activeDevice?.deviceId == device.deviceId) {
                cameraArmed.set(false)
                report("UVC disconnected: ${describe(device)}; manual retry required")
                stopCamera("camera-disconnected-retry-required")
            }
        }

        override fun onCancel(device: UsbDevice) {
            requestingPermission.set(false)
            cameraArmed.set(false)
            bridgeState = "permission-denied"
            report("UVC permission denied: ${describe(device)}")
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        bridgeState = "starting"
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            bridgeState = "camera-disabled"
            cameraWatchdog.scheduleAtFixedRate(
                { checkCameraHealth() }, 2, 2, TimeUnit.SECONDS
            )
            report("camera server listening on :$CAMERA_PORT; UVC disabled until explicitly enabled")
        } catch (t: Throwable) {
            bridgeState = "start-error"
            started.set(false)
            runCatching { monitor?.unregister() }
            runCatching { monitor?.destroy() }
            monitor = null
            runCatching { server.stop() }
            throw t
        }
    }

    private fun requestPermission(device: UsbDevice) {
        if (activeDevice?.deviceId == device.deviceId) return
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            requestingPermission.set(false)
            bridgeState = "permission-required"
            report("UVC permission required for ${describe(device)}")
            return
        }
        if (!requestingPermission.compareAndSet(false, true)) return
        bridgeState = "opening-camera"
        val failed = runCatching { monitor?.requestPermission(device) ?: true }.getOrDefault(true)
        if (failed) {
            requestingPermission.set(false)
            bridgeState = "permission-request-failed"
            report("could not open permitted UVC device ${describe(device)}")
        }
    }

    fun retryGrantedCamera() {
        requestingPermission.set(false)
        val usbMonitor = ensureUsbMonitor() ?: return
        val device = usbMonitor.getDeviceList().firstOrNull(::isUvcCamera)
        if (device == null) {
            cameraArmed.set(false)
            bridgeState = "waiting-for-camera"
            report("camera retry requested but no UVC camera is attached")
            return
        }
        cameraArmed.set(true)
        report("camera explicitly armed for ${describe(device)}")
        requestPermission(device)
    }
    private fun ensureUsbMonitor(): USBMonitor? {
        monitor?.let { return it }
        if (!started.get()) {
            bridgeState = "camera-server-not-ready"
            return null
        }
        return synchronized(cameraLock) {
            monitor?.let { return@synchronized it }
            val usbMonitor = USBMonitor(context, deviceListener)
            try {
                usbMonitor.register()
                monitor = usbMonitor
                bridgeState = "waiting-for-camera"
                report("UVC monitor explicitly enabled")
                usbMonitor
            } catch (t: Throwable) {
                runCatching { usbMonitor.destroy() }
                bridgeState = "camera-monitor-error"
                report("UVC monitor enable failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
    }
    private fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        synchronized(cameraLock) {
            val current = activeDevice
            if (current != null && current.deviceId != device.deviceId) {
                report("ignoring extra UVC camera ${describe(device)}")
                return
            }
            stopCameraLocked("opening-camera")
            bridgeState = "opening-camera"
            latestFrame.set(null)

            val uvc = UVCCamera()
            var texture: SurfaceTexture? = null
            try {
                uvc.open(ctrlBlock)
                configurePreview(uvc)
                texture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    SurfaceTexture(false)
                } else {
                    SurfaceTexture(0)
                }.apply {
                    setDefaultBufferSize(frameWidth, frameHeight)
                }
                uvc.setPreviewTexture(texture)
                uvc.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                uvc.startPreview()
                previewTexture = texture
                camera = uvc
                activeDevice = device
                lastFrameAtElapsedMs.set(SystemClock.elapsedRealtime())
                bridgeState = "camera-open"
                report("opened ${describe(device)} at ${frameWidth}x$frameHeight MJPEG")
            } catch (t: Throwable) {
                runCatching { texture?.release() }
                runCatching { uvc.destroy() }
                bridgeState = "camera-error"
                report("UVC open failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
    private fun configurePreview(uvc: UVCCamera) {
        val profiles = listOf(
            PreviewProfile(320, 240, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG"),
            PreviewProfile(640, 360, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG"),
            PreviewProfile(640, 480, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG")
        )
        var lastFailure: Throwable? = null
        for (profile in profiles) {
            try {
                uvc.setPreviewSize(
                    profile.width,
                    profile.height,
                    TARGET_CAMERA_FPS,
                    TARGET_CAMERA_FPS,
                    profile.format,
                    CAMERA_BANDWIDTH_FACTOR
                )
                frameWidth = profile.width
                frameHeight = profile.height
                report("UVC profile ${profile.label} ${profile.width}x${profile.height} @ ${TARGET_CAMERA_FPS}fps bandwidth=${CAMERA_BANDWIDTH_FACTOR}")
                return
            } catch (t: Throwable) {
                lastFailure = t
            }
        }
        throw IllegalStateException("no supported UVC preview profile", lastFailure)
    }

    private data class PreviewProfile(
        val width: Int,
        val height: Int,
        val format: Int,
        val label: String
    )
    private fun acceptFrame(buffer: ByteBuffer?) {
        buffer ?: return
        val width = frameWidth
        val height = frameHeight
        val expected = width * height * 3 / 2
        val now = System.currentTimeMillis()
        val previous = lastEncodeAtMs.get()
        if (now - previous < FRAME_INTERVAL_MS) return
        if (!lastEncodeAtMs.compareAndSet(previous, now)) return
        if (!encoding.compareAndSet(false, true)) return

        val copy = try {
            val duplicate = buffer.duplicate()
            duplicate.rewind()
            if (duplicate.remaining() < expected) {
                encoding.set(false)
                return
            }
            ByteArray(expected).also { duplicate.get(it) }
        } catch (t: Throwable) {
            encoding.set(false)
            report("frame copy failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        encodeFrame(copy, width, height)
    }
    private fun encodeFrame(bytes: ByteArray, width: Int, height: Int) {
        runCatching {
            encoder.execute {
                try {
                    val output = ByteArrayOutputStream(width * height / 3)
                    val image = YuvImage(bytes, ImageFormat.NV21, width, height, null)
                    if (image.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, output)) {
                        latestFrame.set(
                            Frame(frameSequence.incrementAndGet(), output.toByteArray())
                        )
                        lastFrameAtElapsedMs.set(SystemClock.elapsedRealtime())
                        bridgeState = "streaming"
                    }
                } catch (t: Throwable) {
                    report("JPEG encode failed: ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    encoding.set(false)
                }
            }
        }.onFailure {
            encoding.set(false)
            report("JPEG queue failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun checkCameraHealth() {
        val active = activeDevice != null
        val last = lastFrameAtElapsedMs.get()
        val now = SystemClock.elapsedRealtime()
        if (!CameraSafetyPolicy.shouldStopForStaleFrame(active, last, now)) return
        if (!cameraArmed.compareAndSet(true, false)) return
        report("UVC frame stall detected after ${now - last}ms; stopping camera and requiring manual retry")
        stopCamera("camera-stalled-retry-required")
    }

    private fun stopCamera(nextState: String) {
        synchronized(cameraLock) {
            stopCameraLocked(nextState)
        }
    }
    private fun stopCameraLocked(nextState: String) {
        val current = camera
        val texture = previewTexture
        camera = null
        previewTexture = null
        activeDevice = null
        latestFrame.set(null)
        lastFrameAtElapsedMs.set(0L)
        if (current != null) {
            runCatching { current.setFrameCallback(null, 0) }
            runCatching { current.stopPreview() }
            runCatching { current.destroy() }
        }
        runCatching { texture?.release() }
        bridgeState = nextState
    }

    fun close() {
        if (!started.compareAndSet(true, false)) return
        bridgeState = "stopping"
        stopCamera("stopping")
        monitor?.let { usbMonitor ->
            runCatching { usbMonitor.unregister() }
            runCatching { usbMonitor.destroy() }
        }
        monitor = null
        requestingPermission.set(false)
        cameraArmed.set(false)
        runCatching { server.stop() }
        cameraWatchdog.shutdownNow()
        encoder.shutdownNow()
        bridgeState = "stopped"
        report("camera bridge stopped")
    }

    private fun isUvcCamera(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == USB_VIDEO_CLASS
        }
    private fun describe(device: UsbDevice): String =
        "${device.productName ?: device.deviceName} " +
            "${device.vendorId.toString(16).padStart(4, '0')}:" +
            device.productId.toString(16).padStart(4, '0')

    private inner class CameraHttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val path = session.uri?.substringBefore('?') ?: "/"
            return when (path) {
                "/", "/status" -> statusResponse()
                "/snapshot.jpg", "/camera/snapshot.jpg" -> snapshotResponse()
                "/stream.mjpg", "/camera/stream.mjpg" -> streamResponse()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found"
                )
            }.apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
            }
        }

        private fun statusResponse(): Response {
            val frame = latestFrame.get()
            val cameraName = activeDevice?.productName ?: "none"
            val safeName = cameraName.replace("\\", "\\\\").replace("\"", "\\\"")
            val last = lastFrameAtElapsedMs.get()
            val age = if (last > 0L) SystemClock.elapsedRealtime() - last else -1L
            val body = """
                {"state":"$bridgeState","camera":"$safeName","frame_id":${frame?.id ?: 0},"armed":${cameraArmed.get()},"frame_age_ms":$age}
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }

        private fun snapshotResponse(): Response {
            val frame = latestFrame.get() ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Camera has no frame yet"
            )
            return newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(frame.jpeg),
                frame.jpeg.size.toLong()
            )
        }

        private fun streamResponse(): Response {
            if (latestFrame.get() == null) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Camera stream unavailable: $bridgeState"
                )
            }
            return newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame",
                MjpegInputStream { latestFrame.get() }
            )
        }
    }
    private class MjpegInputStream(
        private val provider: () -> Frame?
    ) : InputStream() {
        @Volatile private var closed = false
        private var current = ByteArrayInputStream(ByteArray(0))
        private var lastFrameId = -1L

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (!closed) {
                val count = current.read(buffer, offset, length)
                if (count >= 0) return count

                val frame = provider()
                if (frame == null || frame.id == lastFrameId) {
                    try {
                        Thread.sleep(STREAM_POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                    continue
                }
                lastFrameId = frame.id
                current = ByteArrayInputStream(buildPart(frame))
            }
            return -1
        }

        override fun close() {
            closed = true
            current.close()
            super.close()
        }

        private fun buildPart(frame: Frame): ByteArray {
            val header = (
                "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.jpeg.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            return ByteArray(header.size + frame.jpeg.size + 2).also { packet ->
                System.arraycopy(header, 0, packet, 0, header.size)
                System.arraycopy(frame.jpeg, 0, packet, header.size, frame.jpeg.size)
                packet[packet.lastIndex - 1] = '\r'.code.toByte()
                packet[packet.lastIndex] = '\n'.code.toByte()
            }
        }
    }
    companion object {
        const val CAMERA_PORT = 8082
        const val STREAM_PATH = "/stream.mjpg"
        const val SNAPSHOT_PATH = "/snapshot.jpg"
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 360
        private const val TARGET_CAMERA_FPS = 5
        private const val CAMERA_BANDWIDTH_FACTOR = 0.50f
        private const val JPEG_QUALITY = 70
        private const val FRAME_INTERVAL_MS = 200L
        private const val STREAM_POLL_MS = 25L
        private const val USB_VIDEO_CLASS = 14
    }
}
