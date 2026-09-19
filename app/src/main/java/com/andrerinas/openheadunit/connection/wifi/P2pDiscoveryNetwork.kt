package com.andrerinas.openheadunit.connection.wifi

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class P2pDiscoveryNetwork(
    val interfaceName: String,
    val localIpv4: String,
    val prefixLength: Int,
    val hasClient: Boolean,
) {
    fun hosts(): Sequence<String> {
        if (!hasClient || prefixLength !in 16..30) return emptySequence()
        val octets = localIpv4.split('.').map { it.toIntOrNull() ?: return emptySequence() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return emptySequence()
        val local = octets.fold(0L) { value, part -> (value shl 8) or part.toLong() }
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        val first = (local and mask) + 1
        val last = (local or (mask xor 0xffffffffL)) - 1
        return (first..last).asSequence().filter { it != local }.map { address ->
            (3 downTo 0).joinToString(".") { ((address shr (it * 8)) and 255).toString() }
        }
    }

    companion object {
        data class InterfaceAddress(val interfaceName: String, val ipv4: String, val prefixLength: Int)

        fun select(groupInterface: String?, ownerIpv4: String?, hasClient: Boolean,
                   addresses: List<InterfaceAddress>): P2pDiscoveryNetwork? {
            if (ownerIpv4 == null) return null
            // Some OEMs hide WifiP2pGroup.interface. Only an unambiguous P2P interface
            // carrying the actual group-owner IP may substitute, never station Wi-Fi.
            val address = addresses.filter {
                it.ipv4 == ownerIpv4 && if (groupInterface.isNullOrBlank())
                    (it.interfaceName.contains("p2p", ignoreCase = true) || it.interfaceName.startsWith("swlan"))
                else it.interfaceName == groupInterface
            }.singleOrNull() ?: return null
            return P2pDiscoveryNetwork(address.interfaceName, address.ipv4, address.prefixLength, hasClient)
        }

        fun fromGroup(interfaceName: String?, ownerIpv4: String?, hasClient: Boolean): P2pDiscoveryNetwork? = runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val addresses = interfaces.filter { it.isUp && !it.isLoopback }.flatMap { nic ->
                nic.interfaceAddresses.filter { it.address is Inet4Address }.mapNotNull { address ->
                    address.address.hostAddress?.let { InterfaceAddress(nic.name, it, address.networkPrefixLength.toInt()) }
                }
            }
            select(interfaceName, ownerIpv4, hasClient, addresses)
        }.getOrNull()
    }
}
