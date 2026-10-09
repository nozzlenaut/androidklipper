package dev.nozzlenaut.androidklipper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.nozzlenaut.androidklipper.usb.UsbDeviceScanner
import dev.nozzlenaut.androidklipper.usb.UsbPermissionReceiver
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var setupGuide: TextView
    private lateinit var mainsailButton: Button
    private lateinit var mainsailLanAddress: TextView
    private lateinit var copyMainsailAddressButton: Button
    private lateinit var autoStartButton: Button
    private lateinit var batteryOptimizationButton: Button
    private lateinit var advancedControls: LinearLayout
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val automationPrefs by lazy {
        getSharedPreferences(KlipperHostService.PREF_AUTOMATION, Context.MODE_PRIVATE)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(KlipperHostService.EXTRA_STATUS)?.let {
                handleStatusReport(it, HostReportSource.LIVE)
            }
        }
    }

    private fun handleStatusReport(report: String, source: HostReportSource) {
        status.text = report
        updateSetupGuide()
        maybeOpenAutoKiosk()
        if (automationPrefs.getBoolean(
                KlipperHostService.KEY_AUTO_KIOSK_PENDING, false
            ) &&
            HostReportPolicy.canCancelAutoStart(report, source)
        ) {
            clearAutoStartState()
        }
    }

    // SharedPreferences are not coherent across Android processes. Send the
    // current setting explicitly whenever the isolated kiosk is launched.
    private fun openMainsail() {
        startActivity(Intent(this, MainsailActivity::class.java).putExtra(
            KlipperHostService.KEY_KEEP_MAINSAIL_SCREEN_AWAKE,
            automationPrefs.getBoolean(KlipperHostService.KEY_KEEP_MAINSAIL_SCREEN_AWAKE, true)
        ))
    }

    override fun onResume() {
        super.onResume()
        if (::batteryOptimizationButton.isInitialized) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            batteryOptimizationButton.text = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                "Battery optimization: EXEMPT"
            } else {
                "Battery optimization: CHECK SETTINGS"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Normal-user behavior: USB auto-start is ON unless explicitly
        // disabled under Advanced / troubleshooting.
        if (!automationPrefs.contains(KlipperHostService.KEY_AUTO_START_USB)) {
            automationPrefs.edit()
                .putBoolean(KlipperHostService.KEY_AUTO_START_USB, true)
                .apply()
        }
        // KEY_AUTO_START_IN_PROGRESS is only a duplicate-start guard. If the
        // activity/process died before the service was actually requested, do
        // not let that persisted bit strand the next launch in a permanent
        // "already starting" state.
        if (automationPrefs.getBoolean(
                KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false
            ) &&
            !KlipperHostService.isPersistentHostDesired(this)
        ) {
            clearAutoStartState()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val heading = TextView(this).apply {
            text = "AndroidKlipper " + BuildConfig.VERSION_NAME
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val deviceName = DeviceIdentity.displayName(this)
        val hostSummary = TextView(this).apply {
            text = "Host device: $deviceName"
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 4)
        }
        val intro = TextView(this).apply {
            text = "Connect the printer over USB, grant Android access, and AndroidKlipper will start Moonraker and Mainsail. On first setup, upload your normal Klipper config in Mainsail; Klippy starts automatically when the config tree is complete."
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        val powerNote = TextView(this).apply {
            text = "Long prints require an Android device and hub/adapter that can charge while the device remains in USB host mode."
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        val setupHeading = TextView(this).apply {
            text = "Setup"
            textSize = 18f
            setPadding(0, 4, 0, 4)
        }
        setupGuide = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 18)
        }
        status = TextView(this).apply {
            text = HostStatusStore.load(this@MainActivity) ?: "Waiting for printer USB."
            textSize = 14f
            setPadding(0, 16, 0, 20)
        }

        mainsailButton = Button(this).apply {
            text = "Open Mainsail"
            isEnabled = false
            setOnClickListener {
                openMainsail()
            }
        }
        mainsailLanAddress = TextView(this).apply {
            text = "Remote Mainsail: available after startup"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        copyMainsailAddressButton = Button(this).apply {
            text = "Copy remote Mainsail address"
            isEnabled = false
            setOnClickListener { copyMainsailAddress() }
        }
        val stop = Button(this).apply {
            text = "Stop host"
            setOnClickListener {
                KlipperHostService.stop(this@MainActivity)
                status.text = "AndroidKlipper host stopped."
                updateSetupGuide()
            }
        }
        val statusHeading = TextView(this).apply {
            text = "Status"
            textSize = 18f
            setPadding(0, 16, 0, 0)
        }
        val advancedToggle = Button(this).apply {
            text = "Show advanced / diagnostics"
            setOnClickListener {
                val show = advancedControls.visibility != View.VISIBLE
                advancedControls.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) {
                    "Hide advanced / diagnostics"
                } else {
                    "Show advanced / diagnostics"
                }
            }
        }

        advancedControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val scan = Button(this).apply {
            text = "Scan USB"
            setOnClickListener { scanUsb() }
        }
        val realConfig = Button(this).apply {
            text = "Start AndroidKlipper host"
            setOnClickListener {
                requestUsbPermissionsAndStart(fullSmoke = false, realConfig = true)
            }
        }
        autoStartButton = Button(this).apply {
            setOnClickListener {
                val enabled = !isAutoStartEnabled()
                automationPrefs.edit()
                    .putBoolean(KlipperHostService.KEY_AUTO_START_USB, enabled)
                    .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
                    .putBoolean(KlipperHostService.KEY_AUTO_KIOSK_PENDING, false)
                    .apply()
                updateAutoStartButton()
                updateSetupGuide()
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "USB auto-start enabled" else "USB auto-start disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        updateAutoStartButton()
        val keepMainsailAwake = Button(this).apply {
            fun refreshLabel() {
                text = "Keep Mainsail display awake: " +
                    if (automationPrefs.getBoolean(
                            KlipperHostService.KEY_KEEP_MAINSAIL_SCREEN_AWAKE, true
                        )
                    ) "ON" else "OFF"
            }
            refreshLabel()
            setOnClickListener {
                val enabled = !automationPrefs.getBoolean(
                    KlipperHostService.KEY_KEEP_MAINSAIL_SCREEN_AWAKE, true
                )
                automationPrefs.edit()
                    .putBoolean(KlipperHostService.KEY_KEEP_MAINSAIL_SCREEN_AWAKE, enabled)
                    .apply()
                refreshLabel()
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) {
                        "Mainsail will keep the display awake"
                    } else {
                        "Mainsail will allow normal screen timeout"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        batteryOptimizationButton = Button(this).apply {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val exempt = powerManager.isIgnoringBatteryOptimizations(packageName)
            text = if (exempt) {
                "Battery optimization: EXEMPT"
            } else {
                "Battery optimization: CHECK SETTINGS"
            }
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        val copy = Button(this).apply {
            text = "Copy diagnostic report"
            setOnClickListener { copyReport() }
        }
        val test = Button(this).apply {
            text = "Run USB host test"
            setOnClickListener { requestUsbPermissionsAndStart(fullSmoke = false) }
        }
        val smoke = Button(this).apply {
            text = "Run full Klippy no-pin smoke test"
            setOnClickListener { requestUsbPermissionsAndStart(fullSmoke = true) }
        }

        advancedControls.addView(scan)
        advancedControls.addView(realConfig)
        advancedControls.addView(autoStartButton)
        advancedControls.addView(keepMainsailAwake)
        advancedControls.addView(batteryOptimizationButton)
        advancedControls.addView(copy)
        advancedControls.addView(test)
        advancedControls.addView(smoke)

        root.addView(heading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(hostSummary, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(intro, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(powerNote, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(setupHeading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(setupGuide, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(mainsailButton)
        root.addView(mainsailLanAddress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(copyMainsailAddressButton)
        root.addView(stop)
        root.addView(statusHeading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(advancedToggle)
        root.addView(advancedControls)
        setContentView(ScrollView(this).apply { addView(root) })

        // Keep first-run focused on printer USB. Android 13+ allows a
        // foreground service to start without POST_NOTIFICATIONS; asking for
        // notifications here can stack a second system dialog on top of the
        // USB permission chain and make initial setup flaky/confusing.

        updateSetupGuide()
        when {
            intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbAttach()
            supportedUsbDevices().isNotEmpty() && isAutoStartEnabled() -> handleUsbAttach()
            HostStatusStore.load(this) == null -> scanUsb()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttach()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(KlipperHostService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }

        // A system USB permission dialog (or a brief app switch) can hide this
        // activity while the service reaches Moonraker READY. Replaying the
        // stored report still updates the UI and opens a ready kiosk. However,
        // an old failure must not cancel the fresh USB startup armed in onCreate.
        HostStatusStore.load(this)?.let {
            handleStatusReport(it, HostReportSource.SAVED)
        } ?: updateSetupGuide()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun isAutoStartEnabled(): Boolean =
        automationPrefs.getBoolean(KlipperHostService.KEY_AUTO_START_USB, true)

    private fun supportedUsbDevices() =
        usbManager.deviceList.values.filter { UsbDeviceScanner.isSupported(it) }

    private fun updateAutoStartButton() {
        autoStartButton.text =
            "Auto-start AndroidKlipper + Mainsail on printer USB: " +
                if (isAutoStartEnabled()) "ON" else "OFF"
    }

    private fun updateSetupGuide() {
        val supported = supportedUsbDevices()
        val permissionsGranted =
            supported.isNotEmpty() && supported.all { usbManager.hasPermission(it) }
        // These are latched by the service rather than inferred from the
        // latest status sentence. Klippy errors/restarts must not make a healthy
        // Moonraker/Mainsail instance appear to disappear from the walkthrough.
        val moonrakerReady = KlipperHostService.isMoonrakerReady(this)
        mainsailButton.isEnabled = moonrakerReady
        val remoteMainsailUrl = DeviceIdentity.mainsailLanUrl()
        mainsailLanAddress.text = when {
            !moonrakerReady -> "Remote Mainsail: available after startup"
            remoteMainsailUrl != null -> "Remote Mainsail: $remoteMainsailUrl"
            else -> "Remote Mainsail: no LAN address detected"
        }
        copyMainsailAddressButton.isEnabled = moonrakerReady && remoteMainsailUrl != null
        val configPresent = File(filesDir, "printer_data/config/printer.cfg").exists()
        val klippyReady = KlipperHostService.isKlippyReady(this)

        fun mark(done: Boolean) = if (done) "✓" else "•"

        setupGuide.text = buildString {
            append(mark(true) + " 1. Install and open AndroidKlipper\n")
            append(mark(supported.isNotEmpty()) + " 2. Plug in printer USB and turn the printer on")
            if (supported.isNotEmpty()) {
                append("  (" + supported.size + " USB device(s) detected)")
            }
            append("\n")
            append(mark(permissionsGranted) + " 3. Grant USB access when Android asks\n")
            append(mark(moonrakerReady) + " 4. AndroidKlipper starts Moonraker and opens Mainsail\n")
            append(mark(configPresent) + " 5. If printer.cfg is missing, upload your normal config files in Mainsail\n")
            append(mark(klippyReady) + " 6. Klipper reaches READY — upload G-code and print")

            if (!isAutoStartEnabled()) {
                append("\n\nUSB auto-start is OFF. Enable it under Advanced / troubleshooting for the normal setup flow.")
            } else if (supported.isEmpty()) {
                append("\n\nWaiting for printer USB…")
            } else if (!permissionsGranted) {
                append("\n\nPrinter USB detected. Tap Allow on each Android USB permission prompt.")
            } else if (!moonrakerReady) {
                append("\n\nUSB access granted. Starting AndroidKlipper…")
            } else if (!configPresent) {
                append("\n\nMainsail is ready. Upload printer.cfg and its included config files.")
            }
        }
    }

    private fun handleUsbAttach() {
        HostDiagnostics.log(
            this,
            "MainActivity USB_DEVICE_ATTACHED autoStart=" + isAutoStartEnabled()
        )
        if (!isAutoStartEnabled()) {
            scanUsb()
            return
        }

        val supported = supportedUsbDevices()
        if (supported.isEmpty()) {
            scanUsb()
            status.append(
                "\n\nUSB auto-start armed: waiting for a supported printer USB serial device."
            )
            return
        }

        if (automationPrefs.getBoolean(
                KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false
            )
        ) return

        automationPrefs.edit()
            .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, true)
            .putBoolean(KlipperHostService.KEY_AUTO_KIOSK_PENDING, true)
            .apply()
        status.text = "Printer USB detected. Waiting briefly for all printer USB devices…"
        updateSetupGuide()

        // Multi-MCU printers are often behind a USB hub. One child can trigger
        // USB_DEVICE_ATTACHED before its siblings finish enumerating. A short
        // settle window makes the permission scan deterministic and avoids
        // starting the host with only part of the printer present.
        Handler(Looper.getMainLooper()).postDelayed({
            if (automationPrefs.getBoolean(
                    KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false
                )
            ) {
                requestUsbPermissionsAndStart(fullSmoke = false, realConfig = true)
            }
        }, USB_ATTACH_SETTLE_MS)
    }

    private fun clearAutoStartState() {
        automationPrefs.edit()
            .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
            .putBoolean(KlipperHostService.KEY_AUTO_KIOSK_PENDING, false)
            .apply()
    }

    private fun maybeOpenAutoKiosk() {
        if (!KlipperHostService.isMoonrakerReady(this)) return
        if (!automationPrefs.getBoolean(
                KlipperHostService.KEY_AUTO_KIOSK_PENDING, false
            )
        ) return
        clearAutoStartState()
        openMainsail()
    }

    private fun scanUsb() {
        val found = UsbDeviceScanner.describeDevices(usbManager)
        status.text = if (found.isEmpty()) {
            "Waiting for printer USB."
        } else {
            buildString {
                append("USB devices found: ${found.size}\n\n")
                found.forEachIndexed { index, d ->
                    append("${index + 1}. ${d.productName ?: d.deviceName}\n")
                    append("   VID:PID ${d.vendorId.toString(16).padStart(4, '0')}:${d.productId.toString(16).padStart(4, '0')}\n")
                    append("   permission: ${if (d.hasPermission) "yes" else "no"}\n")
                    append("   driver: ${d.driverName ?: "not detected"}\n\n")
                }
            }
        }
        updateSetupGuide()
    }

    private fun copyMainsailAddress() {
        val url = DeviceIdentity.mainsailLanUrl()
        if (url == null) {
            Toast.makeText(this, "No LAN address detected", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AndroidKlipper Mainsail", url))
        Toast.makeText(this, "Mainsail address copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyReport() {
        val report = HostStatusStore.load(this) ?: status.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AndroidKlipper diagnostic", report))
        Toast.makeText(this, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    private fun requestUsbPermissionsAndStart(
        fullSmoke: Boolean,
        realConfig: Boolean = false
    ) {
        getSharedPreferences("usb_permission_mode", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KlipperHostService.EXTRA_FULL_SMOKE, fullSmoke)
            .putBoolean(KlipperHostService.EXTRA_REAL_CONFIG, realConfig)
            .apply()

        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            status.text = "Printer USB disappeared before startup. Reconnect the printer."
            clearAutoStartState()
            updateSetupGuide()
            return
        }

        val supported = devices.filter { UsbDeviceScanner.isSupported(it) }
        if (supported.isEmpty()) {
            status.text = "USB device detected, but no supported serial interface was found."
            clearAutoStartState()
            updateSetupGuide()
            return
        }
        val next = supported.firstOrNull { !usbManager.hasPermission(it) }

        if (next == null) {
            KlipperHostService.start(this, fullSmoke, realConfig)
            // Keep the auto-start guard armed until Moonraker is actually READY.
            // This prevents late USB attach events from launching a second startup
            // while the first host is still opening PTYs and identifying MCUs.
            updateSetupGuide()
        } else {
            UsbPermissionReceiver.requestNext(
                this,
                usbManager,
                next,
                fullSmoke,
                realConfig
            )
            status.text = when {
                realConfig -> "Printer USB detected. Grant USB access; AndroidKlipper will request each MCU, start Moonraker, and open Mainsail automatically."
                fullSmoke -> "Grant USB access. AndroidKlipper will request each remaining printer MCU automatically, then start the smoke test."
                else -> "Grant USB access. AndroidKlipper will request each remaining printer MCU automatically, then start the host test."
            }
            updateSetupGuide()
        }
    }

    companion object {
        private const val USB_ATTACH_SETTLE_MS = 750L
    }
}
