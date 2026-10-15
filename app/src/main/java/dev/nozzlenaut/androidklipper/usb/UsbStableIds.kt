package dev.nozzlenaut.androidklipper.usb

/**
 * Decides which stable ID a USB MCU gets, and refuses to let two devices share one.
 *
 * Klipper's config names MCUs by USB serial. Some Android builds (Fire OS)
 * occasionally stop exposing the serial after a CDC device is reopened, so a
 * previously seen serial is remembered per USB device path. That memory is only
 * trustworthy while the device keeps the same path in the same USB enumeration:
 *
 * - entries are tagged with an epoch (the boot count) so a reboot invalidates them;
 * - a detach event removes the entry (the path may be reused by another board);
 * - two devices ending up with the same ID is always an error, because that is
 *   exactly what a stale entry looks like when two identical boards are attached.
 */
class UsbStableIds(
    private val store: Store,
    private val epoch: String
) {
    interface Store {
        fun get(deviceName: String): String?
        fun put(deviceName: String, value: String)
        fun remove(deviceName: String)
    }

    enum class Source { SERIAL, CACHED, PATH_FALLBACK }

    data class Candidate(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val serial: String?
    )

    data class Resolution(val deviceName: String, val id: String, val source: Source)

    fun resolve(candidate: Candidate): Resolution {
        val serial = candidate.serial?.takeIf { it.isNotBlank() }
        if (serial != null) {
            store.put(candidate.deviceName, encode(serial))
            return Resolution(candidate.deviceName, serial, Source.SERIAL)
        }
        val cached = store.get(candidate.deviceName)?.let { decode(it) }
        if (cached != null) {
            return Resolution(candidate.deviceName, cached, Source.CACHED)
        }
        val fallback = "%04x:%04x:%s".format(candidate.vendorId, candidate.productId, candidate.deviceName)
        return Resolution(candidate.deviceName, fallback, Source.PATH_FALLBACK)
    }

    /** Call when Android reports the device at [deviceName] was unplugged. */
    fun forget(deviceName: String) = store.remove(deviceName)

    private fun encode(serial: String) = "$epoch|$serial"

    /** Returns the serial only when it was stored in the current epoch. */
    private fun decode(value: String): String? {
        val sep = value.indexOf('|')
        if (sep <= 0) return null // legacy bare-serial entry: path validity unknown
        if (value.substring(0, sep) != epoch) return null
        return value.substring(sep + 1).takeIf { it.isNotBlank() }
    }

    companion object {
        /** IDs claimed by more than one device, with the device paths involved. */
        fun duplicates(resolutions: Collection<Resolution>): Map<String, List<String>> =
            resolutions.groupBy { it.id }
                .filterValues { it.size > 1 }
                .mapValues { (_, list) -> list.map { it.deviceName } }

        /** Fallback IDs are path-derived, so they cannot match any serial in the user's config. */
        fun needsMapping(resolution: Resolution) = resolution.source == Source.PATH_FALLBACK
    }
}
