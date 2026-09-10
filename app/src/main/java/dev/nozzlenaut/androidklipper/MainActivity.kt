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
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(KlipperHostService.EXTRA_STATUS)?.let { status.text = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val heading = TextView(this).apply {
            text = "AndroidKlipper host proof-of-concept"
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val safety = TextView(this).apply {
            text = "Safe diagnostic build: the Klipper protocol test only identifies MCUs. It does not configure motion, heaters, fans, or GPIO."
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
            setOnClickListener { requestUsbPermissionsAndStart() }
        }
        val copy = Button(this).apply {
            text = "Copy diagnostic report"
            setOnClickListener { copyReport() }
        }
        val stop = Button(this).apply {
            text = "Stop host test"
            setOnClickListener {
                stopService(Intent(this@MainActivity, KlipperHostService::class.java))
                status.text = "Host test stopped."
            }
        }

        root.addView(heading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(safety, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scan)
        root.addView(test)
        root.addView(copy)
        root.addView(stop)
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
        if (HostStatusStore.load(this) == null) scanUsb()
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

    private fun requestUsbPermissionsAndStart() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            status.text = "No USB devices found."
            return
        }

        var requested = 0
        devices.forEachIndexed { index, device ->
            if (!UsbDeviceScanner.isSupported(device)) return@forEachIndexed
            if (!usbManager.hasPermission(device)) {
                val intent = Intent(this, UsbPermissionReceiver::class.java).apply {
                    action = UsbPermissionReceiver.ACTION_USB_PERMISSION
                }
                val pending = PendingIntent.getBroadcast(
                    this,
                    1000 + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(device, pending)
                requested++
            }
        }

        if (requested == 0) {
            KlipperHostService.start(this)
        } else {
            status.text = "Grant USB permission for each printer MCU. The host test starts as permissions arrive."
        }
    }
}
