package dev.nozzlenaut.androidklipper.usb

/**
 * What to do when the service is asked to (re)start while a host may already be
 * running. Pure function so the rules are testable.
 *
 * Hard rule from the project: a duplicate USB attach / permission callback must
 * never tear down a live print. Rebuilding is only allowed when the existing
 * host provably cannot be printing: a USB session already died, or Klippy is
 * not ready and a supported device has appeared that has no session yet.
 */
object UsbRecoveryPolicy {
    enum class Decision {
        /** No host is running: start one. */
        START_HOST,

        /** A host is starting or healthy: leave it alone. */
        IGNORE,

        /** A bridged USB device died (unplugged, errors); it is back or may be: rebuild. */
        REBUILD_LOST_DEVICE,

        /** Klippy is not ready and a supported device without a session showed up: rebuild. */
        REBUILD_NEW_DEVICE
    }

    fun decide(
        hostActive: Boolean,
        rebuildInProgress: Boolean,
        klippyReady: Boolean,
        anySessionFailed: Boolean,
        grantedSupportedDevices: Int,
        sessionCount: Int
    ): Decision = when {
        !hostActive -> Decision.START_HOST
        rebuildInProgress -> Decision.IGNORE
        anySessionFailed -> Decision.REBUILD_LOST_DEVICE
        !klippyReady && grantedSupportedDevices > sessionCount -> Decision.REBUILD_NEW_DEVICE
        else -> Decision.IGNORE
    }
}
