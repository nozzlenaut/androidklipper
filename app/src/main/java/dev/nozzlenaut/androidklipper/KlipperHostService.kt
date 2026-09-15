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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.nozzlenaut.androidklipper.pty.PtyBridge
import dev.nozzlenaut.androidklipper.usb.UsbDeviceScanner
import dev.nozzlenaut.androidklipper.usb.UsbSerialSession
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class KlipperHostService : Service() {
    private val sessions = CopyOnWriteArrayList<UsbSerialSession>()
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val hostExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "androidklipper-host").apply { isDaemon = true }
    }
    private val statusServer = LocalStatusServer { HostStatusStore.load(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { statusServer.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Starting USB host test…"))
        // USB permission callbacks can arrive close together for multi-MCU printers.
        // Serialize rebuilds so two service starts never fight over the same device.
        hostExecutor.execute { rebuildSessions() }
        return START_STICKY
    }

    private fun rebuildSessions() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val hostprobe = Python.getInstance().getModule("hostprobe")
        val helper = File(applicationInfo.nativeLibraryDir, "libklipper_c_helper.so")
        val statusLines = mutableListOf<String>()

        try {
            statusLines += hostprobe.callAttr("probe_klipper_import").toString()
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
                val serial = runCatching { connection.serial }.getOrNull()
                val stableId = serial?.takeIf { it.isNotBlank() }
                    ?: "%04x:%04x:%s".format(device.vendorId, device.productId, device.deviceName)
                val pty = PtyBridge.create()
                val session = UsbSerialSession(driver, connection, stableId, pty) { error ->
                    publishStatus("USB bridge error for $stableId: ${error.message}")
                }
                session.start()
                sessions += session

                val pipeProbe = hostprobe.callAttr("probe_pipe_open", pty.slavePath).toString()
                val identifyProbe = if (UsbDeviceScanner.isLikelyKlipper(device)) {
                    hostprobe.callAttr(
                        "probe_mcu_identify",
                        pty.slavePath,
                        helper.absolutePath,
                        115200
                    ).toString()
                } else {
                    "Klipper identify skipped: unknown serial device"
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

        val summary = if (statusLines.isEmpty()) {
            "No supported USB serial devices with permission."
        } else {
            "AndroidKlipper host test\n\n" + statusLines.joinToString("\n\n")
        }
        publishStatus(summary)
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

    override fun onDestroy() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        hostExecutor.shutdownNow()
        statusServer.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "dev.nozzlenaut.androidklipper.STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "klipper_host"
        private const val NOTIFICATION_ID = 7714

        fun start(context: Context) {
            val intent = Intent(context, KlipperHostService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
