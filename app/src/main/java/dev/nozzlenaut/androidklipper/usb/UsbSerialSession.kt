package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbSerialSession(
    private val driver: UsbSerialDriver,
    private val connection: UsbDeviceConnection,
    val stableId: String,
    val pty: PtyBridge,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {

    private val running = AtomicBoolean(false)
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
                    if (n > 0) pty.write(buf.copyOf(n))
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
                    if (n > 0) writeFully(buf, n)
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

            val written = connection.bulkTransfer(
                port.writeEndpoint,
                chunk,
                remaining,
                250
            )

            if (written > 0) {
                offset += written
                transientFailures = 0
                continue
            }

            // Android bulkTransfer() returns -1 for both a timeout and some
            // transient controller conditions. A single -1 does not mean the
            // Klipper MCU disappeared, so retry briefly before failing the link.
            transientFailures++
            if (transientFailures >= 8) {
                throw IOException(
                    "USB bulk write failed after retries: wrote $offset of $length bytes"
                )
            }
            Thread.sleep((transientFailures * 10L).coerceAtMost(50L))
        }

        if (offset != length && running.get()) {
            throw IOException("USB bulk write incomplete: wrote $offset of $length bytes")
        }
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
