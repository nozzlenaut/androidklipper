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
            setUrgentIoPriority()
            val buffer = ByteArray(BUFFER_SIZE)
            var consecutiveFailures = 0
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
                            consecutiveFailures = 0
                            rxBytes.addAndGet(n.toLong())
                            val written = pty.write(buffer.copyOf(n))
                            if (written != n) {
                                throw IOException(
                                    "PTY write incomplete for $stableId: wrote $written of $n bytes"
                                )
                            }
                        }
                        n < 0 -> {
                            // Android collapses both timeout-ish conditions and real
                            // USB errors to -1 here. One -1 must not kill Klipper.
                            consecutiveFailures++
                            readRetryCount.incrementAndGet()
                            if (consecutiveFailures >= MAX_CONSECUTIVE_READ_FAILURES &&
                                !usbDeviceStillPresent()
                            ) {
                                throw IOException(
                                    "USB device disappeared during read for $stableId; ${stats()}"
                                )
                            }
                            Thread.sleep(READ_RETRY_BACKOFF_MS)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (running.get()) onError(withStats("USB read failed", t))
                    break
                }
            }
        }, "usb-to-pty-${stableId.takeLast(8)}").also { it.start() }

        ptyReader = Thread({
            setUrgentIoPriority()
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
                    if (running.get()) onError(withStats("USB write failed", t))
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun startPtyToSerial(serialPort: UsbSerialPort) {
        ptyReader = Thread({
            setUrgentIoPriority()
            val buffer = ByteArray(BUFFER_SIZE)
            while (running.get()) {
                try {
                    val n = pty.read(buffer, PTY_READ_TIMEOUT_MS)
                    if (n > 0) serialPort.write(buffer, n, SERIAL_WRITE_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (running.get()) onError(withStats("Serial write failed", t))
                    break
                }
            }
        }, "pty-to-usb-${stableId.takeLast(8)}").also { it.start() }
    }

    private fun writeKlipperBulk(endpoint: UsbEndpoint, source: ByteArray, length: Int) {
        var offset = 0
        var consecutiveFailures = 0
        val packetSize = endpoint.maxPacketSize.coerceAtLeast(1)
        val startedAt = System.nanoTime()

        while (offset < length && running.get()) {
            val chunkLength = minOf(length - offset, packetSize)
            val chunk = if (offset == 0 && chunkLength == length) {
                source.copyOf(length)
            } else {
                source.copyOfRange(offset, offset + chunkLength)
            }

            val written = connection.bulkTransfer(
                endpoint,
                chunk,
                chunkLength,
                WRITE_ATTEMPT_TIMEOUT_MS
            )

            if (written > 0) {
                offset += written
                txBytes.addAndGet(written.toLong())
                consecutiveFailures = 0
                continue
            }

            // Android's Java USB API only gives us -1 here. A single immediate
            // failure is not proof of disconnect; retry the exact unsent chunk.
            consecutiveFailures++
            writeRetryCount.incrementAndGet()

            if (!usbDeviceStillPresent()) {
                throw IOException(
                    "USB device disappeared while writing $stableId at $offset/$length; ${stats()}"
                )
            }

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (consecutiveFailures >= MAX_CONSECUTIVE_WRITE_FAILURES ||
                elapsedMs >= MAX_WRITE_RETRY_WINDOW_MS
            ) {
                throw IOException(
                    "USB bulk write retries exhausted for $stableId at $offset/$length " +
                        "after ${elapsedMs}ms (rc=$written, packet=$packetSize); ${stats()}"
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

    private fun usbDeviceStillPresent(): Boolean {
        val device = driver.device
        return runCatching {
            // The descriptor call forces Android to touch the live connection.
            // A cached UsbDevice object alone is not enough to prove attachment.
            connection.rawDescriptors?.isNotEmpty() == true && device.deviceName.isNotBlank()
        }.getOrDefault(false)
    }

    private fun isKlipperNativeUsb(): Boolean {
        val device = driver.device
        return device.vendorId == UsbDeviceScanner.KLIPPER_VID &&
            device.productId == UsbDeviceScanner.KLIPPER_PID
    }

    private fun setUrgentIoPriority() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    }

    private fun stats(): String =
        "rx=${rxBytes.get()} tx=${txBytes.get()} " +
            "readRetries=${readRetryCount.get()} writeRetries=${writeRetryCount.get()}"

    private fun withStats(prefix: String, cause: Throwable): IOException =
        IOException("$prefix for $stableId: ${cause.message}; ${stats()}", cause)

    override fun onNewData(data: ByteArray) {
        try {
            val written = pty.write(data)
            if (written != data.size) {
                throw IOException("PTY write incomplete: wrote $written of ${data.size} bytes")
            }
        } catch (t: Throwable) {
            if (running.get()) onError(withStats("PTY write failed", t))
        }
    }

    override fun onRunError(e: Exception) {
        if (running.get()) onError(withStats("Serial I/O manager failed", e))
    }

    override fun close() {
        running.set(false)
        runCatching { ioManager?.stop() }
        ioManager = null
        usbReader?.interrupt()
        ptyReader?.interrupt()
        usbReader = null
        ptyReader = null
        runCatching { port?.close() }
        port = null
        runCatching { pty.close() }
        // port.close() closes the same UsbDeviceConnection in the library,
        // but close again is harmless and keeps ownership explicit here.
        runCatching { connection.close() }
    }

    companion object {
        private const val BUFFER_SIZE = 4096
        private const val READ_TIMEOUT_MS = 250
        private const val PTY_READ_TIMEOUT_MS = 100
        private const val SERIAL_WRITE_TIMEOUT_MS = 2000
        private const val READ_RETRY_BACKOFF_MS = 1L
        private const val WRITE_RETRY_BACKOFF_MS = 1L
        private const val MAX_CONSECUTIVE_READ_FAILURES = 20
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 12
        private const val MAX_WRITE_RETRY_WINDOW_MS = 250L
        private const val WRITE_ATTEMPT_TIMEOUT_MS = 25
    }
}
