package dev.nozzlenaut.androidklipper.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import dev.nozzlenaut.androidklipper.KlipperHostService

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_PERMISSION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (!granted) return
        val fullSmoke = intent.getBooleanExtra(KlipperHostService.EXTRA_FULL_SMOKE, false)
        val realConfig = intent.getBooleanExtra(KlipperHostService.EXTRA_REAL_CONFIG, false)

        // Permission dialogs for multi-MCU printers arrive independently. Do
        // not rebuild/reopen every USB session after each callback; wait until
        // all supported devices are granted, then start the host once.
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val supported = manager.deviceList.values.filter { UsbDeviceScanner.isSupported(it) }
        if (supported.isNotEmpty() && supported.all { manager.hasPermission(it) }) {
            KlipperHostService.start(context, fullSmoke, realConfig)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.nozzlenaut.androidklipper.USB_PERMISSION"
    }
}
