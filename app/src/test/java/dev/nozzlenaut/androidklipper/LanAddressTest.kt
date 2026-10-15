package dev.nozzlenaut.androidklipper

import dev.nozzlenaut.androidklipper.LanAddress.Addr
import dev.nozzlenaut.androidklipper.LanAddress.Iface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    private fun iface(name: String, vararg addrs: Addr, up: Boolean = true, lo: Boolean = false) =
        Iface(name, up, lo, addrs.toList())

    @Test
    fun cellularCgnatAddressIsNeverPickedEvenWhenListedFirst() {
        // The previous "first private address" logic returned 10.x from rmnet here.
        val picked = LanAddress.pick(
            listOf(
                iface("rmnet_data0", Addr("10.52.8.14", 30)),
                iface("wlan0", Addr("192.168.1.50", 24))
            )
        )
        assertEquals(Addr("192.168.1.50", 24), picked)
    }

    @Test
    fun wifiBeatsWiredBeatsOthers() {
        val wifi = iface("wlan0", Addr("192.168.1.50", 24))
        val wired = iface("eth0", Addr("10.0.0.5", 8))
        val other = iface("bt-pan", Addr("172.16.0.2", 16))
        assertEquals(wifi.addrs[0], LanAddress.pick(listOf(other, wired, wifi)))
        assertEquals(wired.addrs[0], LanAddress.pick(listOf(other, wired)))
        assertEquals(other.addrs[0], LanAddress.pick(listOf(other)))
    }

    @Test
    fun hotspotInterfaceCountsAsWifi() {
        val picked = LanAddress.pick(
            listOf(iface("eth0", Addr("10.0.0.5", 24)), iface("ap0", Addr("192.168.43.1", 24)))
        )
        assertEquals("192.168.43.1", picked?.ip)
    }

    @Test
    fun skipsDownLoopbackPublicAndTunnelInterfaces() {
        val picked = LanAddress.pick(
            listOf(
                iface("lo", Addr("127.0.0.1", 8), lo = true),
                iface("wlan0", Addr("192.168.1.9", 24), up = false),
                iface("tun0", Addr("10.8.0.2", 24)),
                iface("wlan1", Addr("203.0.113.9", 24))
            )
        )
        assertNull(picked)
    }

    @Test
    fun keepsThePrefixLengthAndFirstMatchingAddress() {
        val picked = LanAddress.pick(
            listOf(iface("wlan0", Addr("169.254.3.3", 16), Addr("192.168.4.20", 26), Addr("192.168.5.5", 24)))
        )
        assertEquals(Addr("192.168.4.20", 26), picked)
    }

    @Test
    fun nothingToPickReturnsNull() {
        assertNull(LanAddress.pick(emptyList()))
    }

    @Test
    fun privateRanges() {
        for (ip in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.1", "192.168.0.1")) {
            assertTrue(ip, LanAddress.isPrivateIpv4(ip))
        }
        for (ip in listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "100.64.0.1", "300.1.1.1", "1.2.3", "a.b.c.d", "")) {
            assertFalse(ip, LanAddress.isPrivateIpv4(ip))
        }
    }

    @Test
    fun hostHeaderAcceptsPlainHostsAndStripsThePort() {
        assertEquals("192.168.1.50", LanAddress.sanitizeHost("192.168.1.50:8080"))
        assertEquals("androidklipper.local", LanAddress.sanitizeHost("androidklipper.local:8080"))
        assertEquals("printer", LanAddress.sanitizeHost("printer"))
        assertEquals("[fe80::1]", LanAddress.sanitizeHost("[fe80::1]:8080"))
        assertEquals("127.0.0.1", LanAddress.sanitizeHost("  127.0.0.1:8080 "))
    }

    @Test
    fun hostHeaderRejectsAnythingThatCouldBreakTheJson() {
        val bad = listOf(
            null,
            "",
            "   ",
            "evil\",\"port\":1,\"x\":\"",
            "a b",
            "<script>alert(1)</script>",
            "host\nX: y",
            "host\\u0022",
            "-leading.dash",
            "[not-ipv6",
            "[zz::1]",
            "a".repeat(300)
        )
        for (h in bad) assertNull("accepted: $h", LanAddress.sanitizeHost(h))
    }
}
