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
    private val closed = AtomicBoolean(false)
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var ptyReader: Thread? = null

    fun start() {
        check(!closed.get()) { "USB serial session is already closed" }
        try {
            val openedPort = driver.ports.firstOrNull()
                ?: throw IOException("USB serial device has no ports")
            port = openedPort
            openedPort.open(connection)

            // The current milestone opens only native Klipper CDC devices.
            // Do not toggle DTR/RTS here: control-line behavior for generic
            // USB-to-UART adapters belongs in a later transport implementation.
            openedPort.setParameters(
                250000,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            running.set(true)
            val manager = SerialInputOutputManager(openedPort, this).apply {
                setReadBufferSize(4096)
                setWriteBufferSize(4096)
            }
            ioManager = manager
            manager.start()

            ptyReader = Thread({
                val buf = ByteArray(4096)
                while (running.get()) {
                    try {
                        val n = pty.read(buf, 250)
                        if (n > 0) openedPort.write(buf, n, 2000)
                    } catch (t: Throwable) {
                        fail(t)
                        break
                    }
                }
            }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
        } catch (t: Throwable) {
            running.set(false)
            closeResources()
            throw t
        }
    }

    override fun onNewData(data: ByteArray) {
        if (!running.get()) return
        try {
            val written = pty.write(data, 2000)
            if (written != data.size) {
                throw IOException("PTY write incomplete: $written/${data.size} bytes")
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    override fun onRunError(e: Exception) {
        fail(e)
    }

    private fun fail(t: Throwable) {
        if (!running.compareAndSet(true, false)) return
        runCatching { onError(t) }
        closeResources()
    }

    override fun close() {
        running.set(false)
        closeResources()
    }

    private fun closeResources() {
        if (!closed.compareAndSet(false, true)) return

        runCatching { ioManager?.stop() }
        runCatching { port?.close() }

        val reader = ptyReader
        reader?.interrupt()
        if (reader != null && Thread.currentThread() !== reader) {
            runCatching { reader.join(750) }
        }

        runCatching { pty.close() }
        runCatching { connection.close() }
    }
}
