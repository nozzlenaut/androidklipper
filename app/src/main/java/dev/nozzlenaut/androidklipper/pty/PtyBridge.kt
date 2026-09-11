package dev.nozzlenaut.androidklipper.pty

import java.util.concurrent.atomic.AtomicBoolean

class PtyBridge private constructor(
    private val masterFd: Int,
    private val slaveAnchorFd: Int,
    val slavePath: String
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        check(!closed.get()) { "PTY is closed" }
        return nativeRead(masterFd, buffer, timeoutMs)
    }

    fun write(data: ByteArray, timeoutMs: Int = 2000): Int {
        check(!closed.get()) { "PTY is closed" }
        return nativeWrite(masterFd, data, timeoutMs)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            nativeClose(masterFd, slaveAnchorFd)
        }
    }

    companion object {
        init { System.loadLibrary("androidklipper_pty") }

        fun create(): PtyBridge {
            val parts = nativeCreate().split('\n', limit = 3)
            check(parts.size == 3) { "Invalid PTY result" }
            val masterFd = parts[0].toInt()
            val slaveAnchorFd = parts[1].toInt()
            val path = parts[2]
            check(masterFd >= 0 && slaveAnchorFd >= 0 && path.isNotBlank()) {
                "Unable to create Android PTY"
            }
            return PtyBridge(masterFd, slaveAnchorFd, path)
        }

        @JvmStatic private external fun nativeCreate(): String
        @JvmStatic private external fun nativeRead(fd: Int, buffer: ByteArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeWrite(fd: Int, data: ByteArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeClose(masterFd: Int, slaveAnchorFd: Int)
    }
}
