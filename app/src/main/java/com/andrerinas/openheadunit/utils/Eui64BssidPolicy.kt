package com.andrerinas.openheadunit.utils

/**
 * Recovers an interface's MAC from its IPv6 link-local address.
 *
 * `getHardwareAddress()` has been masked for ordinary apps since Android 6.0, but
 * `getInetAddresses()` never was, and where the kernel built a link-local address by the EUI-64
 * rule that address contains the MAC. It is the one access-point address an unrooted phone can
 * still read, which is what a phone standing in for a head unit needs to describe its own network.
 *
 * Pure: no Android types, so the derivation is tested rather than assumed.
 */
object Eui64BssidPolicy {

    /** One interface, and every IPv6 link-local address on it as raw 16-byte material. */
    data class Candidate(val name: String, val linkLocalIpv6: List<ByteArray>)

    /** Which interface answered, and with what. */
    data class Match(val iface: String, val mac: String)

    /**
     * The MAC encoded in [address], or null where the address was not built by the EUI-64 rule.
     *
     * Bytes 11 and 12 must carry the `ff:fe` marker, which is what makes this self-validating: an
     * interface using RFC 7217 stable-privacy addressing fails the test and yields nothing rather
     * than a fabricated address. Bit 1 of byte 8 is the flipped U/L bit, which the xor undoes.
     */
    fun fromLinkLocal(address: ByteArray?): String? {
        if (address == null || address.size != 16) return null
        if (address[11].toInt() and 0xFF != 0xFF) return null
        if (address[12].toInt() and 0xFF != 0xFE) return null
        val octets = intArrayOf(
            (address[8].toInt() and 0xFF) xor 0x02,
            address[9].toInt() and 0xFF,
            address[10].toInt() and 0xFF,
            address[13].toInt() and 0xFF,
            address[14].toInt() and 0xFF,
            address[15].toInt() and 0xFF
        )
        return octets.joinToString(":") { String.format("%02X", it) }
    }

    /**
     * The first of [addresses] that yields a MAC. Every one is tried rather than only the first,
     * because a kernel may carry a stable-privacy address alongside the EUI-64 one and stopping at
     * the first would return nothing on exactly the hardware where this works.
     */
    fun fromLinkLocals(addresses: List<ByteArray>): String? =
        addresses.firstNotNullOfOrNull { fromLinkLocal(it) }

    /**
     * Whether [name] is an access point or P2P interface.
     *
     * Tighter than the other name filters in the BSSID chain, which accept any "wlan": `wlan0` is
     * the station interface, so its address describes the network this device has joined rather
     * than the one it is offering, and a MAC-shaped wrong answer would outrank every later rung.
     */
    fun looksLikeApOrP2p(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.contains("p2p") || lower.startsWith("ap") ||
            lower.startsWith("swlan") || lower == "wlan1"
    }

    /**
     * [preferred] first, then any candidate whose name passes [nameFilter].
     *
     * The filter is the caller's because the routes differ: a WiFi Direct group's address can only
     * come off a P2P interface, while the hotspot route legitimately reads `ap0` and `swlan0`.
     */
    fun choose(
        candidates: List<Candidate>,
        preferred: String?,
        nameFilter: (String?) -> Boolean = ::looksLikeApOrP2p,
    ): Match? {
        if (!preferred.isNullOrEmpty()) {
            val named = candidates.firstOrNull { it.name == preferred }
            val mac = named?.let { fromLinkLocals(it.linkLocalIpv6) }
            if (named != null && mac != null) return Match(named.name, mac)
        }
        for (candidate in candidates) {
            if (!nameFilter(candidate.name)) continue
            val mac = fromLinkLocals(candidate.linkLocalIpv6) ?: continue
            return Match(candidate.name, mac)
        }
        return null
    }
}
