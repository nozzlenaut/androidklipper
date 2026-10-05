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
import android.os.PowerManager
import android.os.SystemClock
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import dev.nozzlenaut.androidklipper.net.BridgeDiscovery
import dev.nozzlenaut.androidklipper.net.NetworkSerialSession
import dev.nozzlenaut.androidklipper.transport.McuSession
import dev.nozzlenaut.androidklipper.usb.UsbDeviceScanner
import dev.nozzlenaut.androidklipper.usb.UsbSerialSession
import dev.nozzlenaut.androidklipper.usb.UsbPermissionReceiver
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KlipperHostService : Service() {
    private val sessions = CopyOnWriteArrayList<McuSession>()
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val hostExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
            task.run()
        }, "androidklipper-host").apply { isDaemon = false }
    }
    private val klippyExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            // Klippy owns the hard timing deadlines. On a dedicated printer
            // host give its reactor the strongest app-level deadline-oriented
            // priority; USB bridge threads remain at FOREGROUND below it.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            task.run()
        }, "androidklipper-klippy").apply { isDaemon = false }
    }
    private val moonrakerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-moonraker").apply { isDaemon = false }
    }
    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "androidklipper-watchdog").apply { isDaemon = false }
    }
    private val persistentHostActive = AtomicBoolean(false)
    private val firmwareRestartRecoveryPending = AtomicBoolean(false)
    private val servicePrefs by lazy {
        getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
    }
    private val statusServer = LocalStatusServer { HostStatusStore.load(this) }
    private val mainsailServer by lazy { MainsailServer(this) }
    private var hostWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        HostDiagnostics.log(this, "service onCreate")
        HostDiagnostics.recordPreviousProcessExits(this)
        createNotificationChannel()
        runCatching { statusServer.start() }
        runCatching { mainsailServer.startServer() }
        watchdogExecutor.scheduleAtFixedRate(
            { logHeartbeat() },
            30,
            60,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fullSmoke = intent?.getBooleanExtra(EXTRA_FULL_SMOKE, false) ?: false
        val realConfig = intent?.getBooleanExtra(EXTRA_REAL_CONFIG, false)
            ?: servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)
        HostDiagnostics.log(
            this,
            "onStartCommand startId=$startId flags=$flags nullIntent=${intent == null} " +
                "fullSmoke=$fullSmoke realConfig=$realConfig"
        )

        val startText = when {
            realConfig -> "Starting persistent Klipper + Moonraker…"
            fullSmoke -> "Starting full Klippy smoke test…"
            else -> "Starting USB host test…"
        }
        startForeground(NOTIFICATION_ID, notification(startText))
        if (realConfig) acquireHostWakeLock()

        // A second USB attach/permission callback must never tear down a live print.
        // v249 rebuilt the whole host on every service start, and rebuildSessions()
        // deliberately stops Klippy/Moonraker before reopening USB.
        if (persistentHostActive.get()) {
            HostDiagnostics.log(
                this,
                "start ignored: persistent host is already starting/running"
            )
            return START_STICKY
        }
        if (realConfig) persistentHostActive.set(true)

        hostExecutor.execute {
            try {
                rebuildSessions(fullSmoke, realConfig)
            } catch (t: Throwable) {
                if (realConfig) persistentHostActive.set(false)
                HostDiagnostics.log(
                    this,
                    "rebuildSessions ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
                publishStatus("Host startup ERROR ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return if (realConfig) START_STICKY else START_NOT_STICKY
    }

    private fun rebuildSessions(fullSmoke: Boolean, realConfig: Boolean) {
        HostDiagnostics.log(
            this,
            "rebuildSessions begin fullSmoke=$fullSmoke realConfig=$realConfig sessions=${sessions.size}"
        )
        if (Python.isStarted()) {
            val python = Python.getInstance()
            runCatching { python.getModule("moonraker_runner").callAttr("stop") }
            runCatching { python.getModule("persistent_host").callAttr("stop") }
        }

        // Close first, then give Android a moment to release the CDC interfaces.
        // Sleeping before close does not help a kernel/USB stack that is still
        // unwinding the old claims after UsbDeviceConnection.close().
        val hadSessions = sessions.isNotEmpty()
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        if (hadSessions) Thread.sleep(USB_REOPEN_SETTLE_MS)
        if (!realConfig) releaseHostWakeLock()

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
            statusLines += hostprobe.callAttr("probe_klipper_version").toString()
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

        var expectedMcuCount = 0
        var allLikelyKlipper = false

        if (supported.isNotEmpty()) {
            expectedMcuCount = supported.size
            allLikelyKlipper =
                supported.all { (device, _) -> UsbDeviceScanner.isLikelyKlipper(device) }

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
                        HostDiagnostics.log(
                            this,
                            "USB bridge error for $stableId: ${error.javaClass.simpleName}: ${error.message}"
                        )
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
                            smokePtyPaths += pty.slavePath
                            stablePtyMap[stableId] = pty.slavePath
                        }
                    } else {
                        "Klipper identify skipped: unknown serial device"
                    }

                    statusLines += buildString {
                        append("MCU ${index + 1}: ${device.productName ?: driver.javaClass.simpleName}\n")
                        append("  transport: Android USB\n")
                        append("  id: $stableId\n")
                        append("  PTY: ${pty.slavePath}\n")
                        append("  Pipe: $pipeProbe\n")
                        append("  Protocol: $identifyProbe")
                    }
                } catch (t: Throwable) {
                    statusLines += "${device.productName ?: device.deviceName}: ERROR ${t.javaClass.simpleName}: ${t.message}"
                }
            }
        } else {
            val bridge = runCatching {
                BridgeDiscovery.discover(BRIDGE_DISCOVERY_TIMEOUT_MS)
                    .filter { it.mcus.isNotEmpty() }
                    .maxByOrNull { it.mcus.size }
            }.onFailure {
                HostDiagnostics.log(
                    this,
                    "bridge discovery ERROR ${it.javaClass.simpleName}: ${it.message}"
                )
            }.getOrNull()

            if (bridge == null) {
                statusLines +=
                    "No local USB MCUs and no AndroidKlipper Bridge discovered on Wi-Fi."
            } else {
                expectedMcuCount = bridge.mcus.size
                allLikelyKlipper = expectedMcuCount > 0
                statusLines +=
                    "Bridge: ${bridge.bridgeId} firmware=${bridge.firmware} " +
                    "host=${bridge.address.hostAddress} mcus=${bridge.mcus.size}"

                bridge.mcus.forEachIndexed { index, mcu ->
                    val stableId = mcu.serial
                    try {
                        val pty = PtyBridge.create()
                        val session = NetworkSerialSession(
                            stableId = stableId,
                            pty = pty,
                            address = bridge.address,
                            port = mcu.port
                        ) { error ->
                            HostDiagnostics.log(
                                this,
                                "Network bridge error for $stableId: " +
                                    "${error.javaClass.simpleName}: ${error.message}"
                            )
                            publishStatus(
                                "Network bridge error for $stableId: ${error.message}"
                            )
                        }
                        session.start()
                        sessions += session

                        val pipeProbe =
                            hostprobe.callAttr("probe_pipe_open", pty.slavePath).toString()
                        val identifyProbe = hostprobe.callAttr(
                            "probe_mcu_identify",
                            pty.slavePath,
                            helper.absolutePath,
                            115200
                        ).toString().also {
                            smokePtyPaths += pty.slavePath
                            stablePtyMap[stableId] = pty.slavePath
                        }

                        statusLines += buildString {
                            append("MCU ${index + 1}: AndroidKlipper Bridge\n")
                            append("  transport: TCP ${bridge.address.hostAddress}:${mcu.port}\n")
                            append("  id: $stableId\n")
                            append("  PTY: ${pty.slavePath}\n")
                            append("  Pipe: $pipeProbe\n")
                            append("  Protocol: $identifyProbe")
                        }
                    } catch (t: Throwable) {
                        statusLines +=
                            "Bridge MCU $stableId: ERROR ${t.javaClass.simpleName}: ${t.message}"
                    }
                }
            }
        }

        val allIdentified =
            expectedMcuCount > 0 &&
                smokePtyPaths.size == expectedMcuCount &&
                allLikelyKlipper

        if (realConfig) {
            if (!allIdentified || stablePtyMap.size != supported.size) {
                persistentHostActive.set(false)
                releaseHostWakeLock()
                HostDiagnostics.log(this, "persistent host skipped: MCU identify incomplete")
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
                    HostDiagnostics.log(this, "Klippy worker starting")
                    var firmwareRestartRequested = false
                    try {
                        val persistent = Python.getInstance().getModule("persistent_host")
                        val result = persistent.callAttr(
                            "run",
                            CONFIG_SOURCE_URL,
                            stableMapping,
                            helper.absolutePath,
                            filesDir.absolutePath,
                            deviceDisplayName()
                        ).toString()
                        firmwareRestartRequested = result.contains("firmware_restart")
                        HostDiagnostics.log(this, "Klippy worker returned: $result")
                        if (firmwareRestartRequested) {
                            publishStatus("Firmware restart: rebinding Android USB sessions?")
                        } else {
                            publishStatus("Persistent Klippy stopped\n\n$result")
                        }
                    } catch (t: Throwable) {
                        HostDiagnostics.log(
                            this,
                            "Klippy worker ERROR ${t.javaClass.simpleName}: ${t.message}"
                        )
                        publishStatus("Persistent Klippy ERROR ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        persistentHostActive.set(false)
                        if (firmwareRestartRequested &&
                            servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)) {
                            scheduleFirmwareRestartRecovery(sessions.size)
                        }
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
                    persistentHostActive.set(false)
                    releaseHostWakeLock()
                    HostDiagnostics.log(this, "persistent host failed: Klippy API socket did not appear")
                    statusLines += "Persistent Klippy ERROR: API socket did not appear."
                } else {
                    statusLines += "Persistent Klippy API: ${klippySocket.absolutePath}"
                    publishStatus(
                        "AndroidKlipper persistent host\n\n" +
                            statusLines.joinToString("\n\n") +
                            "\n\nStarting Moonraker on http://127.0.0.1:7125 …"
                    )

                    moonrakerExecutor.execute {
                        HostDiagnostics.log(this, "Moonraker worker starting")
                        try {
                            val runner = Python.getInstance().getModule("moonraker_runner")
                            val result = runner.callAttr("run", filesDir.absolutePath).toString()
                            HostDiagnostics.log(this, "Moonraker worker returned: $result")
                            publishStatus("Moonraker stopped\n\n$result")
                        } catch (t: Throwable) {
                            HostDiagnostics.log(
                                this,
                                "Moonraker worker ERROR ${t.javaClass.simpleName}: ${t.message}"
                            )
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

        HostDiagnostics.log(
            this,
            "rebuildSessions end realConfig=$realConfig supported=${supported.size} identified=${smokePtyPaths.size}"
        )

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

    private fun scheduleFirmwareRestartRecovery(expectedDevices: Int) {
        if (expectedDevices <= 0 || !firmwareRestartRecoveryPending.compareAndSet(false, true)) {
            return
        }
        HostDiagnostics.log(
            this,
            "firmware restart recovery scheduled expectedDevices=$expectedDevices"
        )
        hostExecutor.execute {
            try {
                // Capture the transport before closing it. A network bridge MCU reset
                // is recovered by rediscovery; native Android USB still waits for local
                // re-enumeration/permission exactly as before.
                val bridgeTransport =
                    sessions.any { it.statsSnapshot().startsWith("transport=bridge") }

                sessions.forEach { runCatching { it.close() } }
                sessions.clear()
                Thread.sleep(FIRMWARE_REENUM_SETTLE_MS)

                if (bridgeTransport) {
                    val deadline =
                        SystemClock.elapsedRealtime() + FIRMWARE_REENUM_TIMEOUT_MS
                    var bridgeMcuCount = 0

                    while (
                        bridgeMcuCount < expectedDevices &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        bridgeMcuCount = runCatching {
                            BridgeDiscovery.discover(1200)
                                .maxOfOrNull { it.mcus.size } ?: 0
                        }.getOrDefault(0)

                        if (bridgeMcuCount < expectedDevices) {
                            Thread.sleep(FIRMWARE_REENUM_POLL_MS)
                        }
                    }

                    if (bridgeMcuCount < expectedDevices) {
                        HostDiagnostics.log(
                            this,
                            "firmware restart bridge recovery incomplete " +
                                "devices=$bridgeMcuCount/$expectedDevices"
                        )
                        publishStatus(
                            "Firmware restart needs attention: bridge reports " +
                                "$bridgeMcuCount/$expectedDevices MCUs."
                        )
                        return@execute
                    }

                    if (!servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)) {
                        HostDiagnostics.log(
                            this,
                            "firmware restart bridge recovery cancelled: " +
                                "host no longer desired"
                        )
                        return@execute
                    }

                    HostDiagnostics.log(
                        this,
                        "firmware restart recovery rediscovered " +
                            "$bridgeMcuCount bridge MCUs"
                    )
                    persistentHostActive.set(true)
                    rebuildSessions(fullSmoke = false, realConfig = true)
                    return@execute
                }

                val deadline = SystemClock.elapsedRealtime() + FIRMWARE_REENUM_TIMEOUT_MS
                var supported = usbManager.deviceList.values
                    .filter { UsbDeviceScanner.isSupported(it) }
                while (supported.size < expectedDevices && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(FIRMWARE_REENUM_POLL_MS)
                    supported = usbManager.deviceList.values
                        .filter { UsbDeviceScanner.isSupported(it) }
                }

                if (supported.size < expectedDevices) {
                    HostDiagnostics.log(
                        this,
                        "firmware restart recovery incomplete devices=${supported.size}/$expectedDevices"
                    )
                    publishStatus(
                        "Firmware restart needs attention: only ${supported.size}/$expectedDevices MCU USB devices returned."
                    )
                    return@execute
                }

                val missingPermission = supported.firstOrNull { !usbManager.hasPermission(it) }
                if (missingPermission != null) {
                    HostDiagnostics.log(
                        this,
                        "firmware restart recovery requesting renewed USB permission"
                    )
                    getSharedPreferences("usb_permission_mode", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(EXTRA_FULL_SMOKE, false)
                        .putBoolean(EXTRA_REAL_CONFIG, true)
                        .apply()
                    publishStatus("Firmware restart: waiting for renewed USB permission?")
                    UsbPermissionReceiver.requestNext(
                        this, usbManager, missingPermission, fullSmoke = false, realConfig = true
                    )
                    return@execute
                }

                if (!servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)) {
                    HostDiagnostics.log(this, "firmware restart recovery cancelled: host no longer desired")
                    return@execute
                }

                HostDiagnostics.log(
                    this,
                    "firmware restart recovery rebinding ${supported.size} MCU USB devices"
                )
                persistentHostActive.set(true)
                rebuildSessions(fullSmoke = false, realConfig = true)
            } catch (t: Throwable) {
                HostDiagnostics.log(
                    this,
                    "firmware restart recovery ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
                publishStatus(
                    "Firmware restart recovery ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
            } finally {
                firmwareRestartRecoveryPending.set(false)
            }
        }
    }

    // These are Android USB bridge counters, not Klipper MCU retransmit counters.
    // Read retries can grow during normal empty bulk reads; correlate real failures with
    // Moonraker/Klipper bytes_retransmit, bytes_invalid, and print_stall before blaming USB.
    private fun logHeartbeat() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val sessionSummary = if (sessions.isEmpty()) {
            "none"
        } else {
            sessions.joinToString(" | ") { session ->
                "${session.stableId.takeLast(8)} ${session.statsSnapshot()}"
            }
        }
        HostDiagnostics.log(
            this,
            "heartbeat persistent=${persistentHostActive.get()} " +
                "wakeLock=${hostWakeLock?.isHeld == true} interactive=${powerManager.isInteractive} " +
                "deviceIdle=${powerManager.isDeviceIdleMode} sessions=${sessions.size} $sessionSummary"
        )
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

    private fun deviceDisplayName(): String {
        // Prefer Android's user-visible device name when the vendor exposes it.
        // Fall back to Build fields so Mainsail never has to call this "localhost".
        val configured = runCatching {
            android.provider.Settings.Global.getString(contentResolver, "device_name")
        }.getOrNull()?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("localhost", ignoreCase = true)
        }
        if (configured != null) return configured

        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        if (model.isEmpty()) return manufacturer.ifEmpty { "AndroidKlipper" }
        if (manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true)) {
            return model
        }
        return "$manufacturer $model"
    }

    private fun acquireHostWakeLock() {
        if (hostWakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        hostWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:KlipperHost"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        HostDiagnostics.log(this, "wake lock acquired held=${hostWakeLock?.isHeld == true}")
    }

    private fun releaseHostWakeLock() {
        val wasHeld = hostWakeLock?.isHeld == true
        hostWakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        hostWakeLock = null
        if (wasHeld) HostDiagnostics.log(this, "wake lock released")
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
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Klipper host", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        HostDiagnostics.log(this, "service onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        HostDiagnostics.log(this, "service onTrimMemory level=$level")
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        HostDiagnostics.log(
            this,
            "service onDestroy begin persistent=${persistentHostActive.get()} sessions=${sessions.size}"
        )
        persistentHostActive.set(false)
        if (Python.isStarted()) {
            val python = Python.getInstance()
            runCatching { python.getModule("moonraker_runner").callAttr("stop") }
            runCatching { python.getModule("persistent_host").callAttr("stop") }
        }
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        watchdogExecutor.shutdownNow()
        hostExecutor.shutdownNow()
        moonrakerExecutor.shutdownNow()
        klippyExecutor.shutdownNow()
        statusServer.close()
        runCatching { mainsailServer.stop() }
        releaseHostWakeLock()
        HostDiagnostics.log(this, "service onDestroy end")
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
        private const val PREF_SERVICE_STATE = "service_state"
        private const val KEY_DESIRED_REAL_HOST = "desired_real_host"
        private const val CONFIG_SOURCE_URL = "http://192.168.1.83:7125"
        private const val CHANNEL_ID = "klipper_host"
        private const val NOTIFICATION_ID = 7714
        private const val USB_REOPEN_SETTLE_MS = 250L
        private const val BRIDGE_DISCOVERY_TIMEOUT_MS = 2500
        private const val FIRMWARE_REENUM_SETTLE_MS = 500L
        private const val FIRMWARE_REENUM_TIMEOUT_MS = 15_000L
        private const val FIRMWARE_REENUM_POLL_MS = 100L

        fun start(
            context: Context,
            fullSmoke: Boolean = false,
            realConfig: Boolean = false
        ) {
            if (realConfig) {
                context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_DESIRED_REAL_HOST, true).apply()
            }
            HostDiagnostics.log(
                context,
                "service start requested fullSmoke=$fullSmoke realConfig=$realConfig"
            )
            val intent = Intent(context, KlipperHostService::class.java).apply {
                putExtra(EXTRA_FULL_SMOKE, fullSmoke)
                putExtra(EXTRA_REAL_CONFIG, realConfig)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESIRED_REAL_HOST, false).apply()
            HostDiagnostics.log(context, "service stop requested")
            context.stopService(Intent(context, KlipperHostService::class.java))
        }

        fun isPersistentHostDesired(context: Context): Boolean =
            context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESIRED_REAL_HOST, false)
    }
}
