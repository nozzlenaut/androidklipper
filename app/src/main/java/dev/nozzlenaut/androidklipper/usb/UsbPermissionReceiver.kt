package dev.nozzlenaut.androidklipper.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dev.nozzlenaut.androidklipper.HostDiagnostics
import dev.nozzlenaut.androidklipper.KlipperHostService

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_PERMISSION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val cameraPermission = intent.getBooleanExtra(EXTRA_CAMERA_PERMISSION, false)
        HostDiagnostics.log(
            context,
            "USB permission callback granted=$granted camera=$cameraPermission"
        )
        if (cameraPermission) {
            if (granted) {
                HostDiagnostics.log(context, "USB camera permission granted; retrying camera bridge")
                KlipperHostService.retryCamera(context)
            } else {
                HostDiagnostics.log(context, "USB camera permission denied")
            }
            return
        }
        if (!granted) {
            context.getSharedPreferences(
                KlipperHostService.PREF_AUTOMATION, Context.MODE_PRIVATE
            ).edit()
                .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
                .putBoolean(KlipperHostService.KEY_AUTO_KIOSK_PENDING, false)
                .apply()
            return
        }
        val prefs = context.getSharedPreferences("usb_permission_mode", Context.MODE_PRIVATE)
        val fullSmoke = prefs.getBoolean(
            KlipperHostService.EXTRA_FULL_SMOKE,
            intent.getBooleanExtra(KlipperHostService.EXTRA_FULL_SMOKE, false)
        )
        val realConfig = prefs.getBoolean(
            KlipperHostService.EXTRA_REAL_CONFIG,
            intent.getBooleanExtra(KlipperHostService.EXTRA_REAL_CONFIG, false)
        )

        // Fire OS serializes USB permission dialogs. Chain the requests one at
        // a time so a single user action on the AndroidKlipper button walks
        // through every MCU and starts the host automatically at the end.
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val supported = manager.deviceList.values.filter { UsbDeviceScanner.isSupported(it) }
        val next = supported.firstOrNull { !manager.hasPermission(it) }
        if (next != null) {
            requestNext(context, manager, next, fullSmoke, realConfig)
        } else if (supported.isNotEmpty()) {
            HostDiagnostics.log(
                context,
                "USB permission chain complete; starting service fullSmoke=$fullSmoke realConfig=$realConfig"
            )
            KlipperHostService.start(context, fullSmoke, realConfig)
            context.getSharedPreferences(
                KlipperHostService.PREF_AUTOMATION, Context.MODE_PRIVATE
            ).edit()
                .putBoolean(KlipperHostService.KEY_AUTO_START_IN_PROGRESS, false)
                .apply()
            prefs.edit().clear().apply()
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.nozzlenaut.androidklipper.USB_PERMISSION"
        private const val EXTRA_CAMERA_PERMISSION = "camera_permission"

        fun requestCamera(
            context: Context,
            manager: UsbManager,
            device: UsbDevice
        ) {
            val callback = Intent(context, UsbPermissionReceiver::class.java).apply {
                action = ACTION_USB_PERMISSION
                putExtra(EXTRA_CAMERA_PERMISSION, true)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                2000 + (device.deviceId and 0x3fffffff),
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            manager.requestPermission(device, pending)
        }

        fun requestNext(
            context: Context,
            manager: UsbManager,
            device: UsbDevice,
            fullSmoke: Boolean,
            realConfig: Boolean
        ) {
            val callback = Intent(context, UsbPermissionReceiver::class.java).apply {
                action = ACTION_USB_PERMISSION
                putExtra(KlipperHostService.EXTRA_FULL_SMOKE, fullSmoke)
                putExtra(KlipperHostService.EXTRA_REAL_CONFIG, realConfig)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                1000 + (device.deviceId and 0x3fffffff),
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            manager.requestPermission(device, pending)
        }
    }
}
