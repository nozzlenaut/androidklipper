package dev.nozzlenaut.androidklipper.pty

import java.util.concurrent.atomic.AtomicBoolean

class PtyBridge private constructor(
    private val masterFd: Int,
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
            nativeClose(masterFd)
        }
    }

    companion object {
        init { System.loadLibrary("androidklipper_pty") }

        fun create(): PtyBridge {
            val encoded = nativeCreate()
            val split = encoded.indexOf('\n')
            check(split > 0) { "Invalid PTY result: $encoded" }
            val fd = encoded.substring(0, split).toInt()
            val path = encoded.substring(split + 1)
            check(fd >= 0 && path.isNotBlank()) { "Unable to create Android PTY" }
            return PtyBridge(fd, path)
        }

        @JvmStatic private external fun nativeCreate(): String
        @JvmStatic private external fun nativeRead(fd: Int, buffer: ByteArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeWrite(fd: Int, data: ByteArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeClose(fd: Int)
    }
}
