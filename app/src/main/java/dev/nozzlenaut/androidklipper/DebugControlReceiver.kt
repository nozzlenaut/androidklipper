package dev.nozzlenaut.androidklipper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.nozzlenaut.androidklipper.backup.BackupRunner

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

            // adb shell am broadcast -n <pkg>/.DebugControlReceiver -a ...DEBUG_BACKUP [--ez gcodes true]
            ACTION_BACKUP -> {
                val pending = goAsync()
                val gcodes = intent.getBooleanExtra("gcodes", false)
                Thread({
                    try { BackupRunner.backup(context.applicationContext, gcodes) } finally { pending.finish() }
                }, "androidklipper-backup").start()
            }

            // adb shell am broadcast -n <pkg>/.DebugControlReceiver -a ...DEBUG_RESTORE --es name <file.zip>
            ACTION_RESTORE -> {
                val name = intent.getStringExtra("name")
                val pending = goAsync()
                Thread({
                    try {
                        if (name.isNullOrBlank()) {
                            HostDiagnostics.log(context, "restore FAILED: missing --es name <file.zip>")
                        } else {
                            BackupRunner.restore(context.applicationContext, name)
                        }
                    } finally {
                        pending.finish()
                    }
                }, "androidklipper-restore").start()
            }
        }
    }

    companion object {
        const val ACTION_START_REAL =
            "dev.nozzlenaut.androidklipper.DEBUG_START_REAL"
        const val ACTION_STOP =
            "dev.nozzlenaut.androidklipper.DEBUG_STOP"
        const val ACTION_BACKUP =
            "dev.nozzlenaut.androidklipper.DEBUG_BACKUP"
        const val ACTION_RESTORE =
            "dev.nozzlenaut.androidklipper.DEBUG_RESTORE"
    }
}
