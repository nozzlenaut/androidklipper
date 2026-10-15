package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Process
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import dev.nozzlenaut.androidklipper.pty.PtyWriter
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

    /** True once the session died on its own (error or unplug), as opposed to close(). */
    @Volatile
    var hasFailed: Boolean = false
        private set

    /** Android's device path (/dev/bus/usb/BBB/DDD), used to match detach broadcasts. */
    val deviceName: String get() = driver.device.deviceName
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var usbReader: Thread? = null
    private var ptyReader: Thread? = null

    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val readRetryCount = AtomicLong(0)
    private val writeRetryCount = AtomicLong(0)
    private val maxWriteTransferUs = AtomicLong(0)
    private val ptyStallCount = AtomicLong(0)
    private val ptyFdLeaked = AtomicBoolean(false)

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
            val health = UsbReadHealth(READ_TIMEOUT_MS)
            while (running.get()) {
                try {
                    val startedNs = System.nanoTime()
                    val n = connection.bulkTransfer(
                        readEndpoint,
                        buffer,
                        buffer.size,
                        READ_TIMEOUT_MS
                    )
                    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                    val verdict = health.onTransfer(n, elapsedMs, SystemClock.elapsedRealtime())
                    when {
                        n > 0 -> {
                            rxBytes.addAndGet(n.toLong())
                            writeToPty(buffer, n)
                        }
                        n < 0 -> {
                            // Android reports both an idle timeout and a real USB
                            // failure as -1. A read-side -1 by itself is therefore
                            // not enough evidence to tear down a healthy Klipper
                            // session: an idle line waits out the timeout, so only
                            // a long unbroken run of *immediate* failures (no data,
                            // no timeout) is treated as a dead device. Normally the
                            // ACTION_USB_DEVICE_DETACHED receiver gets there first.
                            readRetryCount.incrementAndGet()
                            if (verdict == UsbReadHealth.Verdict.DEAD) {
                                throw IOException(
                                    "USB read endpoint failing immediately for " +
                                        "${UsbReadHealth.DEFAULT_DEAD_AFTER_MS} ms; device likely unplugged"
                                )
                            }
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

    /**
     * Hands bytes to Klippy through the PTY without ever blocking in JNI.
     * Waits out short stalls (Klippy GC pause, reactor restart), exits quietly
     * on shutdown, and fails loudly when the PTY has not drained for seconds.
     */
    private fun writeToPty(buffer: ByteArray, length: Int) {
        PtyWriter.writeAll(
            length = length,
            isRunning = { running.get() },
            nowMs = { SystemClock.elapsedRealtime() },
            onStall = { ptyStallCount.incrementAndGet() },
            writeChunk = { offset, remaining -> pty.write(buffer, remaining, offset) }
        )
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

    /** Android reported this device unplugged: end the session now, loudly. */
    fun markDetached() {
        failSession("USB device detached", IOException("$deviceName was unplugged"))
    }

    private fun failSession(prefix: String, cause: Throwable) {
        if (!running.compareAndSet(true, false)) return
        hasFailed = true
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
            "ptyStalls=${ptyStallCount.get()} maxWriteUs=${maxWriteTransferUs.get()}" +
            if (ptyFdLeaked.get()) " ptyFdLeaked=true" else ""

    private fun withStats(prefix: String, cause: Throwable): IOException =
        IOException("$prefix for $stableId: ${cause.message}; ${stats()}", cause)

    override fun onNewData(data: ByteArray) {
        try {
            writeToPty(data, data.size)
        } catch (t: Throwable) {
            failSession("PTY write failed", t)
        }
    }

    override fun onRunError(e: Exception) {
        failSession("Serial I/O manager failed", e)
    }

    /**
     * Ask the bridge threads to stop without waiting for them. Lets the service
     * stop every session in parallel before the slower, ordered close().
     */
    fun requestStop() {
        running.set(false)
        usbReader?.interrupt()
        ptyReader?.interrupt()
    }

    override fun close() {
        running.set(false)
        runCatching { ioManager?.stop() }
        ioManager = null

        val oldUsbReader = usbReader
        val oldPtyReader = ptyReader
        oldUsbReader?.interrupt()
        oldPtyReader?.interrupt()

        // Order matters. Every native call a bridge thread makes is bounded
        // (USB reads 250 ms, PTY poll/write 100 ms), so first let the threads
        // leave their loops on their own. Closing the PTY fd while another
        // thread is still inside read()/poll()/write() on it lets Linux reuse
        // that fd number for something unrelated.
        var stopped = joinIoThreads(oldUsbReader, oldPtyReader)

        // A thread that is still alive is blocked in bulkTransfer(). Closing the
        // port releases both CDC interfaces and the UsbDeviceConnection, which
        // wakes it.
        runCatching { port?.close() }
        port = null
        runCatching { connection.close() }
        if (!stopped) stopped = joinIoThreads(oldUsbReader, oldPtyReader)

        if (stopped) {
            runCatching { pty.close() }
        } else {
            // Leaking one fd is better than closing it under a live thread.
            ptyFdLeaked.set(true)
        }
        usbReader = null
        ptyReader = null
    }

    /** True when every given thread has exited (or is the calling thread). */
    private fun joinIoThreads(vararg threads: Thread?): Boolean {
        val deadline = SystemClock.elapsedRealtime() + IO_THREAD_JOIN_TIMEOUT_MS
        var allStopped = true
        for (thread in threads) {
            if (thread == null || thread === Thread.currentThread()) continue
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) runCatching { thread.join(remaining) }
            if (thread.isAlive) allStopped = false
        }
        return allStopped
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
        private const val IO_THREAD_JOIN_TIMEOUT_MS = 700L
    }
}
