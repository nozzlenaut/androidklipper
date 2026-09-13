package dev.nozzlenaut.androidklipper.config

import android.content.Context
import java.io.File

object ConfigRuntimePreparer {
    data class Prepared(
        val configFile: File,
        val runtimeDir: File,
        val changes: List<String>,
        val mappedUsbIds: Set<String>
    )

    fun prepare(context: Context, usbSerialToPty: Map<String, String>): Prepared {
        val dataDir = File(context.filesDir, "printer_data")
        val originalDir = File(dataDir, "config-original")
        val runtimeDir = File(dataDir, "config")
        val gcodeDir = File(dataDir, "gcodes")
        require(File(originalDir, "printer.cfg").isFile) {
            "No imported printer.cfg. Import the existing Klipper config first."
        }

        runtimeDir.deleteRecursively()
        copyTree(originalDir, runtimeDir)
        gcodeDir.mkdirs()

        val changes = mutableListOf<String>()
        val mappedUsbIds = linkedSetOf<String>()
        runtimeDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("cfg", ignoreCase = true) }
            .forEach { file ->
                val source = file.readText()
                val refs = ConfigRewriter.extractUsbMcuRefs(source)
                val sectionMap = refs.mapNotNull { ref ->
                    val id = ref.usbSerialHint ?: return@mapNotNull null
                    val pty = usbSerialToPty[id] ?: return@mapNotNull null
                    changes += "${file.relativeTo(runtimeDir).path}: [${ref.section}] -> $pty (USB $id)"
                    mappedUsbIds += id
                    ref.section to pty
                }.toMap()

                var rewritten = ConfigRewriter.rewriteForRuntime(source, sectionMap)
                rewritten = ConfigRewriter.rewriteAndroidCompatibility(
                    rewritten,
                    runtimeDir.absolutePath,
                    gcodeDir.absolutePath
                )
                file.writeText(rewritten)
            }

        return Prepared(File(runtimeDir, "printer.cfg"), runtimeDir, changes, mappedUsbIds)
    }

    private fun copyTree(source: File, dest: File) {
        source.walkTopDown().forEach { item ->
            val relative = item.relativeTo(source)
            val target = File(dest, relative.path)
            if (item.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                item.copyTo(target, overwrite = true)
            }
        }
    }
}
