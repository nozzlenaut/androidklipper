package dev.nozzlenaut.androidklipper.supervision

/**
 * Restart policy for a worker that should stay up (Moonraker): exponential
 * backoff between restarts, and a crash-loop cap so a worker that dies
 * immediately every time is eventually left stopped (with a loud status)
 * instead of being restarted forever.
 *
 * Pure and clock-injected so it can be unit-tested.
 */
class RestartBackoff(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val maxRestartsInWindow: Int = 5,
    private val windowMs: Long = 10 * 60_000L,
    /** A run that lasted at least this long counts as healthy and clears the backoff. */
    private val healthyRunMs: Long = 60_000L
) {
    sealed class Decision {
        data class Restart(val delayMs: Long, val attempt: Int) : Decision()
        data class GiveUp(val restartsInWindow: Int) : Decision()
    }

    private val restartTimes = ArrayDeque<Long>()
    private var consecutiveFailures = 0

    /**
     * Call when the worker exited unexpectedly.
     * @param startedAtMs when this run started
     * @param nowMs when it exited
     */
    fun onUnexpectedExit(startedAtMs: Long, nowMs: Long): Decision {
        if (nowMs - startedAtMs >= healthyRunMs) consecutiveFailures = 0
        while (restartTimes.isNotEmpty() && nowMs - restartTimes.first() > windowMs) {
            restartTimes.removeFirst()
        }
        if (restartTimes.size >= maxRestartsInWindow) {
            return Decision.GiveUp(restartTimes.size)
        }
        val delay = minOf(maxDelayMs, initialDelayMs shl minOf(consecutiveFailures, 20))
        consecutiveFailures++
        restartTimes.addLast(nowMs)
        return Decision.Restart(delay, restartTimes.size)
    }
}
