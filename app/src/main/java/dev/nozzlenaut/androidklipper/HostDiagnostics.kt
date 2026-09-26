package dev.nozzlenaut.androidklipper

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent flight recorder for the Android host process.
 *
 * Moonraker/Klippy logs tell us what the printer saw. This log tells us what
 * Android did to the service around the same time, and it survives reopening
 * the app after a crash or process kill.
 */
object HostDiagnostics {
    private const val LOG_NAME = "androidklipper-host.log"
    private const val MAX_LOG_BYTES = 1_500_000L
    private const val PREFS = "host_diagnostics"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private val writeLock = Any()

    fun log(context: Context, message: String) {
        runCatching {
            synchronized(writeLock) {
                val log = logFile(context)
                rotateIfNeeded(log)
                val stamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US
                ).format(Date())
                val elapsed = SystemClock.elapsedRealtime()
                val thread = Thread.currentThread().name
                log.appendText(
                    "$stamp elapsed=${elapsed}ms pid=${Process.myPid()} " +
                        "thread=$thread | $message\n"
                )
            }
        }
    }

    fun recordPreviousProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
            val exits = manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                8
            ).filter { it.timestamp > lastSeen }

            exits.sortedBy { it.timestamp }.forEach { info ->
                log(
                    context,
                    "previous process exit: timestamp=${info.timestamp} " +
                        "reason=${info.reason} status=${info.status} " +
                        "importance=${info.importance} pss=${info.pss} rss=${info.rss} " +
                        "process=${info.processName} description=${info.description ?: "none"}"
                )
            }

            exits.maxOfOrNull { it.timestamp }?.let { newest ->
                prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, newest).apply()
            }
        }.onFailure {
            log(context, "unable to read previous process exits: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun logFile(context: Context): File {
        val dir = File(context.filesDir, "printer_data/logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_NAME)
    }

    private fun rotateIfNeeded(log: File) {
        if (!log.exists() || log.length() < MAX_LOG_BYTES) return
        val old = File(log.parentFile, "$LOG_NAME.1")
        runCatching { if (old.exists()) old.delete() }
        runCatching { log.renameTo(old) }
    }
}
