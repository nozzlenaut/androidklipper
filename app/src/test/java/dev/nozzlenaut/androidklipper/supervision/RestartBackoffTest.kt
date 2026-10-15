package dev.nozzlenaut.androidklipper.supervision

import dev.nozzlenaut.androidklipper.supervision.RestartBackoff.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartBackoffTest {
    private fun delayOf(d: Decision) = (d as Decision.Restart).delayMs

    @Test
    fun delaysDoubleUpToTheCap() {
        val b = RestartBackoff(initialDelayMs = 1_000, maxDelayMs = 30_000, maxRestartsInWindow = 100)
        var now = 0L
        val delays = (1..8).map {
            val d = b.onUnexpectedExit(startedAtMs = now, nowMs = now + 500) // dies right away
            now += 1_000
            delayOf(d)
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun aCrashLoopIsEventuallyGivenUp() {
        val b = RestartBackoff(maxRestartsInWindow = 5)
        var now = 0L
        repeat(5) {
            assertTrue(b.onUnexpectedExit(now, now + 100) is Decision.Restart)
            now += 5_000
        }
        val sixth = b.onUnexpectedExit(now, now + 100)
        assertTrue(sixth is Decision.GiveUp)
        assertEquals(5, (sixth as Decision.GiveUp).restartsInWindow)
    }

    @Test
    fun aLongHealthyRunResetsTheBackoff() {
        val b = RestartBackoff(initialDelayMs = 1_000, maxRestartsInWindow = 100)
        b.onUnexpectedExit(0, 100)
        b.onUnexpectedExit(1_000, 1_100)
        assertEquals(4_000L, delayOf(b.onUnexpectedExit(2_000, 2_100)))
        // Ran for 10 minutes, then died: back to the initial delay.
        assertEquals(1_000L, delayOf(b.onUnexpectedExit(10_000, 10_000 + 10 * 60_000L)))
    }

    @Test
    fun oldRestartsFallOutOfTheWindow() {
        val b = RestartBackoff(maxRestartsInWindow = 3, windowMs = 60_000, healthyRunMs = 1_000_000)
        assertTrue(b.onUnexpectedExit(0, 10) is Decision.Restart)
        assertTrue(b.onUnexpectedExit(20, 30) is Decision.Restart)
        assertTrue(b.onUnexpectedExit(40, 50) is Decision.Restart)
        assertTrue(b.onUnexpectedExit(60, 70) is Decision.GiveUp)
        // A minute and a bit later the earlier restarts no longer count.
        assertTrue(b.onUnexpectedExit(70_000, 70_100) is Decision.Restart)
    }

    @Test
    fun attemptNumberCountsRestartsInTheWindow() {
        val b = RestartBackoff(maxRestartsInWindow = 10)
        assertEquals(1, (b.onUnexpectedExit(0, 1) as Decision.Restart).attempt)
        assertEquals(2, (b.onUnexpectedExit(2, 3) as Decision.Restart).attempt)
    }
}
