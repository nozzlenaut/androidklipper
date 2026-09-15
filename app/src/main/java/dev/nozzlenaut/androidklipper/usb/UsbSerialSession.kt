package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbSerialSession(
    private val driver: UsbSerialDriver,
    private val connection: UsbDeviceConnection,
    val stableId: String,
    val pty: PtyBridge,
    private val onError: (Throwable) -> Unit
) : SerialInputOutputManager.Listener, AutoCloseable {

    private val running = AtomicBoolean(false)
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var ptyReader: Thread? = null

    fun start() {
        try {
            val serialPort = driver.ports.firstOrNull()
                ?: throw IOException("USB serial device has no ports")
            port = serialPort
            serialPort.open(connection)
            serialPort.setParameters(
                250000,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            // Do not assert DTR/RTS. Some printer controllers tie control-line
            // changes to reset/boot circuitry, so touching them can cause
            // observable hardware state changes even without Klipper GPIO.
            running.set(true)

            val manager = SerialInputOutputManager(serialPort, this).apply {
                setReadBufferSize(4096)
                setWriteBufferSize(4096)
                start()
            }
            ioManager = manager

            ptyReader = Thread({
                val buf = ByteArray(4096)
                while (running.get()) {
                    try {
                        val n = pty.read(buf, 250)
                        if (n > 0) serialPort.write(buf, n, 2000)
                    } catch (t: Throwable) {
                        if (running.get()) onError(t)
                        break
                    }
                }
            }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
        } catch (t: Throwable) {
            // A failed setParameters/open must never leave a claimed USB
            // interface or PTY behind. Leaked half-open sessions can make the
            // next Android USB attempt fail until the printer is power-cycled.
            close()
            throw t
        }
    }

    override fun onNewData(data: ByteArray) {
        try {
            val written = pty.write(data)
            if (written != data.size) {
                throw IOException("PTY write incomplete: wrote $written of ${data.size} bytes")
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    override fun onRunError(e: Exception) {
        if (running.get()) onError(e)
    }

    override fun close() {
        running.set(false)
        runCatching { ioManager?.stop() }
        ioManager = null
        runCatching { port?.close() }
        port = null
        ptyReader?.interrupt()
        ptyReader = null
        runCatching { pty.close() }
        runCatching { connection.close() }
    }
}
