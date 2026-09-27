package dev.nozzlenaut.androidklipper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder

class MainsailServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val rawUri = session.uri ?: "/"
        val uri = runCatching { URLDecoder.decode(rawUri, "UTF-8") }.getOrDefault(rawUri)

        if (uri.substringBefore('?') == "/androidklipper/battery") {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val present = batteryIntent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
            val rawLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val level = if (present && rawLevel >= 0 && scale > 0) {
                ((rawLevel * 100f) / scale).toInt().coerceIn(0, 100)
            } else {
                null
            }
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val json = """
                {
                  "present": $present,
                  "level": ${level?.toString() ?: "null"},
                  "charging": $charging
                }
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
        if (uri.substringBefore('?') == "/config.json") {
            val requestHost = session.headers["host"]
                ?.substringBefore(':')
                ?.takeIf { it.isNotBlank() }
            val hostname = requestHost ?: activeLanIpv4() ?: "127.0.0.1"
            val json = """
                {
                  "defaultLocale": "en",
                  "defaultMode": "dark",
                  "defaultTheme": "mainsail",
                  "hostname": "$hostname",
                  "port": 7125,
                  "path": null,
                  "instancesDB": "moonraker",
                  "instances": []
                }
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                addHeader("Cache-Control", "no-store")
            }
        }

        val clean = uri.substringBefore('?')
            .removePrefix("/")
            .ifBlank { "index.html" }
            .replace('\\', '/')

        if (clean.split('/').any { it == ".." }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }

        return serveAsset(clean)
            ?: if (!clean.substringAfterLast('/').contains('.')) {
                serveAsset("index.html")
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun serveAsset(path: String): Response? {
        val stream = try {
            context.assets.open("mainsail/$path")
        } catch (_: IOException) {
            return null
        }

        return newChunkedResponse(Response.Status.OK, mimeType(path), stream).apply {
            if (path == "index.html" || path == "sw.js") {
                addHeader("Cache-Control", "no-cache")
            } else {
                addHeader("Cache-Control", "public, max-age=3600")
            }
        }
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "map" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun activeLanIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { address ->
                    address.startsWith("10.") ||
                        address.startsWith("192.168.") ||
                        address.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
                }
        }.getOrNull()
    }
}
