package dev.nozzlenaut.androidklipper.config

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ConfigMigration {
    data class Result(val source: String, val fileCount: Int)

    fun importFromMoonraker(context: Context, candidates: List<String>): Result {
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                return importFrom(context, candidate.trimEnd('/'))
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "Could not reach the existing Moonraker host: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }

    private fun importFrom(context: Context, base: String): Result {
        val listJson = readText("$base/server/files/list?root=config")
        val array = JSONObject(listJson).getJSONArray("result")
        val paths = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val path = array.getJSONObject(i).getString("path")
            if (path.endsWith(".cfg", ignoreCase = true)) paths += path
        }
        require(paths.any { it == "printer.cfg" }) { "Moonraker config root has no printer.cfg" }

        val dataDir = File(context.filesDir, "printer_data")
        val originalDir = File(dataDir, "config-original")
        val runtimeDir = File(dataDir, "config")
        originalDir.deleteRecursively()
        runtimeDir.deleteRecursively()
        originalDir.mkdirs()
        runtimeDir.mkdirs()
        File(dataDir, "gcodes").mkdirs()
        File(dataDir, "logs").mkdirs()
        File(dataDir, "comms").mkdirs()
        File(dataDir, "run").mkdirs()

        paths.forEach { relative ->
            val encoded = relative.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val bytes = readBytes("$base/server/files/config/$encoded")
            safeFile(originalDir, relative).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            safeFile(runtimeDir, relative).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
        }

        File(dataDir, "migration-source.txt").writeText(base)
        return Result(base, paths.size)
    }

    private fun safeFile(root: File, relative: String): File {
        require(!relative.startsWith("/") && !relative.contains("..")) {
            "Unsafe config path: $relative"
        }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(rootPath)) {
            "Config path escaped import directory: $relative"
        }
        return file
    }

    private fun readText(url: String): String = readBytes(url).toString(Charsets.UTF_8)

    private fun readBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3500
        connection.readTimeout = 7000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
