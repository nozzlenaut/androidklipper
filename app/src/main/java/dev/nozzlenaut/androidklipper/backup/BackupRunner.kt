package dev.nozzlenaut.androidklipper.backup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import dev.nozzlenaut.androidklipper.HostDiagnostics
import dev.nozzlenaut.androidklipper.KlipperHostService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android glue around [PrinterDataBackup]: where backups are written and when
 * restoring is allowed. Results are logged to HostDiagnostics.
 *
 * Copies go to:
 *  - <external files>/backups (adb pull / adb push, removed on uninstall), and
 *  - Download/AndroidKlipper through MediaStore on Android 10+ (survives uninstall).
 */
object BackupRunner {
    private const val DRIVE_NAME = "AndroidKlipperDrive"

    fun backupDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

    fun backup(context: Context, includeGcodes: Boolean = false): String {
        val drive = File(context.filesDir, DRIVE_NAME)
        val hostRunning = KlipperHostService.isMoonrakerReady(context) || KlipperHostService.isKlippyReady(context)
        // The Moonraker database is LMDB: copying it while Moonraker writes can capture a
        // torn state, so a running host only exports the config (and optionally gcodes).
        val include = buildList {
            add("config")
            if (!hostRunning) add("database")
            if (includeGcodes) add("gcodes")
        }
        val name = "androidklipper-backup-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val temp = File(context.cacheDir, name)
        val result = try {
            temp.outputStream().use { PrinterDataBackup.export(drive, it, include) }
        } catch (t: Throwable) {
            temp.delete()
            return log(context, "backup FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
        val where = mutableListOf<String>()
        runCatching {
            val dest = File(backupDir(context), name)
            temp.copyTo(dest, overwrite = true)
            where += dest.absolutePath
        }.onFailure { where += "app storage copy failed: ${it.message}" }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/AndroidKlipper")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                context.contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ?: error("cannot open MediaStore stream")
                where += "Download/AndroidKlipper/$name"
            }.onFailure { where += "Downloads copy failed: ${it.message}" }
        }
        temp.delete()
        val skippedNote = if (result.skipped.isEmpty()) "" else " skipped=${result.skipped}"
        val partial = if (hostRunning) " (host running: database NOT included)" else ""
        return log(
            context,
            "backup OK ${result.files} files, ${result.bytes} bytes, folders=${result.included}$partial$skippedNote -> ${where.joinToString("; ")}"
        )
    }

    /** Restores backups/<name> from the app's external files dir. The host must be stopped. */
    fun restore(context: Context, name: String): String {
        if (KlipperHostService.isMoonrakerReady(context) || KlipperHostService.isKlippyReady(context)) {
            return log(context, "restore REFUSED: stop the host first (DEBUG_STOP), then restore")
        }
        val safeName = File(name).name
        val source = File(backupDir(context), safeName)
        if (!source.isFile) return log(context, "restore FAILED: ${source.absolutePath} not found")
        return try {
            val drive = File(context.filesDir, DRIVE_NAME)
            val result = source.inputStream().use { PrinterDataBackup.restore(it, drive) }
            log(
                context,
                "restore OK folders=${result.restoredFolders} files=${result.files}" +
                    (result.previousKeptAt?.let { " previous data kept at ${it.absolutePath}" } ?: "")
            )
        } catch (t: Throwable) {
            log(context, "restore FAILED (live data unchanged or rolled back): ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(context: Context, message: String): String {
        HostDiagnostics.log(context, message)
        return message
    }
}
