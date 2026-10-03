package dev.nozzlenaut.androidklipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import dev.nozzlenaut.androidklipper.usb.UsbDeviceScanner
import dev.nozzlenaut.androidklipper.usb.UsbSerialSession
import dev.nozzlenaut.androidklipper.usb.UsbPermissionReceiver
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KlipperHostService : Service() {
    private val sessions = CopyOnWriteArrayList<UsbSerialSession>()
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
        if (realConfig) {
            setMoonrakerReady(false)
            setKlippyReady(false)
            persistentHostActive.set(true)
        }

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
        setMoonrakerReady(false)
        setKlippyReady(false)
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

        val storageSummary = PersistentPrinterDrive.prepare(this).summary

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
                // Do not guess printer compatibility from VID:PID alone. Many
                // Klipper boards sit behind CH340/FTDI/other USB-serial chips.
                // A successful Klipper identify handshake is the authority.
                val identifyProbe = try {
                    hostprobe.callAttr(
                        "probe_mcu_identify",
                        pty.slavePath,
                        helper.absolutePath,
                        250000
                    ).toString().also {
                        smokePtyPaths += pty.slavePath
                        stablePtyMap[stableId] = pty.slavePath
                    }
                } catch (t: Throwable) {
                    sessions.remove(session)
                    runCatching { session.close() }
                    throw IllegalStateException(
                        "serial device did not identify as Klipper: ${t.message}", t
                    )
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

        val allIdentified =
            supported.isNotEmpty() && stablePtyMap.size == supported.size

        if (realConfig) {
            val stableMapping = stablePtyMap.entries.joinToString("|") { (id, path) -> "$id=$path" }
            statusLines += "Klipper MCUs identified: ${stablePtyMap.size}"
            val moonrakerReady = startMoonraker(statusLines)

            val configPath = File(filesDir, "printer_data/config/printer.cfg")
            val missingIncludes =
                if (configPath.exists()) missingConfigIncludes(configPath) else null
            when {
                stablePtyMap.isEmpty() -> {
                    statusLines += "Klippy WAITING: no USB serial device completed a Klipper identify handshake."
                }
                !configPath.exists() -> {
                    statusLines += "Klippy WAITING: printer.cfg is missing. Open Mainsail and upload your normal Klipper config files."
                    if (moonrakerReady) {
                        waitForFirstPrinterConfig(stableMapping, helper, statusLines.toList())
                    }
                }
                missingIncludes != null -> {
                    statusLines += "Klippy WAITING: config upload is incomplete; waiting for $missingIncludes"
                    if (moonrakerReady) {
                        waitForFirstPrinterConfig(stableMapping, helper, statusLines.toList())
                    }
                }
                else -> {
                    startPersistentKlippy(stableMapping, helper, statusLines)
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

    private fun missingConfigIncludes(configPath: File): String? {
        return runCatching {
            val result = Python.getInstance().getModule("hostprobe")
                .callAttr("check_config_includes", configPath.absolutePath)
                .toString()
            if (result.startsWith("missing: ")) result.removePrefix("missing: ").trim()
            else null
        }.onFailure {
            // Preflight is an onboarding convenience, not a replacement for
            // Klipper's own parser. If the scan itself fails, let Klippy be the
            // authority instead of blocking an otherwise valid config forever.
            HostDiagnostics.log(
                this,
                "config include preflight skipped: ${it.javaClass.simpleName}: ${it.message}"
            )
        }.getOrNull()
    }

    private fun configTreeFingerprint(configDir: File): String =
        configDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(configDir).path }
            .joinToString("|") {
                "${it.relativeTo(configDir).path}:${it.length()}:${it.lastModified()}"
            }

    private fun waitForFirstPrinterConfig(
        stableMapping: String,
        helper: File,
        baseStatus: List<String>
    ) {
        hostExecutor.execute {
            val configPath = File(filesDir, "printer_data/config/printer.cfg")
            val cleanBase = baseStatus.filterNot { it.startsWith("Klippy WAITING:") }
            var lastWaitReason: String? = null
            HostDiagnostics.log(this, "waiting for usable printer.cfg upload")
            while (persistentHostActive.get() &&
                servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)
            ) {
                if (configPath.exists()) {
                    val missingIncludes = missingConfigIncludes(configPath)
                    if (missingIncludes == null) {
                        val before = configTreeFingerprint(configPath.parentFile)
                        Thread.sleep(CONFIG_UPLOAD_SETTLE_MS)
                        val after = configTreeFingerprint(configPath.parentFile)
                        val afterMissing = missingConfigIncludes(configPath)
                        if (before == after && afterMissing == null) {
                            val lines = cleanBase.toMutableList()
                            lines += "printer.cfg and referenced includes are present: starting Klippy automatically."
                            HostDiagnostics.log(
                                this,
                                "printer.cfg include preflight complete and files stable; starting Klippy"
                            )
                            startPersistentKlippy(stableMapping, helper, lines)
                            publishStatus(
                                "AndroidKlipper host\n\n" + lines.joinToString("\n\n")
                            )
                            return@execute
                        }
                        continue
                    }

                    val waitReason =
                        "Klippy WAITING: config upload is incomplete; waiting for $missingIncludes"
                    if (waitReason != lastWaitReason) {
                        lastWaitReason = waitReason
                        HostDiagnostics.log(this, waitReason)
                        publishStatus(
                            "AndroidKlipper host\n\n" +
                                (cleanBase + waitReason).joinToString("\n\n")
                        )
                    }
                }
                Thread.sleep(FIRST_CONFIG_POLL_MS)
            }
            HostDiagnostics.log(this, "printer.cfg wait ended: host no longer active")
        }
    }

    private fun startMoonraker(statusLines: MutableList<String>): Boolean {
        setMoonrakerReady(false)
        publishStatus(
            "AndroidKlipper host\n\n" +
                statusLines.joinToString("\n\n") +
                "\n\nStarting Moonraker on http://127.0.0.1:7125 …"
        )

        moonrakerExecutor.execute {
            HostDiagnostics.log(this, "Moonraker worker starting")
            try {
                val runner = Python.getInstance().getModule("moonraker_runner")
                val result = runner.callAttr("run", filesDir.absolutePath).toString()
                setMoonrakerReady(false)
                HostDiagnostics.log(this, "Moonraker worker returned: $result")
                publishStatus("Moonraker stopped\n\n$result")
            } catch (t: Throwable) {
                setMoonrakerReady(false)
                HostDiagnostics.log(
                    this,
                    "Moonraker worker ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
                publishStatus("Moonraker ERROR ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        for (attempt in 0 until 300) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", 7125), 200)
                }
                setMoonrakerReady(true)
                statusLines += "Moonraker READY: http://127.0.0.1:7125"
                return true
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }

        val runnerStatus = runCatching {
            Python.getInstance().getModule("moonraker_runner")
                .callAttr("get_status").toString()
        }.getOrElse { "status unavailable: ${it.message}" }
        statusLines +=
            "Moonraker ERROR: port 7125 did not open.\nMoonraker runtime: $runnerStatus"
        return false
    }

    private fun startPersistentKlippy(
        stableMapping: String,
        helper: File,
        statusLines: MutableList<String>
    ): Boolean {
        setKlippyReady(false)
        val klippySocket = File(filesDir, "printer_data/comms/klippy.sock")
        publishStatus(
            "AndroidKlipper host\n\n" +
                statusLines.joinToString("\n\n") +
                "\n\nStarting Klippy with persistent config…"
        )

        klippyExecutor.execute {
            HostDiagnostics.log(this, "Klippy worker starting")
            var firmwareRestartRequested = false
            try {
                val persistent = Python.getInstance().getModule("persistent_host")
                val result = persistent.callAttr(
                    "run",
                    stableMapping,
                    helper.absolutePath,
                    filesDir.absolutePath,
                    deviceDisplayName()
                ).toString()
                firmwareRestartRequested = result.contains("firmware_restart")
                setKlippyReady(false)
                HostDiagnostics.log(this, "Klippy worker returned: $result")
                if (firmwareRestartRequested) {
                    publishStatus("Firmware restart: rebinding Android USB sessions…")
                } else {
                    publishStatus("Klippy stopped\n\n$result")
                }
            } catch (t: Throwable) {
                setKlippyReady(false)
                HostDiagnostics.log(
                    this,
                    "Klippy worker ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
                publishStatus("Klippy ERROR ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                if (firmwareRestartRequested &&
                    servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)
                ) {
                    scheduleFirmwareRestartRecovery(sessions.size)
                }
            }
        }

        for (attempt in 0 until 300) {
            if (klippySocket.exists()) {
                statusLines += "Klippy API: ${klippySocket.absolutePath}"

                // Socket creation happens before config validation finishes.
                // Wait briefly for Klippy's own state so onboarding can report
                // READY honestly instead of treating "socket exists" as success.
                for (readyAttempt in 0 until 100) {
                    val runtimeStatus = runCatching {
                        Python.getInstance().getModule("persistent_host")
                            .callAttr("get_status").toString()
                    }.getOrElse { "" }
                    if (runtimeStatus.startsWith("ready:")) {
                        setKlippyReady(true)
                        statusLines += "Klippy READY: ${runtimeStatus.removePrefix("ready:").trim()}"
                        return true
                    }
                    if (runtimeStatus.startsWith("error:") ||
                        runtimeStatus.startsWith("shutdown:")
                    ) {
                        setKlippyReady(false)
                        statusLines += "Klippy state: $runtimeStatus"
                        return true
                    }
                    Thread.sleep(100)
                }
                setKlippyReady(false)
                statusLines += "Klippy state: starting"
                return true
            }
            Thread.sleep(100)
        }

        val klippyStatus = runCatching {
            Python.getInstance().getModule("persistent_host")
                .callAttr("get_status").toString()
        }.getOrElse { "status unavailable: ${it.message}" }
        setKlippyReady(false)
        statusLines +=
            "Klippy ERROR: API socket did not appear.\nKlippy runtime: $klippyStatus"
        return false
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
                // Klippy deliberately returned before Android's physical USB
                // sessions are rebuilt. At this point there is no live print to
                // preserve: FIRMWARE_RESTART has already ended the Klippy runtime.
                sessions.forEach { runCatching { it.close() } }
                sessions.clear()
                Thread.sleep(FIRMWARE_REENUM_SETTLE_MS)

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
                // Direct recovery bypasses onStartCommand(), so explicitly re-arm
                // the duplicate-start guard before launching the replacement host.
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

    private fun setMoonrakerReady(ready: Boolean) {
        servicePrefs.edit().putBoolean(KEY_MOONRAKER_READY, ready).apply()
    }

    private fun setKlippyReady(ready: Boolean) {
        servicePrefs.edit().putBoolean(KEY_KLIPPY_READY, ready).apply()
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
        val protectHost = persistentHostActive.get() ||
            servicePrefs.getBoolean(KEY_DESIRED_REAL_HOST, false)
        HostDiagnostics.log(
            this,
            "service onTaskRemoved protectHost=$protectHost sessions=${sessions.size}"
        )
        if (protectHost) {
            // Removing the UI task must not demote or rebuild a live printer host.
            // Refresh the foreground-service state and wake lock in place; keep the
            // existing Klippy process, PTYs, and USB sessions completely untouched.
            acquireHostWakeLock()
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification("Klipper host running")
                )
            }.onFailure {
                HostDiagnostics.log(
                    this,
                    "onTaskRemoved foreground refresh failed: ${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }
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
        setMoonrakerReady(false)
        setKlippyReady(false)
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
        private const val KEY_MOONRAKER_READY = "moonraker_ready"
        private const val KEY_KLIPPY_READY = "klippy_ready"
        private const val CHANNEL_ID = "klipper_host"
        private const val NOTIFICATION_ID = 7714
        private const val USB_REOPEN_SETTLE_MS = 250L
        private const val FIRMWARE_REENUM_SETTLE_MS = 500L
        private const val FIRMWARE_REENUM_TIMEOUT_MS = 15_000L
        private const val FIRMWARE_REENUM_POLL_MS = 100L
        private const val FIRST_CONFIG_POLL_MS = 500L
        private const val CONFIG_UPLOAD_SETTLE_MS = 1_000L

        fun start(
            context: Context,
            fullSmoke: Boolean = false,
            realConfig: Boolean = false
        ) {
            if (realConfig) {
                context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DESIRED_REAL_HOST, true)
                    .apply()
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
                .edit()
                .putBoolean(KEY_DESIRED_REAL_HOST, false)
                .putBoolean(KEY_MOONRAKER_READY, false)
                .putBoolean(KEY_KLIPPY_READY, false)
                .apply()
            HostDiagnostics.log(context, "service stop requested")
            context.stopService(Intent(context, KlipperHostService::class.java))
        }

        fun isPersistentHostDesired(context: Context): Boolean =
            context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESIRED_REAL_HOST, false)

        fun isMoonrakerReady(context: Context): Boolean =
            context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                .getBoolean(KEY_MOONRAKER_READY, false)

        fun isKlippyReady(context: Context): Boolean =
            context.getSharedPreferences(PREF_SERVICE_STATE, Context.MODE_PRIVATE)
                .getBoolean(KEY_KLIPPY_READY, false)
    }
}
