package dev.nozzlenaut.androidklipper.usb

import dev.nozzlenaut.androidklipper.usb.UsbRecoveryPolicy.Decision
import dev.nozzlenaut.androidklipper.usb.UsbRecoveryPolicy.decide
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbRecoveryPolicyTest {
    private fun d(
        hostActive: Boolean = true,
        rebuildInProgress: Boolean = false,
        klippyReady: Boolean = true,
        anySessionFailed: Boolean = false,
        granted: Int = 3,
        sessions: Int = 3
    ) = decide(hostActive, rebuildInProgress, klippyReady, anySessionFailed, granted, sessions)

    @Test
    fun noHostMeansStartOne() {
        assertEquals(Decision.START_HOST, d(hostActive = false, sessions = 0))
    }

    @Test
    fun aDuplicateAttachDuringALivePrintIsIgnored() {
        assertEquals(Decision.IGNORE, d())
    }

    @Test
    fun aDuplicateAttachWhileStartingIsIgnored() {
        assertEquals(Decision.IGNORE, d(rebuildInProgress = true, klippyReady = false, granted = 3, sessions = 1))
        assertEquals(Decision.IGNORE, d(rebuildInProgress = true, anySessionFailed = true))
    }

    @Test
    fun aDeadSessionTriggersRecoveryEvenIfKlippyStillLooksReady() {
        assertEquals(Decision.REBUILD_LOST_DEVICE, d(anySessionFailed = true, klippyReady = true))
        assertEquals(Decision.REBUILD_LOST_DEVICE, d(anySessionFailed = true, klippyReady = false))
    }

    @Test
    fun aNewDeviceTriggersRecoveryOnlyWhenKlippyIsNotReady() {
        assertEquals(Decision.REBUILD_NEW_DEVICE, d(klippyReady = false, granted = 3, sessions = 2))
        // A live print must survive a stray extra device (e.g. someone plugs in a USB stick).
        assertEquals(Decision.IGNORE, d(klippyReady = true, granted = 4, sessions = 3))
    }

    @Test
    fun notReadyWithAllDevicesBridgedIsIgnored() {
        // Klippy in an error state with every MCU bridged: restarting USB would not help.
        assertEquals(Decision.IGNORE, d(klippyReady = false, granted = 3, sessions = 3))
    }

    @Test
    fun fewerDevicesThanSessionsAloneIsNotATrigger() {
        // Detach handling marks sessions failed; this policy does not guess from counts.
        assertEquals(Decision.IGNORE, d(granted = 2, sessions = 3))
    }
}
