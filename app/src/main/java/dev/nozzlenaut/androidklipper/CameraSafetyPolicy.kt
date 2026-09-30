package dev.nozzlenaut.androidklipper

/** Pure safety rules for deciding when a UVC stream should be abandoned. */
internal object CameraSafetyPolicy {
    const val STALL_TIMEOUT_MS = 5_000L

    fun shouldStopForStaleFrame(
        cameraActive: Boolean,
        lastFrameAtElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        if (!cameraActive || lastFrameAtElapsedMs <= 0L) return false
        if (nowElapsedMs < lastFrameAtElapsedMs) return false
        return nowElapsedMs - lastFrameAtElapsedMs >= STALL_TIMEOUT_MS
    }
}
