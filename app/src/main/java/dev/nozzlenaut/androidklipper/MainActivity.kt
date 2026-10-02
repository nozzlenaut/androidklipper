package dev.nozzlenaut.androidklipper

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var autoStartButton: Button
    private lateinit var advancedControls: LinearLayout
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val automationPrefs by lazy {
        getSharedPreferences(KlipperHostService.PREF_AUTOMATION, Context.MODE_PRIVATE)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(KlipperHostService.EXTRA_STATUS)?.let { report ->
                status.text = report
                updateSetupGuide(report)
                maybeOpenAutoKiosk(report)
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val heading = TextView(this).apply {
            text = "AndroidKlipper " + BuildConfig.VERSION_NAME
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val intro = TextView(this).apply {
            text = "Turn an Android device into a Klipper host. Follow the steps below; AndroidKlipper will handle USB startup and open Mainsail automatically."
            textSize = 14f
            setPadding(0, 16, 0, 12)
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

        val kiosk = Button(this).apply {
            text = "Open Mainsail"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MainsailActivity::class.java))
            }
        }
        val stop = Button(this).apply {
            text = "Stop AndroidKlipper host"
            setOnClickListener {
                KlipperHostService.stop(this@MainActivity)
                status.text = "AndroidKlipper host stopped."
                updateSetupGuide(status.text.toString())
            }
        }
        val advancedToggle = Button(this).apply {
            text = "Show advanced / troubleshooting"
            setOnClickListener {
                val show = advancedControls.visibility != View.VISIBLE
                advancedControls.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) {
                    "Hide advanced / troubleshooting"
                } else {
                    "Show advanced / troubleshooting"
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
        advancedControls.addView(copy)
        advancedControls.addView(test)
        advancedControls.addView(smoke)

        root.addView(heading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(intro, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(setupGuide, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(kiosk)
        root.addView(stop)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(advancedToggle)
        root.addView(advancedControls)
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }

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
        HostStatusStore.load(this)?.let {
            status.text = it
            updateSetupGuide(it)
        } ?: updateSetupGuide()
        val filter = IntentFilter(KlipperHostService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
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

    private fun updateSetupGuide(report: String? = HostStatusStore.load(this)) {
        val supported = supportedUsbDevices()
        val permissionsGranted =
            supported.isNotEmpty() && supported.all { usbManager.hasPermission(it) }
        val moonrakerReady = report?.contains("Moonraker READY:") == true
        val configPresent = File(filesDir, "printer_data/config/printer.cfg").exists()
        // Only mark this complete when the host publishes an explicit
        // READY state. A Klippy API socket can exist while config validation is
        // still in error, so socket presence alone is not enough.
        val klippyReady = report?.contains("Klippy READY:") == true

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
        status.text = "Printer USB detected. Starting AndroidKlipper…"
        updateSetupGuide(status.text.toString())
        requestUsbPermissionsAndStart(fullSmoke = false, realConfig = true)
    }

    private fun maybeOpenAutoKiosk(report: String) {
        if (!report.contains("Moonraker READY:")) return
        if (!automationPrefs.getBoolean(
                KlipperHostService.KEY_AUTO_KIOSK_PENDING, false
            )
        ) return
        automationPrefs.edit()
            .putBoolean(KlipperHostService.KEY_AUTO_KIOSK_PENDING, false)
            .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
            .apply()
        startActivity(Intent(this, MainsailActivity::class.java))
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
            status.text = "Waiting for printer USB."
            updateSetupGuide()
            return
        }

        val supported = devices.filter { UsbDeviceScanner.isSupported(it) }
        if (supported.isEmpty()) {
            status.text = "USB device detected, but no supported serial interface was found."
            updateSetupGuide()
            return
        }
        val next = supported.firstOrNull { !usbManager.hasPermission(it) }

        if (next == null) {
            KlipperHostService.start(this, fullSmoke, realConfig)
            if (realConfig && automationPrefs.getBoolean(
                    KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false
                )
            ) {
                automationPrefs.edit()
                    .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
                    .apply()
            }
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
            updateSetupGuide(status.text.toString())
        }
    }
}
