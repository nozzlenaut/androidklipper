package dev.nozzlenaut.androidklipper.net

import android.os.Process
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import dev.nozzlenaut.androidklipper.transport.McuSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Presents a remote Bridge MCU to Klippy as the same PTY abstraction used by
 * local Android USB.
 */
class NetworkSerialSession(
    override val stableId: String,
    override val pty: PtyBridge,
    private val address: InetAddress,
    private val port: Int,
    private val onError: (Throwable) -> Unit
) : McuSession {

    private val running = AtomicBoolean(false)
    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)

    private var socket: Socket? = null
    private var socketReader: Thread? = null
    private var ptyReader: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            require(port in 1..65535) {
                "Invalid bridge TCP port $port for $stableId"
            }
            val connected = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
            socket = connected

            val input = BufferedInputStream(connected.getInputStream(), BUFFER_SIZE)
            val output = BufferedOutputStream(connected.getOutputStream(), BUFFER_SIZE)

            socketReader = Thread({
                setIoPriority()
                val buffer = ByteArray(BUFFER_SIZE)

                try {
                    while (running.get()) {
                        val n = input.read(buffer)
                        if (n < 0) throw IOException("Bridge socket closed")
                        if (n == 0) continue

                        rxBytes.addAndGet(n.toLong())
                        val written = pty.write(buffer.copyOf(n))
                        if (written != n) {
                            throw IOException(
                                "PTY write incomplete for $stableId: $written/$n"
                            )
                        }
                    }
                } catch (_: InterruptedException) {
                    // Normal shutdown.
                } catch (t: Throwable) {
                    fail("Bridge read failed", t)
                }
            }, "bridge-to-pty-${stableId.takeLast(8)}").also { it.start() }

            ptyReader = Thread({
                setIoPriority()
                val buffer = ByteArray(BUFFER_SIZE)

                try {
                    while (running.get()) {
                        val n = pty.read(buffer, PTY_READ_TIMEOUT_MS)
                        if (n <= 0) continue

                        output.write(buffer, 0, n)
                        output.flush()
                        txBytes.addAndGet(n.toLong())
                    }
                } catch (_: InterruptedException) {
                    // Normal shutdown.
                } catch (t: Throwable) {
                    fail("Bridge write failed", t)
                }
            }, "pty-to-bridge-${stableId.takeLast(8)}").also { it.start() }
        } catch (t: Throwable) {
            running.set(false)
            close()
            throw t
        }
    }

    private fun fail(prefix: String, cause: Throwable) {
        if (!running.compareAndSet(true, false)) return
        onError(
            IOException(
                "$prefix for $stableId: ${cause.message}; ${statsSnapshot()}",
                cause
            )
        )
        runCatching { socket?.close() }
        socketReader?.let { if (it !== Thread.currentThread()) it.interrupt() }
        ptyReader?.let { if (it !== Thread.currentThread()) it.interrupt() }
    }

    private fun setIoPriority() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
    }

    override fun statsSnapshot(): String =
        "transport=bridge host=${address.hostAddress}:$port " +
            "rx=${rxBytes.get()} tx=${txBytes.get()}"

    override fun close() {
        running.set(false)

        val oldSocketReader = socketReader
        val oldPtyReader = ptyReader

        oldSocketReader?.interrupt()
        oldPtyReader?.interrupt()

        runCatching { socket?.close() }
        socket = null
        runCatching { pty.close() }

        join(oldSocketReader)
        join(oldPtyReader)

        socketReader = null
        ptyReader = null
    }

    private fun join(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) }
    }

    companion object {
        private const val BUFFER_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val PTY_READ_TIMEOUT_MS = 100
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }
}
