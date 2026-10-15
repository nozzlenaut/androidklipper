package dev.nozzlenaut.androidklipper.usb

/**
 * Tells an idle USB endpoint apart from a dead one, using only call results and
 * timings (no Android types, so it is unit-testable).
 *
 * UsbDeviceConnection.bulkTransfer() returns -1 both for "nothing arrived
 * within the timeout" and for real failures. The two differ in *how long the
 * call took*: an idle read waits out (most of) the timeout, a failing read on
 * a detached or halted endpoint returns immediately. A long unbroken run of
 * immediate -1 results with no data in between means the device is gone.
 *
 * This is a backstop behind ACTION_USB_DEVICE_DETACHED. It is deliberately slow
 * to trigger: tearing down a healthy session during a print is the worst outcome.
 */
class UsbReadHealth(
    private val timeoutMs: Int,
    private val deadAfterMs: Long = DEFAULT_DEAD_AFTER_MS
) {
    enum class Verdict { OK, IDLE, SUSPECT, DEAD }

    private var fastFailureSince = NONE

    /**
     * @param result bulkTransfer() return value
     * @param elapsedMs how long the call took
     * @param nowMs monotonic clock reading after the call
     */
    fun onTransfer(result: Int, elapsedMs: Long, nowMs: Long): Verdict {
        if (result >= 0) {
            fastFailureSince = NONE
            return Verdict.OK
        }
        if (elapsedMs >= timeoutMs / 2) {
            // Waited roughly the full timeout: ordinary idle line.
            fastFailureSince = NONE
            return Verdict.IDLE
        }
        if (fastFailureSince == NONE) fastFailureSince = nowMs
        return if (nowMs - fastFailureSince >= deadAfterMs) Verdict.DEAD else Verdict.SUSPECT
    }

    companion object {
        const val DEFAULT_DEAD_AFTER_MS = 5_000L
        private const val NONE = Long.MIN_VALUE
    }
}
