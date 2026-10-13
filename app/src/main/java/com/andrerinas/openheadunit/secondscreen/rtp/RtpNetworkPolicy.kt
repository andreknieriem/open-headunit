package com.andrerinas.openheadunit.secondscreen.rtp

/** Which network an RTP socket binds to: the one whose subnet holds the destination, never a VPN. */
object RtpNetworkPolicy {

    class LinkAddress(val address: ByteArray, val prefixLength: Int)

    data class Candidate(
        val index: Int,
        val name: String,
        val isVpn: Boolean,
        val addresses: List<LinkAddress>,
    )

    /** The non-VPN candidate with the longest prefix holding [destination], or null. */
    fun choose(destination: ByteArray, candidates: List<Candidate>): Candidate? {
        if (destination.size != 4 || destination[0].toInt() == 127) return null
        var best: Candidate? = null
        var bestPrefix = -1
        for (c in candidates) {
            if (c.isVpn) continue
            for (a in c.addresses) {
                if (a.address.size == 4 && a.prefixLength > bestPrefix && holds(a, destination)) {
                    best = c
                    bestPrefix = a.prefixLength
                }
            }
        }
        return best
    }

    private fun holds(link: LinkAddress, destination: ByteArray): Boolean {
        var bits = link.prefixLength.coerceIn(0, 32)
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((link.address[i].toInt() and mask) != (destination[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }
}
