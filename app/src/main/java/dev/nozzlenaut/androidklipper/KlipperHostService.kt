package dev.nozzlenaut.androidklipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.system.Os
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import dev.nozzlenaut.androidklipper.usb.UsbDeviceScanner
import dev.nozzlenaut.androidklipper.usb.UsbSerialSession
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class KlipperHostService : Service() {
    private val sessions = CopyOnWriteArrayList<UsbSerialSession>()
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val hostExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-host").apply { isDaemon = true }
    }
    private val klippyExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-klippy").apply { isDaemon = true }
    }
    private val moonrakerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-moonraker").apply { isDaemon = true }
    }
    private val statusServer = LocalStatusServer { HostStatusStore.load(this) }
    private val mainsailServer by lazy { MainsailServer(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { statusServer.start() }
        runCatching { mainsailServer.startServer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fullSmoke = intent?.getBooleanExtra(EXTRA_FULL_SMOKE, false) ?: false
        val realConfig = intent?.getBooleanExtra(EXTRA_REAL_CONFIG, false) ?: false
        val startText = when {
            realConfig -> "Starting persistent Klipper + Moonraker…"
            fullSmoke -> "Starting full Klippy smoke test…"
            else -> "Starting USB host test…"
        }
        startForeground(NOTIFICATION_ID, notification(startText))
        // USB permission callbacks can arrive close together for multi-MCU printers.
        // Serialize rebuilds so two service starts never fight over the same device.
        hostExecutor.execute { rebuildSessions(fullSmoke, realConfig) }
        // This is still a diagnostic host. Never let Android silently recreate
        // it later with a null Intent and an ambiguous/default test mode.
        return START_NOT_STICKY
    }

    private fun rebuildSessions(fullSmoke: Boolean, realConfig: Boolean) {
        if (Python.isStarted()) {
            val python = Python.getInstance()
            runCatching { python.getModule("moonraker_runner").callAttr("stop") }
            runCatching { python.getModule("persistent_host").callAttr("stop") }
        }

        // Always tear down old USB sessions before trying to reopen them. Android
        // USB stacks can return an apparently valid connection immediately after
        // close while the kernel is still releasing the claimed CDC interfaces.
        // A short post-close settle proved more reliable than sleeping before close.
        val hadSessions = sessions.isNotEmpty()
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        if (hadSessions) Thread.sleep(USB_REOPEN_SETTLE_MS)

        val storageSummary = prepareGcodeStorage()

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val hostprobe = Python.getInstance().getModule("hostprobe")
        val helper = File(applicationInfo.nativeLibraryDir, "libklipper_c_helper.so")
        val statusLines = mutableListOf<String>()
        statusLines += storageSummary
        val smokePtyPaths = mutableListOf<String>()
        val stablePtyMap = linkedMapOf<String, String>()

        try {
            statusLines += hostprobe.callAttr("probe_klipper_import").toString()
            statusLines += hostprobe.callAttr("probe_c_helper", helper.absolutePath).toString()
        } catch (t: Throwable) {
            statusLines += "Host runtime ERROR ${t.javaClass.simpleName}: ${t.message}"
        }

        val supported = usbManager.deviceList.values.mapNotNull { device ->
            val driver = UsbDeviceScanner.probe(device) ?: return@mapNotNull null
            if (!usbManager.hasPermission(device)) {
                statusLines += "${device.productName ?: device.deviceName}: waiting for USB permission"
                return@mapNotNull null
            }
            device to driver
        }

        supported.forEachIndexed { index, (device, driver) ->
            try {
                val connection = usbManager.openDevice(device)
                    ?: error("UsbManager.openDevice returned null")
                // Fire OS can intermittently stop exposing one MCU serial after
                // the CDC device has been reopened. Cache every successful stable ID
                // by the current USB device path so subsequent opens in the same USB
                // enumeration keep the real Klipper serial instead of degrading to a
                // VID:PID:path fallback.
                val stableIdPrefs = getSharedPreferences("usb_stable_ids", Context.MODE_PRIVATE)
                val cacheKey = device.deviceName
                val serial = runCatching { device.serialNumber }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { connection.serial }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                val stableId = if (serial != null) {
                    stableIdPrefs.edit().putString(cacheKey, serial).apply()
                    serial
                } else {
                    stableIdPrefs.getString(cacheKey, null)
                        ?.takeIf { it.isNotBlank() }
                        ?: "%04x:%04x:%s".format(device.vendorId, device.productId, device.deviceName)
                }
                val pty = PtyBridge.create()
                val session = UsbSerialSession(driver, connection, stableId, pty) { error ->
                    publishStatus("USB bridge error for $stableId: ${error.message}")
                }
                session.start()
                sessions += session

                val pipeProbe = hostprobe.callAttr("probe_pipe_open", pty.slavePath).toString()
                val identifyProbe = if (UsbDeviceScanner.isLikelyKlipper(device)) {
                    hostprobe.callAttr(
                        "probe_mcu_identify",
                        pty.slavePath,
                        helper.absolutePath,
                        115200
                    ).toString().also {
                        // Only arm a PTY for full/real Klippy after the MCU has
                        // actually completed Klipper identify successfully.
                        smokePtyPaths += pty.slavePath
                        stablePtyMap[stableId] = pty.slavePath
                    }
                } else {
                    "Klipper identify skipped: unknown serial device"
                }

                statusLines += buildString {
                    append("MCU ${index + 1}: ${device.productName ?: driver.javaClass.simpleName}\n")
                    append("  id: $stableId\n")
                    append("  PTY: ${pty.slavePath}\n")
                    append("  Pipe: $pipeProbe\n")
                    append("  Protocol: $identifyProbe")
                }
            } catch (t: Throwable) {
                statusLines += "${device.productName ?: device.deviceName}: ERROR ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        val allLikelyKlipper = supported.isNotEmpty() &&
            supported.all { (device, _) -> UsbDeviceScanner.isLikelyKlipper(device) }
        val allIdentified = smokePtyPaths.size == supported.size && allLikelyKlipper

        if (realConfig) {
            if (!allIdentified || stablePtyMap.size != supported.size) {
                statusLines += "Persistent host SKIPPED: every supported USB device must identify as a Klipper MCU first."
            } else {
                val stableMapping = stablePtyMap.entries.joinToString("|") { (id, path) -> "$id=$path" }
                val klippySocket = File(filesDir, "printer_data/comms/klippy.sock")
                publishStatus(
                    "AndroidKlipper persistent host\n\n" +
                        statusLines.joinToString("\n\n") +
                        "\n\nImporting active config and starting persistent Klippy…"
                )

                klippyExecutor.execute {
                    try {
                        val persistent = Python.getInstance().getModule("persistent_host")
                        val result = persistent.callAttr(
                            "run",
                            CONFIG_SOURCE_URL,
                            stableMapping,
                            helper.absolutePath,
                            filesDir.absolutePath
                        ).toString()
                        publishStatus("Persistent Klippy stopped\n\n$result")
                    } catch (t: Throwable) {
                        publishStatus("Persistent Klippy ERROR ${t.javaClass.simpleName}: ${t.message}")
                    }
                }

                var klippySocketReady = false
                for (attempt in 0 until 300) {
                    if (klippySocket.exists()) {
                        klippySocketReady = true
                        break
                    }
                    Thread.sleep(100)
                }

                if (!klippySocketReady) {
                    statusLines += "Persistent Klippy ERROR: API socket did not appear."
                } else {
                    statusLines += "Persistent Klippy API: ${klippySocket.absolutePath}"
                    publishStatus(
                        "AndroidKlipper persistent host\n\n" +
                            statusLines.joinToString("\n\n") +
                            "\n\nStarting Moonraker on http://127.0.0.1:7125 …"
                    )

                    moonrakerExecutor.execute {
                        try {
                            val runner = Python.getInstance().getModule("moonraker_runner")
                            val result = runner.callAttr("run", filesDir.absolutePath).toString()
                            publishStatus("Moonraker stopped\n\n$result")
                        } catch (t: Throwable) {
                            publishStatus("Moonraker ERROR ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }

                    var moonrakerReady = false
                    for (attempt in 0 until 300) {
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress("127.0.0.1", 7125), 200)
                            }
                            moonrakerReady = true
                            break
                        } catch (_: Throwable) {
                            Thread.sleep(100)
                        }
                    }
                    statusLines += if (moonrakerReady) {
                        "Moonraker READY: http://127.0.0.1:7125"
                    } else {
                        val runnerStatus = runCatching {
                            Python.getInstance().getModule("moonraker_runner")
                                .callAttr("get_status").toString()
                        }.getOrElse { "status unavailable: ${it.message}" }
                        "Moonraker ERROR: port 7125 did not open.\nMoonraker runtime: $runnerStatus"
                    }
                }
            }
        } else if (fullSmoke) {
            if (!allIdentified) {
                statusLines += "Full Klippy smoke SKIPPED: every supported USB device must identify as a Klipper MCU first."
            } else {
                publishStatus(
                    "AndroidKlipper full smoke test\n\n" +
                        statusLines.joinToString("\n\n") +
                        "\n\nStarting real Klippy with kinematics=none and no configured pins…"
                )
                try {
                    statusLines += hostprobe.callAttr(
                        "probe_full_klippy",
                        smokePtyPaths.joinToString("|"),
                        helper.absolutePath,
                        filesDir.absolutePath
                    ).toString()
                } catch (t: Throwable) {
                    statusLines += "Full Klippy smoke ERROR ${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }

        val summary = if (statusLines.isEmpty()) {
            "No supported USB serial devices with permission."
        } else {
            val title = when {
                realConfig -> "AndroidKlipper persistent host"
                fullSmoke -> "AndroidKlipper full smoke test"
                else -> "AndroidKlipper host test"
            }
            title + "\n\n" + statusLines.joinToString("\n\n")
        }
        publishStatus(summary)
    }

    private fun prepareGcodeStorage(): String {
        val dataRoot = File(filesDir, "printer_data")
        dataRoot.mkdirs()
        val internalGcodes = File(dataRoot, "gcodes")

        val removableRoot = getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { dir ->
                Environment.isExternalStorageRemovable(dir) &&
                    Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            }

        if (removableRoot == null) {
            if (!internalGcodes.exists()) internalGcodes.mkdirs()
            return "G-code storage: internal (" + internalGcodes.absolutePath + ")"
        }

        val sdGcodes = File(removableRoot, "gcodes")
        if (!sdGcodes.exists() && !sdGcodes.mkdirs()) {
            if (!internalGcodes.exists()) internalGcodes.mkdirs()
            return "G-code storage WARNING: SD folder unavailable; using internal storage"
        }

        val linkPath = internalGcodes.toPath()
        if (Files.isSymbolicLink(linkPath)) {
            val target = runCatching { Files.readSymbolicLink(linkPath).toString() }.getOrNull()
            if (target == sdGcodes.absolutePath) {
                return "G-code storage: removable SD (" + sdGcodes.absolutePath + ")"
            }
            runCatching { Files.delete(linkPath) }
        } else if (internalGcodes.exists()) {
            val existing = internalGcodes.listFiles().orEmpty()
            if (existing.isNotEmpty()) {
                existing.forEach { source ->
                    val dest = File(sdGcodes, source.name)
                    runCatching {
                        if (source.isDirectory) source.copyRecursively(dest, overwrite = true)
                        else source.copyTo(dest, overwrite = true)
                    }
                }
            }
            runCatching { internalGcodes.deleteRecursively() }
        }

        return try {
            internalGcodes.parentFile?.mkdirs()
            Os.symlink(sdGcodes.absolutePath, internalGcodes.absolutePath)
            val probe = File(internalGcodes, ".androidklipper-storage-probe")
            probe.writeText("ok")
            probe.delete()
            "G-code storage: removable SD (" + sdGcodes.absolutePath + ")"
        } catch (t: Throwable) {
            runCatching { internalGcodes.delete() }
            internalGcodes.mkdirs()
            "G-code storage WARNING: SD link failed (" + t.javaClass.simpleName + ": " + t.message + "); using internal storage"
        }
    }

    private fun publishStatus(text: String) {
        HostStatusStore.save(this, text)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text.lineSequence().firstOrNull() ?: "AndroidKlipper"))
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, text)
        })
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AndroidKlipper")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_androidklipper)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Klipper host", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        if (Python.isStarted()) {
            val python = Python.getInstance()
            runCatching { python.getModule("moonraker_runner").callAttr("stop") }
            runCatching { python.getModule("persistent_host").callAttr("stop") }
        }
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        hostExecutor.shutdownNow()
        moonrakerExecutor.shutdownNow()
        klippyExecutor.shutdownNow()
        statusServer.close()
        runCatching { mainsailServer.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "dev.nozzlenaut.androidklipper.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FULL_SMOKE = "full_smoke"
        const val EXTRA_REAL_CONFIG = "real_config"
        const val PREF_AUTOMATION = "automation"
        const val KEY_AUTO_START_USB = "auto_start_usb"
        const val KEY_AUTO_START_IN_PROGRESS = "auto_start_in_progress"
        const val KEY_AUTO_KIOSK_PENDING = "auto_kiosk_pending"
        private const val CONFIG_SOURCE_URL = "http://192.168.1.83:7125"
        private const val CHANNEL_ID = "klipper_host"
        private const val NOTIFICATION_ID = 7714
        private const val USB_REOPEN_SETTLE_MS = 250L

        fun start(
            context: Context,
            fullSmoke: Boolean = false,
            realConfig: Boolean = false
        ) {
            val intent = Intent(context, KlipperHostService::class.java).apply {
                putExtra(EXTRA_FULL_SMOKE, fullSmoke)
                putExtra(EXTRA_REAL_CONFIG, realConfig)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
