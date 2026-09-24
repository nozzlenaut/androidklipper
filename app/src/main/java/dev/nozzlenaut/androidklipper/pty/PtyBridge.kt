package dev.nozzlenaut.androidklipper.pty

/**
 * Tiny Kotlin wrapper around the native PTY helper.
 *
 * A PTY is basically a fake serial port pair. AndroidKlipper owns the master
 * side, while Klipper opens the slave path (for example /dev/pts/12) and thinks
 * it is talking to a normal Linux serial-ish device.
 *
 * Why bother? Android's USB APIs and Klipper's normal Linux serial code do not
 * speak the same language. The PTY is the adapter between them, which lets us
 * keep the Android-specific weirdness here instead of rewriting Klipper.
 */
class PtyBridge private constructor(
    private val masterFd: Int,
    val slavePath: String
) : AutoCloseable {

    // Bytes from Klipper arrive here and will be forwarded to the real USB MCU.
    fun read(buffer: ByteArray, timeoutMs: Int): Int = nativeRead(masterFd, buffer, timeoutMs)

    // Bytes from the real USB MCU are written here so Klipper can read them.
    fun write(data: ByteArray): Int = nativeWrite(masterFd, data)

    override fun close() {
        nativeClose(masterFd)
    }

    companion object {
        init { System.loadLibrary("androidklipper_pty") }

        /**
         * Native code creates the PTY and returns "<master fd>\n<slave path>".
         * Keeping that ugly detail inside this class means the rest of the app
         * only has to care about read(), write(), close(), and slavePath.
         */
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
