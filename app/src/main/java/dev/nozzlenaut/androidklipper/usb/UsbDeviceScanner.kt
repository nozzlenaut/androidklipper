package dev.nozzlenaut.androidklipper.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

object UsbDeviceScanner {
    const val KLIPPER_VID = 0x1d50
    const val KLIPPER_PID = 0x614e

    private val klipperProber: UsbSerialProber by lazy {
        val table = ProbeTable()
        table.addProduct(KLIPPER_VID, KLIPPER_PID, CdcAcmSerialDriver::class.java)
        UsbSerialProber(table)
    }

    fun isLikelyKlipper(device: UsbDevice): Boolean =
        device.vendorId == KLIPPER_VID && device.productId == KLIPPER_PID

    fun probe(device: UsbDevice): UsbSerialDriver? =
        klipperProber.probeDevice(device) ?: UsbSerialProber.getDefaultProber().probeDevice(device)

    fun isSupported(device: UsbDevice): Boolean = probe(device) != null

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
