package dev.nozzlenaut.androidklipper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSafetyPolicyTest {
    @Test fun inactiveCameraNeverTrips() {
        assertFalse(CameraSafetyPolicy.shouldStopForStaleFrame(false, 1_000L, 20_000L))
    }

    @Test fun freshFrameDoesNotTrip() {
        assertFalse(CameraSafetyPolicy.shouldStopForStaleFrame(true, 10_000L, 14_999L))
    }

    @Test fun staleFrameTripsAtTimeout() {
        assertTrue(CameraSafetyPolicy.shouldStopForStaleFrame(true, 10_000L, 15_000L))
    }

    @Test fun clockRollbackDoesNotTrip() {
        assertFalse(CameraSafetyPolicy.shouldStopForStaleFrame(true, 10_000L, 9_000L))
    }
}
