package dev.nozzlenaut.androidklipper.pty

class PtyBridge private constructor(
    private val masterFd: Int,
    val slavePath: String
) : AutoCloseable {

    fun read(buffer: ByteArray, timeoutMs: Int): Int = nativeRead(masterFd, buffer, timeoutMs)
    fun write(data: ByteArray): Int = nativeWrite(masterFd, data)

    override fun close() {
        nativeClose(masterFd)
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
        @JvmStatic private external fun nativeWrite(fd: Int, data: ByteArray): Int
        @JvmStatic private external fun nativeClose(fd: Int)
    }
}
