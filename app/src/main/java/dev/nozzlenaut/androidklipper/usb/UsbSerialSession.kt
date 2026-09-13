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
                    if (n > 0) {
                        val written = connection.bulkTransfer(port.writeEndpoint, buf, n, 2000)
                        if (written != n) {
                            throw IOException("USB bulk write failed: wrote $written of $n bytes")
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) onError(t)
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
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
