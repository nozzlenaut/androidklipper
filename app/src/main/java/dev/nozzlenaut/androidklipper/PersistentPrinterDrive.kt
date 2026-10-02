package dev.nozzlenaut.androidklipper

import android.content.Context
import android.os.Environment
import android.system.Os
import java.io.File
import java.nio.file.Files

object PersistentPrinterDrive {
    private const val DRIVE_NAME = "AndroidKlipperDrive"
    private const val DRIVE_VERSION = "1"
    private val persistentDirs = listOf("config", "gcodes", "database", "backups")
    private val transientDirs = listOf("logs", "comms")

    data class Result(
        val summary: String,
        val driveRoot: File?,
        val linked: List<String>,
        val warnings: List<String>
    )

    fun prepare(context: Context): Result {
        val dataRoot = File(context.filesDir, "printer_data")
        dataRoot.mkdirs()
        transientDirs.forEach { File(dataRoot, it).mkdirs() }

        val driveBase = selectDriveBase(context)
        if (driveBase == null) {
            persistentDirs.forEach { File(dataRoot, it).mkdirs() }
            return Result(
                "AndroidKlipperDrive WARNING: external app storage unavailable; using internal printer_data",
                null,
                emptyList(),
                listOf("persistent drive unavailable")
            )
        }

        val driveRoot = File(driveBase, DRIVE_NAME)
        if (!driveRoot.exists() && !driveRoot.mkdirs()) {
            persistentDirs.forEach { File(dataRoot, it).mkdirs() }
            return Result(
                "AndroidKlipperDrive WARNING: unable to create ${driveRoot.absolutePath}; using internal printer_data",
                null,
                emptyList(),
                listOf("unable to create drive root")
            )
        }

        seedDriveMetadata(driveRoot)
        val linked = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        persistentDirs.forEach { name ->
            val warning = linkPersistentDirectory(dataRoot, driveRoot, name)
            if (warning == null) linked += name else warnings += "$name: $warning"
        }

        val summary = buildString {
            append("AndroidKlipperDrive: ")
            append(driveRoot.absolutePath)
            append(" persistent=")
            append(linked.joinToString(","))
            if (warnings.isNotEmpty()) {
                append(" warnings=")
                append(warnings.joinToString(" | "))
            }
        }
        return Result(summary, driveRoot, linked, warnings)
    }

    private fun selectDriveBase(context: Context): File? {
        val externalRoots = context.getExternalFilesDirs(null).filterNotNull()
        val removable = externalRoots.firstOrNull { dir ->
            Environment.isExternalStorageRemovable(dir) &&
                Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
        }
        val primary = context.getExternalFilesDir(null)?.takeIf { dir ->
            Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
        }
        return removable ?: primary
    }

    private fun seedDriveMetadata(driveRoot: File) {
        persistentDirs.forEach { File(driveRoot, it).mkdirs() }
        val marker = File(driveRoot, ".androidklipper-drive")
        if (!marker.exists()) {
            runCatching { marker.writeText("version=$DRIVE_VERSION\n") }
        }
        val readme = File(driveRoot, "README.txt")
        if (!readme.exists()) {
            runCatching {
                readme.writeText(
                    "AndroidKlipper persistent printer data.\n" +
                        "config, gcodes, database, and backups survive normal app updates " +
                        "and embedded runtime rebuilds.\n" +
                        "Do not remove this folder while AndroidKlipper is using it.\n"
                )
            }
        }
    }

    private fun linkPersistentDirectory(
        dataRoot: File,
        driveRoot: File,
        name: String
    ): String? {
        val runtime = File(dataRoot, name)
        val persistent = File(driveRoot, name)
        if (!persistent.exists() && !persistent.mkdirs()) {
            runtime.mkdirs()
            return "unable to create persistent directory"
        }

        val runtimePath = runtime.toPath()
        if (Files.isSymbolicLink(runtimePath)) {
            val oldTarget = runCatching { Files.readSymbolicLink(runtimePath).toString() }.getOrNull()
            if (oldTarget == persistent.absolutePath) return null

            val resolvedOldTarget = oldTarget?.let { target ->
                val targetFile = File(target)
                if (targetFile.isAbsolute) targetFile else File(runtime.parentFile, target)
            }
            val migrationError = resolvedOldTarget
                ?.takeIf { it.exists() }
                ?.let { migrateMissing(it, persistent) }
            if (migrationError != null) return "old link migration failed: $migrationError"
            runCatching { Files.delete(runtimePath) }
                .getOrElse { return "unable to remove old link: ${it.message}" }
        } else if (runtime.exists()) {
            val migrationError = migrateMissing(runtime, persistent)
            if (migrationError != null) return "migration failed: $migrationError"
            if (!runtime.deleteRecursively()) {
                return "migration copied data but could not remove old runtime directory"
            }
        }

        return try {
            runtime.parentFile?.mkdirs()
            Os.symlink(persistent.absolutePath, runtime.absolutePath)
            val probe = File(runtime, ".androidklipper-storage-probe")
            probe.writeText("ok")
            probe.delete()
            null
        } catch (t: Throwable) {
            runCatching {
                if (Files.isSymbolicLink(runtime.toPath())) Files.delete(runtime.toPath())
            }
            runtime.mkdirs()
            "link failed (${t.javaClass.simpleName}: ${t.message})"
        }
    }

    private fun migrateMissing(source: File, destination: File): String? {
        return try {
            copyMissing(source, destination)
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun copyMissing(source: File, destination: File) {
        if (destination.exists()) {
            if (source.isDirectory && destination.isDirectory) {
                source.listFiles().orEmpty().forEach { child ->
                    copyMissing(child, File(destination, child.name))
                }
            }
            // Existing persistent data wins over the stale runtime copy.
            return
        }

        if (source.isDirectory) {
            if (!destination.mkdirs() && !destination.isDirectory) {
                error("unable to create ${destination.absolutePath}")
            }
            source.listFiles().orEmpty().forEach { child ->
                copyMissing(child, File(destination, child.name))
            }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = false)
        }
    }
}
