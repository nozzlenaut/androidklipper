package dev.nozzlenaut.androidklipper

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class LocalStatusServer(
    private val statusProvider: () -> String?
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-status").apply { isDaemon = true }
    }
    @Volatile private var server: ServerSocket? = null

    fun start(port: Int = 7715) {
        if (server != null) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
        server = socket
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                client.use {
                    val body = (statusProvider() ?: "No AndroidKlipper status yet.") + "\n"
                    val payload = body.toByteArray(StandardCharsets.UTF_8)
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/plain; charset=utf-8\r\n")
                        append("Content-Length: ${payload.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(StandardCharsets.US_ASCII)
                    runCatching {
                        it.getOutputStream().use { out ->
                            out.write(header)
                            out.write(payload)
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
        executor.shutdownNow()
    }
}
