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
    private lateinit var port: UsbSerialPort
    private lateinit var ioManager: SerialInputOutputManager
    private var ptyReader: Thread? = null

    fun start() {
        port = driver.ports.firstOrNull() ?: throw IOException("USB serial device has no ports")
        port.open(connection)
        port.setParameters(250000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        // Do not assert DTR/RTS during diagnostics. Some printer controllers tie
        // control-line changes to reset/boot circuitry, so touching them can cause
        // observable hardware state changes even when no Klipper GPIO command is sent.

        running.set(true)
        ioManager = SerialInputOutputManager(port, this).apply {
            setReadBufferSize(4096)
            setWriteBufferSize(4096)
            start()
        }

        ptyReader = Thread({
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = pty.read(buf, 250)
                    if (n > 0) port.write(buf, n, 2000)
                } catch (t: Throwable) {
                    if (running.get()) onError(t)
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    override fun onNewData(data: ByteArray) {
        try {
            pty.write(data)
        } catch (t: Throwable) {
            if (running.get()) onError(t)
        }
    }

    override fun onRunError(e: Exception) {
        if (running.get()) onError(e)
    }

    override fun close() {
        running.set(false)
        runCatching { ioManager.stop() }
        runCatching { port.close() }
        ptyReader?.interrupt()
        runCatching { pty.close() }
        runCatching { connection.close() }
    }
}
