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

        // Multi-MCU printers can trigger several Android permission dialogs.
        // Do not rebuild the host after each individual grant; wait until every
        // supported USB serial device is available, then start once.
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val supported = manager.deviceList.values.filter { UsbDeviceScanner.isSupported(it) }
        if (supported.isNotEmpty() && supported.all { manager.hasPermission(it) }) {
            KlipperHostService.start(context)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.nozzlenaut.androidklipper.USB_PERMISSION"
    }
}
