package dev.nozzlenaut.androidklipper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_REAL -> {
                KlipperHostService.start(
                    context,
                    fullSmoke = false,
                    realConfig = true
                )
            }

            ACTION_STOP -> {
                KlipperHostService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_START_REAL =
            "dev.nozzlenaut.androidklipper.DEBUG_START_REAL"
        const val ACTION_STOP =
            "dev.nozzlenaut.androidklipper.DEBUG_STOP"
    }
}
