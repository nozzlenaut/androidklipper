package dev.nozzlenaut.androidklipper

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
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

        if (uri.substringBefore('?') == "/sw.js") {
            // The Mainsail PWA service worker normally precaches the whole UI.
            // AndroidKlipper already ships every asset inside the APK, and
            // persistent browser caches can mix old/new asset bundles after an
            // APK update (for example, a blank CodeMirror config editor).
            // Keep a tiny pass-through service worker instead and delete any
            // caches left by older embedded Mainsail versions.
            val noCacheWorker = """
                self.addEventListener('install', event => {
                  self.skipWaiting();
                });
                self.addEventListener('activate', event => {
                  event.waitUntil(
                    caches.keys()
                      .then(keys => Promise.all(keys.map(key => caches.delete(key))))
                      .then(() => self.clients.claim())
                  );
                });
            """.trimIndent()
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/javascript; charset=utf-8",
                noCacheWorker
            ).apply {
                addHeader("Cache-Control", "no-store, max-age=0")
                addHeader("Service-Worker-Allowed", "/")
            }
        }

        if (uri.substringBefore('?') == "/config.json") {
            val requestHost = session.headers["host"]
                ?.substringBefore(':')
                ?.takeIf { it.isNotBlank() }
            val hostname = requestHost ?: DeviceIdentity.activeLanIpv4() ?: "127.0.0.1"
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

}
