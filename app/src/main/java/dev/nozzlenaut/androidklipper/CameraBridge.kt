package dev.nozzlenaut.androidklipper

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
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
    private val cameraLock = Any()
    private val encoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-camera-jpeg").apply { isDaemon = true }
    }
    private val server = CameraHttpServer(CAMERA_PORT)

    @Volatile private var monitor: USBMonitor? = null
    @Volatile private var camera: UVCCamera? = null
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
            requestPermission(device)
        }

        override fun onDettach(device: UsbDevice) {
            if (activeDevice?.deviceId == device.deviceId) {
                report("UVC detached: ${describe(device)}")
                stopCamera("waiting-for-camera")
            }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            if (!isUvcCamera(device)) return
            requestingPermission.set(false)
            openCamera(device, ctrlBlock)
        }
        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            if (activeDevice?.deviceId == device.deviceId) {
                report("UVC disconnected: ${describe(device)}")
                stopCamera("waiting-for-camera")
            }
        }

        override fun onCancel(device: UsbDevice) {
            requestingPermission.set(false)
            bridgeState = "permission-denied"
            report("UVC permission denied: ${describe(device)}")
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        bridgeState = "starting"
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val usbMonitor = USBMonitor(context, deviceListener)
            monitor = usbMonitor
            usbMonitor.register()
            bridgeState = "waiting-for-camera"
            report("camera server listening on :$CAMERA_PORT")
            usbMonitor.getDeviceList()
                .firstOrNull(::isUvcCamera)
                ?.let(::requestPermission)
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
        if (!requestingPermission.compareAndSet(false, true)) return
        bridgeState = "requesting-usb-permission"
        val failed = runCatching { monitor?.requestPermission(device) ?: true }.getOrDefault(true)
        if (failed) {
            requestingPermission.set(false)
            bridgeState = "permission-request-failed"
            report("could not request UVC permission for ${describe(device)}")
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
            try {
                uvc.open(ctrlBlock)
                configurePreview(uvc)
                uvc.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                uvc.startPreview()
                camera = uvc
                activeDevice = device
                bridgeState = "camera-open"
                report("opened ${describe(device)} at ${frameWidth}x$frameHeight MJPEG")
            } catch (t: Throwable) {
                runCatching { uvc.destroy() }
                bridgeState = "camera-error"
                report("UVC open failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
    private fun configurePreview(uvc: UVCCamera) {
        val profiles = listOf(
            PreviewProfile(640, 360, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG"),
            PreviewProfile(640, 480, UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG"),
            PreviewProfile(640, 480, UVCCamera.FRAME_FORMAT_YUYV, "YUYV")
        )
        var lastFailure: Throwable? = null
        for (profile in profiles) {
            try {
                uvc.setPreviewSize(
                    profile.width,
                    profile.height,
                    MIN_CAMERA_FPS,
                    MAX_CAMERA_FPS,
                    profile.format,
                    UVCCamera.DEFAULT_BANDWIDTH
                )
                frameWidth = profile.width
                frameHeight = profile.height
                report("UVC profile ${profile.label} ${profile.width}x${profile.height}")
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

    private fun stopCamera(nextState: String) {
        synchronized(cameraLock) {
            stopCameraLocked(nextState)
        }
    }
    private fun stopCameraLocked(nextState: String) {
        val current = camera
        camera = null
        activeDevice = null
        latestFrame.set(null)
        if (current != null) {
            runCatching { current.setFrameCallback(null, 0) }
            runCatching { current.stopPreview() }
            runCatching { current.destroy() }
        }
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
        runCatching { server.stop() }
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
            val body = """
                {"state":"$bridgeState","camera":"$safeName","frame_id":${frame?.id ?: 0}}
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

        private fun streamResponse(): Response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            MjpegInputStream { latestFrame.get() }
        )
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
        private const val MIN_CAMERA_FPS = 5
        private const val MAX_CAMERA_FPS = 15
        private const val JPEG_QUALITY = 75
        private const val FRAME_INTERVAL_MS = 100L
        private const val STREAM_POLL_MS = 25L
        private const val USB_VIDEO_CLASS = 14
    }
}
