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
import dev.nozzlenaut.androidklipper.config.ConfigRuntimePreparer
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

    @Volatile
    private var klippyThread: Thread? = null

    @Volatile
    private var klippyThreadError: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Starting AndroidKlipper…"))
        hostExecutor.execute { rebuildSessionsAndStart() }
        return START_STICKY
    }

    private fun rebuildSessionsAndStart() {
        stopKlippy()
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val python = Python.getInstance()
        val hostprobe = python.getModule("hostprobe")
        val helper = File(applicationInfo.nativeLibraryDir, "libklipper_c_helper.so")
        val statusLines = mutableListOf<String>()

        try {
            statusLines += hostprobe.callAttr("probe_klipper_import").toString()
            statusLines += hostprobe.callAttr("probe_c_helper", helper.absolutePath).toString()
        } catch (t: Throwable) {
            publishStatus("Host runtime error: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        val supported = usbManager.deviceList.values.mapNotNull { device ->
            val driver = UsbDeviceScanner.probe(device) ?: return@mapNotNull null
            if (!usbManager.hasPermission(device)) {
                statusLines += "${device.productName ?: device.deviceName}: waiting for USB permission"
                return@mapNotNull null
            }
            device to driver
        }

        if (supported.isEmpty()) {
            publishStatus("No supported USB serial devices with permission.")
            return
        }

        val usbSerialToPty = linkedMapOf<String, String>()
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
                usbSerialToPty[stableId] = pty.slavePath

                statusLines += "USB MCU ${index + 1}: $stableId -> ${pty.slavePath}"
            } catch (t: Throwable) {
                publishStatus(
                    "${device.productName ?: device.deviceName}: ERROR ${t.javaClass.simpleName}: ${t.message}"
                )
                return
            }
        }

        val imported = File(filesDir, "printer_data/config-original/printer.cfg")
        if (!imported.isFile) {
            publishStatus(
                "USB bridge is ready, but no imported printer.cfg exists. Tap Migrate & Start first."
            )
            return
        }

        try {
            val prepared = ConfigRuntimePreparer.prepare(this, usbSerialToPty)
            val missingMappings = usbSerialToPty.keys - prepared.mappedUsbIds
            statusLines += "Mapped MCU USB IDs: ${prepared.mappedUsbIds.joinToString()}"
            if (missingMappings.isNotEmpty()) {
                statusLines += "Unreferenced USB serial devices: ${missingMappings.joinToString()}"
            }
            publishStatus(
                "Config migrated. Starting real Klippy…\n\n" +
                    statusLines.joinToString("\n")
            )
            startRealKlippy(prepared, helper)
        } catch (t: Throwable) {
            publishStatus(
                "Runtime config preparation failed: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    private fun startRealKlippy(
        prepared: ConfigRuntimePreparer.Prepared,
        helper: File
    ) {
        val dataDir = File(filesDir, "printer_data")
        val logFile = File(dataDir, "logs/klippy-android.log")
        val apiSocket = File(dataDir, "comms/klippy.sock")
        val inputTty = File(dataDir, "run/printer")
        logFile.parentFile?.mkdirs()
        apiSocket.parentFile?.mkdirs()
        inputTty.parentFile?.mkdirs()
        logFile.delete()
        apiSocket.delete()
        inputTty.delete()
        klippyThreadError = null

        val runner = Python.getInstance().getModule("klipper_runner")
        klippyThread = Thread({
            try {
                runner.callAttr(
                    "run",
                    prepared.configFile.absolutePath,
                    apiSocket.absolutePath,
                    logFile.absolutePath,
                    inputTty.absolutePath,
                    helper.absolutePath
                )
            } catch (t: Throwable) {
                klippyThreadError = "${t.javaClass.simpleName}: ${t.message}"
            }
        }, "klippy-runtime").apply {
            isDaemon = true
            start()
        }

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val logText = runCatching {
                if (logFile.isFile) logFile.readText() else ""
            }.getOrDefault("")

            if (logText.contains("Printer is ready")) {
                publishStatus(
                    buildString {
                        append("PRINTER IS READY ✅\n\n")
                        append("Real Klippy is running on the Fire tablet.\n")
                        append("Config source preserved in config-original.\n")
                        append("Mapped ${prepared.mappedUsbIds.size} MCU USB IDs.\n\n")
                        append("This build intentionally exposes no motion/heater controls yet.")
                    }
                )
                return
            }

            val thread = klippyThread
            if (thread == null || !thread.isAlive) break
            Thread.sleep(250)
        }

        val tail = tailLog(logFile, 55)
        val threadError = klippyThreadError
        publishStatus(
            buildString {
                append("Klippy did not reach ready.\n")
                if (!threadError.isNullOrBlank()) append("Runtime: $threadError\n")
                append("\nLast Klippy log lines:\n")
                append(tail.ifBlank { "(log empty)" })
            }
        )
    }

    private fun tailLog(file: File, lineCount: Int): String {
        if (!file.isFile) return ""
        return runCatching {
            file.readLines().takeLast(lineCount).joinToString("\n")
        }.getOrDefault("")
    }

    private fun stopKlippy() {
        if (Python.isStarted()) {
            runCatching {
                Python.getInstance().getModule("klipper_runner").callAttr("stop")
            }
        }
        runCatching { klippyThread?.join(1200) }
        klippyThread = null
        klippyThreadError = null
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
        stopKlippy()
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        hostExecutor.shutdownNow()
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
