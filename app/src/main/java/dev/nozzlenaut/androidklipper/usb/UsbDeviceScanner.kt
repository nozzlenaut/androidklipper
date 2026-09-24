package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Finds USB serial devices AndroidKlipper can actually talk to.
 *
 * Klipper's native USB firmware normally shows up with the VID/PID below. We
 * register that pair explicitly because it is the main path we care about,
 * then fall back to usb-serial-for-android's normal device detection for other
 * serial adapters.
 *
 * Important: VID/PID tells us what kind of device this probably is. It does
 * NOT uniquely identify one MCU. A multi-MCU printer can have several boards
 * with the exact same VID/PID, so the host service still has to use each
 * device's USB serial number when it builds the stable printer mapping.
 */
object UsbDeviceScanner {
    const val KLIPPER_VID = 0x1d50
    const val KLIPPER_PID = 0x614e

    // Give native Klipper USB devices a known CDC-ACM driver before asking the
    // generic prober to guess. This keeps the common path predictable.
    private val klipperProber: UsbSerialProber by lazy {
        val table = ProbeTable()
        table.addProduct(KLIPPER_VID, KLIPPER_PID, CdcAcmSerialDriver::class.java)
        UsbSerialProber(table)
    }

    fun isLikelyKlipper(device: UsbDevice): Boolean =
        device.vendorId == KLIPPER_VID && device.productId == KLIPPER_PID

    /** Return a usable serial driver, or null if this USB device is irrelevant. */
    fun probe(device: UsbDevice): UsbSerialDriver? =
        klipperProber.probeDevice(device) ?: UsbSerialProber.getDefaultProber().probeDevice(device)

    fun isSupported(device: UsbDevice): Boolean = probe(device) != null

    /**
     * Produce the boring-but-useful device list shown by the diagnostic UI.
     * This does not open anything or move the printer; it only reports what
     * Android can currently see and whether permission has been granted.
     */
    fun describeDevices(manager: UsbManager): List<UsbDeviceDescriptor> =
        manager.deviceList.values.map { device ->
            val driver = probe(device)
            UsbDeviceDescriptor(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                productName = device.productName,
                hasPermission = manager.hasPermission(device),
                driverName = driver?.javaClass?.simpleName
            )
        }.sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceName }))
}
