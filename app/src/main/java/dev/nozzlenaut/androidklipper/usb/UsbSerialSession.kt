package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.CommonUsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import java.io.IOException
import java.lang.reflect.InvocationTargetException
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

        // Use usb-serial-for-android's own protected read implementation,
        // but explicitly disable its optional connection test. This preserves
        // the driver's exact UsbRequest/buffer handling while preventing the
        // GET_STATUS control transfer which Fire OS + this STM32 Klipper CDC
        // device rejects after a zero-byte completion.
        usbReader = Thread({
            val commonPort = port as? CommonUsbSerialPort
                ?: throw IOException("Unsupported USB serial port implementation: ${port.javaClass.name}")
            val readMethod = CommonUsbSerialPort::class.java.getDeclaredMethod(
                "read",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val buf = ByteArray(4096)
            try {
                while (running.get()) {
                    val n = try {
                        readMethod.invoke(commonPort, buf, buf.size, 0, false) as Int
                    } catch (wrapped: InvocationTargetException) {
                        throw (wrapped.targetException ?: wrapped)
                    }
                    if (!running.get()) break
                    if (n > 0) {
                        usbBytesIn.addAndGet(n.toLong())
                        pty.write(buf.copyOf(n))
                    } else {
                        // Zero-byte completions are allowed; unlike the public
                        // read() path we intentionally do not send GET_STATUS.
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
