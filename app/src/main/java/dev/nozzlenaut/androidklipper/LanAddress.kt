package dev.nozzlenaut.androidklipper

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the device's LAN address (and prefix length) and sanitises host names
 * that end up in generated JSON. Android-free apart from java.net, so the
 * selection and validation rules are unit-testable.
 */
object LanAddress {
    data class Addr(val ip: String, val prefix: Int)

    data class Iface(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val addrs: List<Addr>
    )

    // Cellular and tunnel interfaces often carry private (CGNAT) 10.x addresses.
    // Picking one of those would advertise an address no LAN client can reach.
    private val skippedPrefixes = listOf(
        "rmnet", "ccmni", "v4-rmnet", "v6-rmnet", "tun", "ppp", "dummy", "ifb", "p2p", "wigig"
    )
    private val wifiPrefixes = listOf("wlan", "swlan", "ap", "softap")
    private val wiredPrefixes = listOf("eth", "en", "usb", "rndis")

    /** The best private IPv4 LAN address: Wi-Fi / hotspot first, then wired, then anything else. */
    fun pick(ifaces: List<Iface>): Addr? =
        ifaces.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { iface -> skippedPrefixes.none { iface.name.startsWith(it) } }
            .flatMap { iface -> iface.addrs.asSequence().map { iface to it } }
            .filter { (_, addr) -> isPrivateIpv4(addr.ip) }
            .withIndex()
            .minWithOrNull(compareBy({ rank(it.value.first.name) }, { it.index }))
            ?.value?.second

    /** "ip/prefix" for the current LAN, or null when there is none. */
    fun hint(): String? = current()?.let { "${it.ip}/${it.prefix}" }

    fun current(): Addr? = runCatching {
        pick(
            NetworkInterface.getNetworkInterfaces().toList().map { ni ->
                Iface(
                    name = ni.name,
                    isUp = ni.isUp,
                    isLoopback = ni.isLoopback,
                    addrs = ni.interfaceAddresses
                        .filter { it.address is Inet4Address }
                        .mapNotNull { ia ->
                            ia.address.hostAddress?.let { Addr(it, ia.networkPrefixLength.toInt()) }
                        }
                )
            }
        )
    }.getOrNull()

    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168)
    }

    /**
     * Extracts a safe host (no port) from an HTTP Host header, or null.
     * The result is written into /config.json, so anything that is not a plain
     * host name, IPv4 address or bracketed IPv6 literal is rejected.
     */
    fun sanitizeHost(hostHeader: String?): String? {
        val raw = hostHeader?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 260) return null
        val host = if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end < 0) return null
            val literal = raw.substring(0, end + 1)
            if (!ipv6Literal.matches(literal)) return null
            return literal
        } else {
            raw.substringBefore(':')
        }
        return host.takeIf { hostName.matches(it) }
    }

    private val hostName = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val ipv6Literal = Regex("^\\[[0-9A-Fa-f:.]{2,45}]$")

    private fun rank(name: String): Int = when {
        wifiPrefixes.any { name.startsWith(it) } -> 0
        wiredPrefixes.any { name.startsWith(it) } -> 1
        else -> 2
    }
}
