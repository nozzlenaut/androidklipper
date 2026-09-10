package dev.nozzlenaut.androidklipper.usb

data class UsbDeviceDescriptor(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val hasPermission: Boolean,
    val driverName: String?
)
