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
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.nozzlenaut.androidklipper.usb.UsbDeviceScanner
import dev.nozzlenaut.androidklipper.usb.UsbPermissionReceiver

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var autoStartButton: Button
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val automationPrefs by lazy {
        getSharedPreferences(KlipperHostService.PREF_AUTOMATION, Context.MODE_PRIVATE)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(KlipperHostService.EXTRA_STATUS)?.let { report ->
                status.text = report
                maybeOpenAutoKiosk(report)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val heading = TextView(this).apply {
            text = "AndroidKlipper " + BuildConfig.VERSION_NAME
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val safety = TextView(this).apply {
            text = "Connect the printer, start AndroidKlipper, then open Mainsail. AndroidKlipper identifies attached Klipper MCUs and keeps config, G-code, and Moonraker data in the persistent printer drive. Upload your normal Klipper config files through Mainsail if printer.cfg is missing or needs changes. Use Stop Host before disconnecting USB."
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        status = TextView(this).apply {
            text = HostStatusStore.load(this@MainActivity)
                ?: "Plug the printer into USB OTG, then scan."
            textSize = 15f
            setPadding(0, 24, 0, 24)
        }
        val scan = Button(this).apply {
            text = "Scan USB"
            setOnClickListener { scanUsb() }
        }
        val test = Button(this).apply {
            text = "Grant USB access and start host test"
            setOnClickListener { requestUsbPermissionsAndStart(fullSmoke = false) }
        }
        val smoke = Button(this).apply {
            text = "Run full Klippy no-pin smoke test"
            setOnClickListener { requestUsbPermissionsAndStart(fullSmoke = true) }
        }
        val realConfig = Button(this).apply {
            text = "Start AndroidKlipper host"
            setOnClickListener {
                requestUsbPermissionsAndStart(fullSmoke = false, realConfig = true)
            }
        }
        autoStartButton = Button(this).apply {
            setOnClickListener {
                val enabled = !automationPrefs.getBoolean(
                    KlipperHostService.KEY_AUTO_START_USB, false
                )
                automationPrefs.edit()
                    .putBoolean(KlipperHostService.KEY_AUTO_START_USB, enabled)
                    .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
                    .putBoolean(KlipperHostService.KEY_AUTO_KIOSK_PENDING, false)
                    .apply()
                updateAutoStartButton()
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "USB auto-start enabled" else "USB auto-start disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        updateAutoStartButton()
        val kiosk = Button(this).apply {
            text = "Open Mainsail kiosk"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MainsailActivity::class.java))
            }
        }
        val copy = Button(this).apply {
            text = "Copy diagnostic report"
            setOnClickListener { copyReport() }
        }
        val stop = Button(this).apply {
            text = "Stop Klipper/Moonraker host"
            setOnClickListener {
                KlipperHostService.stop(this@MainActivity)
                status.text = "Host test stopped."
            }
        }

        root.addView(heading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(safety, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scan)
        root.addView(test)
        root.addView(smoke)
        root.addView(realConfig)
        root.addView(autoStartButton)
        root.addView(kiosk)
        root.addView(copy)
        root.addView(stop)
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttach()
        } else if (HostStatusStore.load(this) == null) {
            scanUsb()
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
        HostStatusStore.load(this)?.let { status.text = it }
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

    private fun updateAutoStartButton() {
        val enabled = automationPrefs.getBoolean(
            KlipperHostService.KEY_AUTO_START_USB, false
        )
        autoStartButton.text =
            "Auto-start AndroidKlipper + Mainsail on printer USB: " +
                if (enabled) "ON" else "OFF"
    }

    private fun handleUsbAttach() {
        HostDiagnostics.log(
            this,
            "MainActivity USB_DEVICE_ATTACHED autoStart=" +
                automationPrefs.getBoolean(KlipperHostService.KEY_AUTO_START_USB, false)
        )
        if (!automationPrefs.getBoolean(KlipperHostService.KEY_AUTO_START_USB, false)) {
            scanUsb()
            return
        }

        val supported = usbManager.deviceList.values
            .filter { UsbDeviceScanner.isSupported(it) }
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
        status.text = "USB auto-start: printer USB detected; starting AndroidKlipper..."
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
            "No USB devices found."
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
            status.text = "No USB devices found."
            return
        }

        val supported = devices.filter { UsbDeviceScanner.isSupported(it) }
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
        } else {
            UsbPermissionReceiver.requestNext(
                this,
                usbManager,
                next,
                fullSmoke,
                realConfig
            )
            status.text = when {
                realConfig -> "Grant USB access. AndroidKlipper will request each supported printer USB device, identify Klipper MCUs, start Moonraker, and then start Klippy when printer.cfg is available."
                fullSmoke -> "Grant USB access. AndroidKlipper will request each remaining printer MCU automatically, then start the smoke test."
                else -> "Grant USB access. AndroidKlipper will request each remaining printer MCU automatically, then start the host test."
            }
        }
    }
}
