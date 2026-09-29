package dev.nozzlenaut.androidklipper

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.usb.USBMonitor
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one USB UVC camera and exposes it as a local MJPEG stream for Mainsail.
 * This intentionally stays independent from Klipper's serial USB sessions.
 */
class CameraBridge(
    private val context: Context,
    private val report: (String) -> Unit = {}
) {
    private data class Frame(val id: Long, val jpeg: ByteArray)

    private val latestFrame = AtomicReference<Frame?>(null)
    private val frameSequence = AtomicLong(0)
    private val lastEncodeAtMs = AtomicLong(0)
    private val encoding = AtomicBoolean(false)
    private val requestingPermission = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val encoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-camera-jpeg").apply { isDaemon = true }
    }
    private val cameras = linkedMapOf<Int, MultiCameraClient.ICamera>()
    private val server = CameraHttpServer(CAMERA_PORT)

    @Volatile
    private var cameraClient: MultiCameraClient? = null

    @Volatile
    private var activeCamera: MultiCameraClient.ICamera? = null

    @Volatile
    private var bridgeState = "stopped"

    private val cameraRequest = CameraRequest.Builder()
        .setPreviewWidth(PREVIEW_WIDTH)
        .setPreviewHeight(PREVIEW_HEIGHT)
        .setRenderMode(CameraRequest.RenderMode.OPENGL)
        .setRawPreviewData(true)
        .setCaptureRawImage(false)
        .setAudioSource(CameraRequest.AudioSource.NONE)
        .create()
    private val previewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        ) {
            if (data == null || format != IPreviewDataCallBack.DataFormat.NV21) return
            val now = System.currentTimeMillis()
            val previous = lastEncodeAtMs.get()
            if (now - previous < FRAME_INTERVAL_MS) return
            if (!lastEncodeAtMs.compareAndSet(previous, now)) return
            if (!encoding.compareAndSet(false, true)) return

            val copy = data.copyOf()
            runCatching {
                encoder.execute {
                    try {
                        val output = ByteArrayOutputStream(width * height / 3)
                        val image = YuvImage(copy, ImageFormat.NV21, width, height, null)
                        if (image.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, output)) {
                            latestFrame.set(Frame(frameSequence.incrementAndGet(), output.toByteArray()))
                            bridgeState = "streaming"
                        }
                    } catch (t: Throwable) {
                        report("JPEG encode failed: ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        encoding.set(false)
                    }
                }
            }.onFailure { encoding.set(false) }
        }
    }
    private val cameraStateCallback = object : ICameraStateCallBack {
        override fun onCameraState(
            self: MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?
        ) {
            bridgeState = when (code) {
                ICameraStateCallBack.State.OPENED -> "camera-open"
                ICameraStateCallBack.State.CLOSED -> "camera-closed"
                ICameraStateCallBack.State.ERROR -> "camera-error"
            }
            report("UVC state=$code${msg?.let { ": $it" } ?: ""}")
        }
    }

    private val deviceCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            device ?: return
            if (!isUvcCamera(device)) return
            report("UVC attached: ${describe(device)}")
            ensureCamera(device)
            requestCameraPermission(device)
        }

        override fun onDetachDec(device: UsbDevice?) {
            device ?: return
            report("UVC detached: ${describe(device)}")
            removeCamera(device)
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (device == null || ctrlBlock == null || !isUvcCamera(device)) return
            requestingPermission.set(false)
            openCamera(device, ctrlBlock)
        }
        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            requestingPermission.set(false)
            report("UVC disconnected: ${describe(device)}")
            removeCamera(device)
        }

        override fun onCancelDev(device: UsbDevice?) {
            requestingPermission.set(false)
            bridgeState = "permission-denied"
            report("UVC permission denied: ${device?.let(::describe) ?: "unknown device"}")
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        bridgeState = "starting"
        try {
            server.start(SOCKET_READ_TIMEOUT, false)
            val client = MultiCameraClient(context, deviceCallback)
            cameraClient = client
            client.openDebug(false)
            client.register()
            bridgeState = "waiting-for-camera"
            report("camera server listening on :$CAMERA_PORT")

            client.getDeviceList()
                ?.firstOrNull(::isUvcCamera)
                ?.let { device ->
                    ensureCamera(device)
                    requestCameraPermission(device)
                }
        } catch (t: Throwable) {
            bridgeState = "start-error"
            started.set(false)
            runCatching { cameraClient?.unRegister() }
            runCatching { cameraClient?.destroy() }
            cameraClient = null
            runCatching { server.stop() }
            throw t
        }
    }
    private fun ensureCamera(device: UsbDevice): MultiCameraClient.ICamera {
        synchronized(cameras) {
            return cameras.getOrPut(device.deviceId) {
                CameraUVC(context, device).apply {
                    setCameraStateCallBack(cameraStateCallback)
                    addPreviewDataCallBack(previewCallback)
                }
            }
        }
    }

    private fun requestCameraPermission(device: UsbDevice) {
        if (activeCamera?.getUsbDevice()?.deviceId == device.deviceId) return
        if (!requestingPermission.compareAndSet(false, true)) return
        bridgeState = "requesting-usb-permission"
        if (cameraClient?.requestPermission(device) != true) {
            requestingPermission.set(false)
            bridgeState = "permission-request-failed"
            report("could not request UVC permission for ${describe(device)}")
        }
    }

    private fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        val current = activeCamera
        if (current != null && current.getUsbDevice().deviceId != device.deviceId) {
            report("ignoring extra UVC camera ${describe(device)}")
            return
        }
        val camera = ensureCamera(device)
        camera.setUsbControlBlock(ctrlBlock)
        activeCamera = camera
        latestFrame.set(null)
        bridgeState = "opening-camera"
        report("opening ${describe(device)} at ${PREVIEW_WIDTH}x$PREVIEW_HEIGHT")
        camera.openCamera<Any>(null, cameraRequest)
    }

    private fun removeCamera(device: UsbDevice) {
        val camera = synchronized(cameras) { cameras.remove(device.deviceId) }
        if (camera != null) {
            runCatching { camera.removePreviewDataCallBack(previewCallback) }
            runCatching { camera.closeCamera() }
        }
        if (activeCamera?.getUsbDevice()?.deviceId == device.deviceId) {
            activeCamera = null
            latestFrame.set(null)
            bridgeState = "waiting-for-camera"
        }
    }

    fun close() {
        if (!started.compareAndSet(true, false)) return
        bridgeState = "stopping"
        synchronized(cameras) {
            cameras.values.toList().forEach { camera ->
                runCatching { camera.removePreviewDataCallBack(previewCallback) }
                runCatching { camera.closeCamera() }
            }
            cameras.clear()
        }
        activeCamera = null
        latestFrame.set(null)
        cameraClient?.let { client ->
            runCatching { client.unRegister() }
            runCatching { client.destroy() }
        }
        cameraClient = null
        runCatching { server.stop() }
        encoder.shutdownNow()
        bridgeState = "stopped"
        report("camera bridge stopped")
    }

    private fun isUvcCamera(device: UsbDevice): Boolean {
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == USB_VIDEO_CLASS
        }
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
            val camera = activeCamera?.getUsbDevice()?.productName ?: "none"
            val body = """
                {"state":"$bridgeState","camera":"$camera","frame_id":${frame?.id ?: 0}}
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
        @Volatile
        private var closed = false
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
        private const val JPEG_QUALITY = 75
        private const val FRAME_INTERVAL_MS = 100L
        private const val STREAM_POLL_MS = 25L
        private const val USB_VIDEO_CLASS = 14
    }
}
