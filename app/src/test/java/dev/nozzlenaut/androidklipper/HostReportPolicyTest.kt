package dev.nozzlenaut.androidklipper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReportPolicyTest {
    private val failures = listOf(
        "USB bridge error for 3033393834057C77: USB bulk write retries exhausted",
        "Host startup ERROR IOException: disconnected",
        "MCUs identified\nMoonraker ERROR: port 7125 did not open"
    )

    @Test
    fun replayingPreviousFailuresLeavesFreshUsbStartupArmed() {
        // onCreate arms startup, onStart replays the saved report, then the
        // USB settle callback checks this flag before starting the host.
        for (report in failures) {
            var startupPending = true
            if (HostReportPolicy.canCancelAutoStart(report, HostReportSource.SAVED)) {
                startupPending = false
            }
            assertTrue("Saved failure cancelled the new startup: $report", startupPending)
        }
    }

    @Test
    fun currentFailuresStillCancelStartup() {
        for (report in failures) {
            assertTrue(HostReportPolicy.canCancelAutoStart(report, HostReportSource.LIVE))
        }
    }

    @Test
    fun ordinaryProgressAndReadyReportsNeverCancelStartup() {
        for (source in HostReportSource.values()) {
            for (report in listOf("Starting Moonraker", "Moonraker READY: http://127.0.0.1:7125", "Klippy READY")) {
                assertFalse(HostReportPolicy.canCancelAutoStart(report, source))
            }
        }
    }
}
