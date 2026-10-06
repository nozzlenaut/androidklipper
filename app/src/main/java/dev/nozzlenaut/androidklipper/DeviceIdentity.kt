package dev.nozzlenaut.androidklipper

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIdentity {
    fun displayName(context: Context): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
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

    fun activeLanIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { address ->
                    address.startsWith("10.") ||
                        address.startsWith("192.168.") ||
                        address.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
                }
        }.getOrNull()
    }

    fun mainsailLanUrl(): String? = activeLanIpv4()?.let { "http://$it:8080/" }
}
