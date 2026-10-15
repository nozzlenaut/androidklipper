package dev.nozzlenaut.androidklipper.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PrinterDataBackupTest {
    private lateinit var tmp: File
    private lateinit var drive: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("akbackup").toFile()
        drive = File(tmp, "drive").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun write(rel: String, text: String) {
        File(drive, rel).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun read(rel: String) = File(drive, rel).readText()

    private fun exportBytes(include: List<String> = PrinterDataBackup.DEFAULT_INCLUDE): ByteArray {
        val out = ByteArrayOutputStream()
        PrinterDataBackup.export(drive, out, include)
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, String>, manifest: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (manifest) {
                z.putNextEntry(ZipEntry(PrinterDataBackup.MANIFEST_NAME))
                z.write("{\"format\":1,\"createdAtMs\":1,\"folders\":[]}".toByteArray())
                z.closeEntry()
            }
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertInvalid(bytes: ByteArray, what: String) {
        write("config/printer.cfg", "KEEP")
        try {
            PrinterDataBackup.restore(ByteArrayInputStream(bytes), drive)
            fail("expected InvalidBackupException for $what")
        } catch (e: PrinterDataBackup.InvalidBackupException) {
            // expected
        }
        assertEquals("existing data must be untouched for $what", "KEEP", read("config/printer.cfg"))
        assertFalse(File(drive, "backups").exists())
        assertEquals(listOf("config"), drive.list()!!.sorted())
    }

    @Test
    fun roundTripPreservesFilesAndBytes() {
        write("config/printer.cfg", "[mcu]\nserial: /dev/x\n")
        write("config/macros/home.cfg", "G28")
        write("database/data.mdb", "binary\u0000data")
        write("gcodes/big.gcode", "G1 X1")
        val bytes = exportBytes()

        File(drive, "config").deleteRecursively()
        File(drive, "database").deleteRecursively()
        val result = PrinterDataBackup.restore(ByteArrayInputStream(bytes), drive)

        assertEquals(listOf("config", "database"), result.restoredFolders.sorted())
        assertEquals(3, result.files)
        assertEquals("[mcu]\nserial: /dev/x\n", read("config/printer.cfg"))
        assertEquals("G28", read("config/macros/home.cfg"))
        assertEquals("binary\u0000data", read("database/data.mdb"))
        assertEquals("G1 X1", read("gcodes/big.gcode")) // untouched, not part of the backup
        assertNull(result.previousKeptAt)
        assertFalse(drive.list()!!.any { it.startsWith(".restore-") })
    }

    @Test
    fun gcodesAreOnlyExportedWhenAsked() {
        write("config/a.cfg", "a")
        write("gcodes/x.gcode", "x")
        val default = PrinterDataBackup.export(drive, ByteArrayOutputStream())
        assertEquals(listOf("config"), default.included)
        val withGcodes = PrinterDataBackup.export(drive, ByteArrayOutputStream(), listOf("config", "gcodes"))
        assertEquals(2, withGcodes.files)
    }

    @Test
    fun symlinksAreNotFollowedOnExport() {
        write("config/real.cfg", "real")
        val outside = File(tmp, "secret.txt").apply { writeText("TOP SECRET") }
        Files.createSymbolicLink(File(drive, "config/link.cfg").toPath(), outside.toPath())
        val result = PrinterDataBackup.export(drive, ByteArrayOutputStream())
        assertEquals(1, result.files)
        assertEquals(listOf("config/link.cfg"), result.skipped)
        assertFalse(String(exportBytes(), Charsets.ISO_8859_1).contains("TOP SECRET"))
    }

    @Test
    fun restoreKeepsThePreviousFoldersAndReplacesLiveOnes() {
        write("config/printer.cfg", "OLD")
        val newBackup = zip("config/printer.cfg" to "NEW", "config/extra.cfg" to "E")
        val result = PrinterDataBackup.restore(ByteArrayInputStream(newBackup), drive, nowMs = 42)
        assertEquals("NEW", read("config/printer.cfg"))
        assertEquals("E", read("config/extra.cfg"))
        val kept = result.previousKeptAt
        assertNotNull(kept)
        assertEquals("OLD", File(kept, "config/printer.cfg").readText())
        assertEquals("pre-restore-42", kept!!.name)
    }

    @Test
    fun zipSlipAndOtherUnsafeNamesAreRejectedWithoutTouchingLiveData() {
        assertInvalid(zip("config/../../evil.txt" to "x"), "dot-dot inside path")
        assertInvalid(zip("../evil.txt" to "x"), "leading dot-dot")
        assertInvalid(zip("/etc/passwd" to "x"), "absolute path")
        assertInvalid(zip("config\\..\\evil" to "x"), "backslashes")
        assertInvalid(zip("C:/windows/x" to "x"), "drive letter")
        assertInvalid(zip("config/./a.cfg" to "x"), "dot segment")
    }

    @Test
    fun unknownTopLevelFoldersAndLooseFilesAreRejected() {
        assertInvalid(zip("logs/moonraker.log" to "x"), "logs folder")
        assertInvalid(zip("comms/klippy.sock" to "x"), "comms folder")
        assertInvalid(zip("config" to "file named config"), "loose top-level file")
        assertInvalid(zip("backups/pre-restore-1/x" to "x"), "backups folder")
    }

    @Test
    fun archivesWithoutAManifestOrWithAnotherFormatAreRejected() {
        assertInvalid(zip("config/a.cfg" to "x", manifest = false), "no manifest")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(PrinterDataBackup.MANIFEST_NAME))
            z.write("{\"format\":99}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("config/a.cfg")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertInvalid(out.toByteArray(), "future format")
    }

    @Test
    fun emptyAndCorruptArchivesAreRejected() {
        assertInvalid(zip(), "manifest only")
        write("config/printer.cfg", "KEEP")
        try {
            PrinterDataBackup.restore(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), drive)
            fail("expected an exception for garbage input")
        } catch (e: java.io.IOException) {
            // expected: InvalidBackupException (no manifest) or ZipException
        }
        assertEquals("KEEP", read("config/printer.cfg"))
    }

    @Test
    fun oversizedArchivesAreRejectedPartWayAndLeaveLiveDataAlone() {
        write("config/printer.cfg", "KEEP")
        val big = "x".repeat(10_000)
        try {
            PrinterDataBackup.restore(
                ByteArrayInputStream(zip("config/a.cfg" to big, "config/b.cfg" to big)), drive, maxTotalBytes = 15_000
            )
            fail("expected size limit")
        } catch (e: PrinterDataBackup.InvalidBackupException) {
            assertTrue(e.message!!.contains("size"))
        }
        assertEquals("KEEP", read("config/printer.cfg"))
        assertFalse(File(drive, "config/a.cfg").exists())
    }

    @Test
    fun aFailureWhileSwappingRollsEverythingBack() {
        write("config/printer.cfg", "OLD-CONFIG")
        write("database/data.mdb", "OLD-DB")
        val backup = zip("config/printer.cfg" to "NEW-CONFIG", "database/data.mdb" to "NEW-DB")
        try {
            PrinterDataBackup.restore(ByteArrayInputStream(backup), drive, beforeSwap = { top ->
                if (top == "database") throw java.io.IOException("simulated disk failure")
            })
            fail("expected failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("simulated"))
        }
        assertEquals("OLD-CONFIG", read("config/printer.cfg"))
        assertEquals("OLD-DB", read("database/data.mdb"))
        assertFalse(drive.list()!!.any { it.startsWith(".restore-") })
    }

    @Test
    fun safeRelativeNormalisesAndRejects() {
        assertEquals("config/a.cfg", PrinterDataBackup.safeRelative("config//a.cfg"))
        assertEquals("config/a.cfg", PrinterDataBackup.safeRelative("config/a.cfg/"))
        for (bad in listOf("", "/", "..", "a/../b", "a/./b", "/abs", "a\\b", "a\u0000b", "c:/x", "a".repeat(2000))) {
            assertNull("accepted: $bad", PrinterDataBackup.safeRelative(bad))
        }
    }

    @Test
    fun exportingAnEmptyDriveStillProducesARestorableManifestOnlyArchive() {
        val result = PrinterDataBackup.export(drive, ByteArrayOutputStream())
        assertEquals(0, result.files)
        assertTrue(result.included.isEmpty())
    }
}
