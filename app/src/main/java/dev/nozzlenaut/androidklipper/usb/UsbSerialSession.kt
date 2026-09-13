package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.CommonUsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class UsbSerialSession(
    private val driver: UsbSerialDriver,
    private val connection: UsbDeviceConnection,
    val stableId: String,
    val pty: PtyBridge,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {

    private val running = AtomicBoolean(false)
    private val usbBytesIn = AtomicLong(0)
    private val usbBytesOut = AtomicLong(0)
    private val ptyBytesIn = AtomicLong(0)
    private val usbWriteCalls = AtomicLong(0)
    private val usbWriteRetries = AtomicLong(0)
    private val usbReadMisses = AtomicLong(0)
    private val startedAtMs = System.currentTimeMillis()
    @Volatile private var lastError: String? = null
    private lateinit var port: UsbSerialPort
    private var usbReader: Thread? = null
    private var ptyReader: Thread? = null

    fun start() {
        port = driver.ports.firstOrNull() ?: throw IOException("USB serial device has no ports")
        port.open(connection)
        port.setParameters(250000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching { port.dtr = true }
        runCatching { port.rts = true }

        // Give Fire OS / the USB controller a moment to finish endpoint setup
        // before Klippy starts sending its first protocol frames.
        Thread.sleep(80)
        running.set(true)

        // Reuse usb-serial-for-android's already-initialized read request but
        // bypass CommonUsbSerialPort.read(timeout=0), because v3.11.0 sends a
        // USB GET_STATUS control request whenever a blocking read completes
        // with zero bytes. Klipper's STM32F446 CDC device on Fire OS rejects
        // GET_STATUS even though its bulk endpoints remain valid.
        usbReader = Thread({
            val commonPort = port as? CommonUsbSerialPort
                ?: throw IOException("Unsupported USB serial port implementation: ${port.javaClass.name}")
            val readRequestField = CommonUsbSerialPort::class.java
                .getDeclaredField("mReadRequest")
                .apply { isAccessible = true }
            val request = readRequestField.get(commonPort) as? UsbRequest
                ?: throw IOException("USB serial driver has no active read request")

            val buffer = ByteBuffer.allocate(4096)
            try {
                while (running.get()) {
                    buffer.clear()
                    if (!request.queue(buffer, buffer.capacity())) {
                        throw IOException("Unable to queue driver's USB read request")
                    }
                    val response = connection.requestWait()
                    if (!running.get()) break
                    if (response == null) {
                        throw IOException("USB read requestWait returned null")
                    }
                    if (response !== request) {
                        throw IOException("Unexpected USB request completed")
                    }

                    val n = buffer.position()
                    if (n > 0) {
                        buffer.flip()
                        val data = ByteArray(n)
                        buffer.get(data)
                        usbBytesIn.addAndGet(n.toLong())
                        pty.write(data)
                    } else {
                        // Zero-byte USB completions are harmless on the Klipper
                        // STM32 CDC endpoint. Requeue instead of probing it with
                        // GET_STATUS (which Fire OS reports as a failure).
                        usbReadMisses.incrementAndGet()
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    lastError = "USB read failed: ${t.message}"
                    onError(t)
                }
            }
        }, "usb-to-pty-${stableId.takeLast(8)}").also { it.start() }

        ptyReader = Thread({
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = pty.read(buf, 250)
                    if (n > 0) {
                        ptyBytesIn.addAndGet(n.toLong())
                        writeFully(buf, n)
                    }
                } catch (t: Throwable) {
                    if (running.get()) onError(t)
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun writeFully(source: ByteArray, length: Int) {
        var offset = 0
        var transientFailures = 0

        while (running.get() && offset < length) {
            val remaining = length - offset
            val chunk = if (offset == 0 && remaining == source.size) {
                source
            } else {
                source.copyOfRange(offset, length)
            }

            val written = synchronized(globalUsbWriteLock) {
                connection.bulkTransfer(
                    port.writeEndpoint,
                    chunk,
                    remaining,
                    500
                )
            }

            usbWriteCalls.incrementAndGet()
            if (written > 0) {
                usbBytesOut.addAndGet(written.toLong())
                offset += written
                transientFailures = 0
                continue
            }

            // Android bulkTransfer() returns -1 for both a timeout and some
            // transient controller conditions. A single -1 does not mean the
            // Klipper MCU disappeared, so retry briefly before failing the link.
            transientFailures++
            usbWriteRetries.incrementAndGet()
            if (transientFailures >= 8) {
                val msg = "USB bulk write failed after retries: wrote $offset of $length bytes"
                lastError = msg
                throw IOException(msg)
            }
            Thread.sleep((transientFailures * 10L).coerceAtMost(50L))
        }

        if (offset != length && running.get()) {
            throw IOException("USB bulk write incomplete: wrote $offset of $length bytes")
        }
    }

    fun telemetry(): String {
        val ageMs = System.currentTimeMillis() - startedAtMs
        return buildString {
            append(stableId)
            append(": age="); append(ageMs); append("ms")
            append(", usb_in="); append(usbBytesIn.get())
            append(", pty_outbound="); append(ptyBytesIn.get())
            append(", usb_out="); append(usbBytesOut.get())
            append(", write_calls="); append(usbWriteCalls.get())
            append(", write_retries="); append(usbWriteRetries.get())
            append(", read_misses="); append(usbReadMisses.get())
            lastError?.let { append(", last_error="); append(it) }
        }
    }

    companion object {
        // Older Android / Fire OS USB host stacks can behave poorly when
        // several UsbDeviceConnection bulk OUT calls are issued concurrently.
        // Klipper packets are tiny, so serializing writes adds negligible
        // latency while preserving independent read threads for all MCUs.
        private val globalUsbWriteLock = Any()
    }

    override fun close() {
        running.set(false)
        usbReader?.interrupt()
        ptyReader?.interrupt()
        // Closing the port is what interrupts the blocking timeout=0 read.
        runCatching { port.close() }
        runCatching { pty.close() }
        runCatching { connection.close() }
    }
}
