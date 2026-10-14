package dev.nozzlenaut.androidklipper.pty

import java.io.IOException

/**
 * Android-free policy for pushing bytes into the PTY toward Klippy.
 *
 * The native write is bounded (see PtyBridge.write), so this loop is what turns
 * "Klippy is not draining right now" into either a short wait, a clean shutdown,
 * or a loud failure after a long stall, instead of a USB-reader thread that is
 * stuck inside JNI forever.
 */
object PtyWriter {
    /**
     * Klippy normally drains the PTY within milliseconds. A multi-second stall
     * means Klippy is gone or wedged; the MCU will already have timed out.
     */
    const val DEFAULT_STALL_LIMIT_MS = 5_000L

    class StallException(message: String) : IOException(message)

    /**
     * Writes buffer[0, length) using [writeChunk] (offset, remaining) -> written.
     *
     * - returns normally when every byte was written, or when [isRunning]
     *   turned false (shutdown), in which case the remaining bytes are dropped;
     * - throws [StallException] when no progress was made for [stallLimitMs];
     * - throws [IOException] when [writeChunk] reports a hard error (< 0).
     *
     * [onStall] is invoked once per stalled attempt with the stall time so far.
     * Stall time is measured from the first empty attempt to the latest one, so
     * it under-reports the real wait by at most one native write timeout.
     */
    inline fun writeAll(
        length: Int,
        isRunning: () -> Boolean,
        nowMs: () -> Long,
        stallLimitMs: Long = DEFAULT_STALL_LIMIT_MS,
        onStall: (stalledMs: Long) -> Unit = {},
        writeChunk: (offset: Int, remaining: Int) -> Int
    ): Int {
        var offset = 0
        var stallStartedAt = -1L
        while (offset < length && isRunning()) {
            val n = writeChunk(offset, length - offset)
            if (n < 0) throw IOException("PTY write failed at $offset/$length (rc=$n)")
            if (n > 0) {
                offset += n
                stallStartedAt = -1L
                continue
            }
            val now = nowMs()
            if (stallStartedAt < 0) stallStartedAt = now
            val stalled = now - stallStartedAt
            onStall(stalled)
            if (stalled >= stallLimitMs) {
                throw StallException(
                    "PTY not draining: no progress for ${stalled}ms at $offset/$length"
                )
            }
        }
        return offset
    }
}
