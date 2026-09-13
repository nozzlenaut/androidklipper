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
    private val usbReaderStarted = AtomicBoolean(false)
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

        // Deliberately do not start USB IN traffic yet. The known-good Phase 1
        // path opened each MCU and immediately sent Klipper identify. Full Klippy
        // spends several seconds parsing config first; repeatedly polling or
        // queueing reads during that idle window is what consistently poisons
        // the STM32 CDC connection on Fire OS. Start the reader only after the
        // first outbound Klipper packet has successfully reached the MCU.

        ptyReader = Thread({
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = pty.read(buf, 250)
                    if (n > 0) {
                        ptyBytesIn.addAndGet(n.toLong())
                        writeFully(buf, n)
                        ensureUsbReaderStarted()
                    }
                } catch (t: Throwable) {
                    if (running.get()) onError(t)
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun ensureUsbReaderStarted() {
        if (!usbReaderStarted.compareAndSet(false, true)) return

        // This is intentionally the exact direct-bulk read strategy which
        // successfully identified all three real Klipper MCUs in Phase 1.
        // It begins only after the first host->MCU packet is accepted, so the
        // STM32 never sits through config parsing with an idle IN transfer.
        usbReader = Thread({
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = connection.bulkTransfer(
                        port.readEndpoint,
                        buf,
                        buf.size,
                        250
                    )
                    if (n > 0) {
                        usbBytesIn.addAndGet(n.toLong())
                        pty.write(buf.copyOf(n))
                    } else {
                        usbReadMisses.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        lastError = "USB read failed: ${t.message}"
                        onError(t)
                    }
                    break
                }
            }
        }, "usb-to-pty-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun writeFully(source: ByteArray, length: Int) {
        // Klipper's initial packets are smaller than a USB max packet. Match
        // the known-good Phase 1 write path instead of issuing a sequence of
        // short retries which can further disturb Fire OS' USB host state.
        usbWriteCalls.incrementAndGet()
        val written = connection.bulkTransfer(
            port.writeEndpoint,
            source,
            length,
            2000
        )
        if (written > 0) usbBytesOut.addAndGet(written.toLong())
        if (written != length) {
            if (written <= 0) usbWriteRetries.incrementAndGet()
            val msg = "USB bulk write failed: wrote $written of $length bytes"
            lastError = msg
            throw IOException(msg)
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
