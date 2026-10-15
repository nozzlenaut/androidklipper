package dev.nozzlenaut.androidklipper.usb

import dev.nozzlenaut.androidklipper.usb.UsbReadHealth.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbReadHealthTest {
    private val timeout = 250

    @Test
    fun anIdleLineNeverLooksDead() {
        val health = UsbReadHealth(timeout)
        var now = 0L
        // One hour of 250 ms idle timeouts.
        repeat(3600 * 4) {
            now += 251
            assertEquals(Verdict.IDLE, health.onTransfer(-1, 250, now))
        }
    }

    @Test
    fun receivedDataIsOk() {
        val health = UsbReadHealth(timeout)
        assertEquals(Verdict.OK, health.onTransfer(64, 1, 10))
        assertEquals(Verdict.OK, health.onTransfer(0, 1, 11)) // zero-length packet
    }

    @Test
    fun immediateFailuresAreSuspectThenDeadAfterTheWindow() {
        val health = UsbReadHealth(timeout, deadAfterMs = 5_000)
        var now = 1_000L
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 0, now))
        now += 4_999
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 0, now))
        now += 1
        assertEquals(Verdict.DEAD, health.onTransfer(-1, 0, now))
    }

    @Test
    fun dataInTheMiddleRestartsTheWindow() {
        val health = UsbReadHealth(timeout, deadAfterMs = 5_000)
        var now = 0L
        health.onTransfer(-1, 0, now)
        now += 4_000
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 0, now))
        now += 100
        assertEquals(Verdict.OK, health.onTransfer(8, 0, now))
        now += 4_000
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 0, now)) // new window just started
        now += 4_000
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 0, now))
    }

    @Test
    fun oneRealTimeoutInTheMiddleRestartsTheWindow() {
        val health = UsbReadHealth(timeout, deadAfterMs = 5_000)
        var now = 0L
        health.onTransfer(-1, 0, now)
        now += 4_500
        assertEquals(Verdict.IDLE, health.onTransfer(-1, 250, now)) // the line was alive
        now += 4_500
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 1, now))
    }

    @Test
    fun halfTheTimeoutIsTheBoundaryBetweenFastAndIdle() {
        val health = UsbReadHealth(timeout)
        assertEquals(Verdict.SUSPECT, health.onTransfer(-1, 124, 0))
        assertEquals(Verdict.IDLE, health.onTransfer(-1, 125, 1))
    }

    @Test
    fun aMillisecondSleepBetweenFailuresStillReachesDead() {
        // The reader loop sleeps READ_RETRY_BACKOFF_MS (1 ms) after every -1.
        val health = UsbReadHealth(timeout, deadAfterMs = 5_000)
        var now = 0L
        var verdict = Verdict.SUSPECT
        var iterations = 0
        while (verdict != Verdict.DEAD && iterations < 100_000) {
            now += 1
            iterations++
            verdict = health.onTransfer(-1, 0, now)
        }
        assertEquals(Verdict.DEAD, verdict)
        assertEquals(5_001, iterations) // first call starts the window at t=1
    }
}
