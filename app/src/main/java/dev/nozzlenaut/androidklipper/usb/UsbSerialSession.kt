package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Process
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
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
) : SerialInputOutputManager.Listener, AutoCloseable {

    private val running = AtomicBoolean(false)
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var usbReader: Thread? = null
    private var ptyReader: Thread? = null

    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val readRetryCount = AtomicLong(0)
    private val writeRetryCount = AtomicLong(0)
    private val maxWriteTransferUs = AtomicLong(0)

    fun start() {
        try {
            val serialPort = driver.ports.firstOrNull()
                ?: throw IOException("USB serial device has no ports")
            port = serialPort
            serialPort.open(connection)

            try {
                serialPort.setParameters(
                    250000,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
            } catch (t: Throwable) {
                // Klipper native USB is CDC bulk transport. The virtual UART baud
                // rate is irrelevant, and a few Android USB stacks reject the CDC
                // SET_LINE_CODING request even when the data endpoints are healthy.
                val isKlipperNativeUsb = isKlipperNativeUsb()
                val isLineCodingFailure =
                    t is IOException && t.message?.contains("controlTransfer failed") == true
                if (!isKlipperNativeUsb || !isLineCodingFailure) throw t
            }

            // Do not assert DTR/RTS. Some printer controllers tie control-line
            // changes to reset/boot circuitry.
            running.set(true)

            if (isKlipperNativeUsb()) {
                startKlipperBulkTransport(serialPort)
            } else {
                val manager = SerialInputOutputManager(serialPort, this).apply {
                    setReadBufferSize(BUFFER_SIZE)
                    setWriteBufferSize(BUFFER_SIZE)
                    start()
                }
                ioManager = manager
                startPtyToSerial(serialPort)
            }
        } catch (t: Throwable) {
            // Never leave a claimed interface, connection, or PTY behind after
            // a partial startup. Android can otherwise keep the next open sick.
            close()
            throw t
        }
    }

    private fun startKlipperBulkTransport(serialPort: UsbSerialPort) {
        val readEndpoint = serialPort.readEndpoint
        val writeEndpoint = serialPort.writeEndpoint

        usbReader = Thread({
            setUsbIoPriority()
            val buffer = ByteArray(BUFFER_SIZE)
            while (running.get()) {
                try {
                    val n = connection.bulkTransfer(
                        readEndpoint,
                        buffer,
                        buffer.size,
                        READ_TIMEOUT_MS
                    )
                    when {
                        n > 0 -> {
                            rxBytes.addAndGet(n.toLong())
                            val written = pty.write(buffer.copyOf(n))
                            if (written != n) {
                                throw IOException(
                                    "PTY write incomplete for $stableId: wrote $written of $n bytes"
                                )
                            }
                        }
                        n < 0 -> {
                            // Android reports both an idle timeout and a real USB
                            // failure as -1. A read-side -1 by itself is therefore
                            // not enough evidence to tear down a healthy Klipper
                            // session. The write path has a bounded retry window and
                            // Klipper itself detects a genuinely silent MCU.
                            readRetryCount.incrementAndGet()
                            Thread.sleep(READ_RETRY_BACKOFF_MS)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    failSession("USB read failed", t)
                    break
                }
            }
        }, "usb-to-pty-${stableId.takeLast(8)}").also { it.start() }

        ptyReader = Thread({
            setUsbIoPriority()
            val buffer = ByteArray(BUFFER_SIZE)
            while (running.get()) {
                try {
                    val n = pty.read(buffer, PTY_READ_TIMEOUT_MS)
                    if (n > 0) {
                        writeKlipperBulk(writeEndpoint, buffer, n)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    failSession("USB write failed", t)
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun startPtyToSerial(serialPort: UsbSerialPort) {
        ptyReader = Thread({
            setUsbIoPriority()
            val buffer = ByteArray(BUFFER_SIZE)
            while (running.get()) {
                try {
                    val n = pty.read(buffer, PTY_READ_TIMEOUT_MS)
                    if (n > 0) serialPort.write(buffer, n, SERIAL_WRITE_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    failSession("Serial write failed", t)
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun writeKlipperBulk(endpoint: UsbEndpoint, source: ByteArray, length: Int) {
        var offset = 0
        var consecutiveFailures = 0
        var failureWindowStartedAt = 0L

        while (offset < length && running.get()) {
            val remaining = length - offset
            // bulkTransfer() already packetizes to endpoint.maxPacketSize. Use its
            // offset overload so a normal Klipper burst is one Java -> USB call and
            // partial-write retries do not allocate/copy another byte array.
            val startedNs = System.nanoTime()
            val written = connection.bulkTransfer(
                endpoint,
                source,
                offset,
                remaining,
                WRITE_ATTEMPT_TIMEOUT_MS
            )
            val transferUs = (System.nanoTime() - startedNs) / 1_000
            maxWriteTransferUs.getAndUpdate { old -> maxOf(old, transferUs) }

            if (written > 0) {
                offset += written
                txBytes.addAndGet(written.toLong())
                consecutiveFailures = 0
                failureWindowStartedAt = 0L
                continue
            }

            if (consecutiveFailures == 0) {
                failureWindowStartedAt = System.nanoTime()
            }
            consecutiveFailures++
            writeRetryCount.incrementAndGet()

            val elapsedMs = (System.nanoTime() - failureWindowStartedAt) / 1_000_000
            if (elapsedMs >= MAX_WRITE_RETRY_WINDOW_MS) {
                throw IOException(
                    "USB bulk write retries exhausted for $stableId at $offset/$length " +
                        "after ${elapsedMs}ms (rc=$written, maxTransferUs=${maxWriteTransferUs.get()}); ${stats()}"
                )
            }
            Thread.sleep(WRITE_RETRY_BACKOFF_MS)
        }

        if (offset != length && running.get()) {
            throw IOException(
                "USB bulk write incomplete for $stableId: wrote $offset of $length bytes; ${stats()}"
            )
        }
    }

    private fun failSession(prefix: String, cause: Throwable) {
        if (!running.compareAndSet(true, false)) return
        onError(withStats(prefix, cause))
        usbReader?.let { if (it !== Thread.currentThread()) it.interrupt() }
        ptyReader?.let { if (it !== Thread.currentThread()) it.interrupt() }
    }

    private fun isKlipperNativeUsb(): Boolean {
        val device = driver.device
        return device.vendorId == UsbDeviceScanner.KLIPPER_VID &&
            device.productId == UsbDeviceScanner.KLIPPER_PID
    }

    private fun setUsbIoPriority() {
        // Six urgent-audio bridge threads (two per MCU) can starve Klippy on
        // weaker Android devices. USB still gets foreground priority, but the
        // Klippy reactor now runs above it.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
    }

    fun statsSnapshot(): String = stats()

    private fun stats(): String =
        "rx=${rxBytes.get()} tx=${txBytes.get()} " +
            "readRetries=${readRetryCount.get()} writeRetries=${writeRetryCount.get()} " +
            "maxWriteUs=${maxWriteTransferUs.get()}"

    private fun withStats(prefix: String, cause: Throwable): IOException =
        IOException("$prefix for $stableId: ${cause.message}; ${stats()}", cause)

    override fun onNewData(data: ByteArray) {
        try {
            val written = pty.write(data)
            if (written != data.size) {
                throw IOException("PTY write incomplete: wrote $written of ${data.size} bytes")
            }
        } catch (t: Throwable) {
            failSession("PTY write failed", t)
        }
    }

    override fun onRunError(e: Exception) {
        failSession("Serial I/O manager failed", e)
    }

    override fun close() {
        running.set(false)
        runCatching { ioManager?.stop() }
        ioManager = null

        val oldUsbReader = usbReader
        val oldPtyReader = ptyReader
        oldUsbReader?.interrupt()
        oldPtyReader?.interrupt()

        // Closing the port releases both CDC interfaces and closes the underlying
        // UsbDeviceConnection. Do this before join so a blocked bulkTransfer wakes.
        runCatching { port?.close() }
        port = null
        runCatching { connection.close() }
        runCatching { pty.close() }

        joinIoThread(oldUsbReader)
        joinIoThread(oldPtyReader)
        usbReader = null
        ptyReader = null
    }

    private fun joinIoThread(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        runCatching { thread.join(IO_THREAD_JOIN_TIMEOUT_MS) }
    }

    companion object {
        private const val BUFFER_SIZE = 4096
        private const val READ_TIMEOUT_MS = 250
        private const val PTY_READ_TIMEOUT_MS = 100
        private const val SERIAL_WRITE_TIMEOUT_MS = 2000
        private const val READ_RETRY_BACKOFF_MS = 1L
        private const val WRITE_RETRY_BACKOFF_MS = 1L
        private const val MAX_WRITE_RETRY_WINDOW_MS = 250L
        private const val WRITE_ATTEMPT_TIMEOUT_MS = 25
        private const val IO_THREAD_JOIN_TIMEOUT_MS = 500L
    }
}
