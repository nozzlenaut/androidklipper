package dev.nozzlenaut.androidklipper.net

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class BridgeMcu(
    val serial: String,
    val port: Int
)

data class BridgeDescriptor(
    val bridgeId: String,
    val firmware: String,
    val address: InetAddress,
    val mcus: List<BridgeMcu>
)

/**
 * UDP discovery for AndroidKlipper Bridge devices.
 *
 * Request:  AKDISCOVER/1 -> UDP 7130
 * Reply:    JSON described in bridge/PROTOCOL.md
 */
object BridgeDiscovery {
    private const val DISCOVERY_PORT = 7130
    private val request = "AKDISCOVER/1".toByteArray(Charsets.US_ASCII)

    fun discover(timeoutMs: Int = 1200): List<BridgeDescriptor> {
        val found = linkedMapOf<String, BridgeDescriptor>()

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 150

            broadcastTargets().forEach { target ->
                runCatching {
                    socket.send(
                        DatagramPacket(
                            request,
                            request.size,
                            target,
                            DISCOVERY_PORT
                        )
                    )
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)

            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    parseReply(
                        text = String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            Charsets.UTF_8
                        ),
                        address = packet.address
                    )?.let { descriptor ->
                        found["${descriptor.bridgeId}@${descriptor.address.hostAddress}"] =
                            descriptor
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep receiving until the overall deadline.
                }
            }
        }

        return found.values.toList()
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")

        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .filterIsInstance<Inet4Address>()
                .forEach { targets += it }
        }

        return targets
    }

    private fun parseReply(text: String, address: InetAddress): BridgeDescriptor? {
        return runCatching {
            val json = JSONObject(text)
            if (json.optString("type") != "androidklipper-bridge") return null
            if (json.optInt("protocol") != 1) return null

            val mcusJson = json.getJSONArray("mcus")
            val mcus = buildList {
                for (i in 0 until mcusJson.length()) {
                    val item = mcusJson.getJSONObject(i)
                    val serial = item.getString("serial").trim()
                    val port = item.getInt("port")
                    if (serial.isNotEmpty() && port in 1..65535) {
                        add(BridgeMcu(serial, port))
                    }
                }
            }

            BridgeDescriptor(
                bridgeId = json.getString("bridge_id"),
                firmware = json.optString("firmware", "unknown"),
                address = address,
                mcus = mcus
            )
        }.getOrNull()
    }
}
