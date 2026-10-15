package dev.nozzlenaut.androidklipper.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / restore of the AndroidKlipperDrive directories as one zip.
 *
 * Uninstalling the app (which a signing-key change forces) deletes the drive,
 * so a copy has to leave app-private storage. This class only deals with files:
 * it knows nothing about Android, which keeps it unit-testable.
 *
 * Restore never trusts the archive: entry names are validated (no absolute
 * paths, no "..", only known top-level folders), everything is extracted to a
 * staging directory first, and live folders are swapped in only after the whole
 * archive validated. Replaced folders are kept under backups/pre-restore-*, and a
 * failure part-way through rolls the swap back.
 *
 * The caller must make sure Klippy and Moonraker are stopped before restoring,
 * and should not export the database while Moonraker is running (LMDB files can
 * be copied in an inconsistent state).
 */
object PrinterDataBackup {
    const val MANIFEST_NAME = "androidklipper-backup.json"
    const val FORMAT_VERSION = 1

    /** Folders under the drive root that may appear in a backup. */
    val ALLOWED_TOP_LEVEL = setOf("config", "database", "gcodes")
    val DEFAULT_INCLUDE = listOf("config", "database")

    const val DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 50_000

    data class ExportResult(val files: Int, val bytes: Long, val included: List<String>, val skipped: List<String>)
    data class RestoreResult(val restoredFolders: List<String>, val files: Int, val previousKeptAt: File?)

    class InvalidBackupException(message: String) : IOException(message)

    fun export(
        driveRoot: File,
        out: OutputStream,
        include: List<String> = DEFAULT_INCLUDE,
        nowMs: Long = System.currentTimeMillis()
    ): ExportResult {
        require(include.all { it in ALLOWED_TOP_LEVEL }) { "unknown folder in $include" }
        val skipped = mutableListOf<String>()
        var files = 0
        var bytes = 0L
        val present = include.filter { File(driveRoot, it).isDirectory }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(
                ("{\"format\":$FORMAT_VERSION,\"createdAtMs\":$nowMs," +
                    "\"folders\":[${present.joinToString(",") { "\"$it\"" }}]}").toByteArray()
            )
            zip.closeEntry()
            for (top in present) {
                val root = File(driveRoot, top)
                root.walkTopDown().onEnter { dir ->
                    if (Files.isSymbolicLink(dir.toPath())) { skipped += relative(driveRoot, dir); false } else true
                }.forEach { f ->
                    if (f.isDirectory) return@forEach
                    val rel = relative(driveRoot, f)
                    if (Files.isSymbolicLink(f.toPath()) || !f.isFile) {
                        skipped += rel
                        return@forEach
                    }
                    zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files++
                    bytes += f.length()
                }
            }
        }
        return ExportResult(files, bytes, present, skipped)
    }

    fun restore(
        input: InputStream,
        driveRoot: File,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        nowMs: Long = System.currentTimeMillis(),
        /** Test hook: called before each folder is swapped in. */
        beforeSwap: (String) -> Unit = {}
    ): RestoreResult {
        val stage = File(driveRoot, ".restore-$nowMs")
        stage.deleteRecursively()
        if (!stage.mkdirs()) throw IOException("cannot create staging directory $stage")
        try {
            val (tops, files) = extractValidated(input, stage, maxTotalBytes)
            return swapIn(driveRoot, stage, tops, files, nowMs, beforeSwap)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun extractValidated(input: InputStream, stage: File, maxTotalBytes: Long): Pair<List<String>, Int> {
        val stageCanonical = stage.canonicalFile
        val tops = linkedSetOf<String>()
        var sawManifest = false
        var entries = 0
        var total = 0L
        var files = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw InvalidBackupException("too many entries")
                if (entry.name == MANIFEST_NAME) {
                    sawManifest = true
                    val text = readBounded(zip, 64 * 1024).toString(Charsets.UTF_8)
                    if (!text.contains("\"format\":$FORMAT_VERSION")) {
                        throw InvalidBackupException("unsupported backup format")
                    }
                    continue
                }
                val rel = safeRelative(entry.name)
                    ?: throw InvalidBackupException("unsafe entry name: ${entry.name.take(80)}")
                if (entry.isDirectory) continue
                val top = rel.substringBefore('/')
                if (top !in ALLOWED_TOP_LEVEL || !rel.contains('/')) {
                    throw InvalidBackupException("unexpected entry: $rel")
                }
                val target = File(stage, rel).canonicalFile
                if (!target.path.startsWith(stageCanonical.path + File.separator)) {
                    throw InvalidBackupException("entry escapes staging directory: $rel")
                }
                target.parentFile.mkdirs()
                target.outputStream().use { outStream ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxTotalBytes) throw InvalidBackupException("backup exceeds size limit")
                        outStream.write(buf, 0, n)
                    }
                }
                tops += top
                files++
            }
        }
        if (!sawManifest) throw InvalidBackupException("not an AndroidKlipper backup (no manifest)")
        if (tops.isEmpty()) throw InvalidBackupException("backup contains no files")
        return tops.toList() to files
    }

    private fun swapIn(
        driveRoot: File,
        stage: File,
        tops: List<String>,
        files: Int,
        nowMs: Long,
        beforeSwap: (String) -> Unit
    ): RestoreResult {
        val keepRoot = File(driveRoot, "backups/pre-restore-$nowMs")
        val moved = mutableListOf<Pair<File, File>>() // live -> kept copy
        val installed = mutableListOf<File>()
        try {
            for (top in tops) {
                beforeSwap(top)
                val live = File(driveRoot, top)
                if (live.exists()) {
                    val kept = File(keepRoot, top)
                    kept.parentFile.mkdirs()
                    move(live, kept)
                    moved += live to kept
                }
                move(File(stage, top), live)
                installed += live
            }
        } catch (t: Throwable) {
            installed.asReversed().forEach { runCatching { it.deleteRecursively() } }
            moved.asReversed().forEach { (live, kept) ->
                runCatching { move(kept, live) }
            }
            runCatching { keepRoot.delete() }
            throw if (t is IOException) t else IOException("restore failed and was rolled back: ${t.message}", t)
        }
        return RestoreResult(tops, files, if (moved.isEmpty()) null else keepRoot)
    }

    // Same-filesystem rename (the staging dir lives inside the drive), so it is
    // atomic per folder. File.renameTo avoids java.nio on minSdk 24.
    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("cannot move $from to $to")
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Normalised relative path using '/', or null when the name could escape or is malformed. */
    fun safeRelative(name: String): String? {
        if (name.isEmpty() || name.length > 1024) return null
        if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return null
        if (name.length >= 2 && name[1] == ':') return null // C:\ style
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return null
        return parts.joinToString("/")
    }

    private fun relative(root: File, f: File) = f.relativeTo(root).path.replace(File.separatorChar, '/')
}
