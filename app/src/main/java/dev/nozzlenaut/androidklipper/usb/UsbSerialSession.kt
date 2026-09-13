package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import java.io.IOException
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

        // usb-serial-for-android performs an optional USB GET_STATUS probe when
        // a zero-length/failed transfer is observed. Some Fire OS + Klipper CDC
        // combinations reject that request even though the bulk endpoints are
        // usable. For Klipper traffic, use the already-claimed bulk endpoints
        // directly and keep the library only for CDC discovery/open/setup.
        usbReader = Thread({
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = connection.bulkTransfer(port.readEndpoint, buf, buf.size, 250)
                    if (n > 0) {
                        usbBytesIn.addAndGet(n.toLong())
                        pty.write(buf.copyOf(n))
                    } else {
                        usbReadMisses.incrementAndGet()
                    }
                    // -1 is also Android's normal timeout result for bulkTransfer.
                    // The next transfer or Klipper handshake will tell us if the
                    // device really disappeared.
                } catch (t: Throwable) {
                    if (running.get()) onError(t)
                    break
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
        runCatching { port.close() }
        runCatching { pty.close() }
        runCatching { connection.close() }
    }
}
