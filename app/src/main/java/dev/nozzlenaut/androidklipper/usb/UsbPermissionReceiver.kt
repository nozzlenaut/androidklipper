package dev.nozzlenaut.androidklipper.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import dev.nozzlenaut.androidklipper.HostStatusStore
import dev.nozzlenaut.androidklipper.KlipperHostService

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_PERMISSION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (granted) {
            KlipperHostService.start(context)
            return
        }

        val message = "USB permission denied. No device was opened; tap the host test button to try again."
        HostStatusStore.save(context, message)
        context.sendBroadcast(Intent(KlipperHostService.ACTION_STATUS).apply {
            setPackage(context.packageName)
            putExtra(KlipperHostService.EXTRA_STATUS, message)
        })
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.nozzlenaut.androidklipper.USB_PERMISSION"
    }
}
