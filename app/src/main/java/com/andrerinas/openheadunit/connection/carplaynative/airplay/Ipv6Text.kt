package com.andrerinas.openheadunit.connection.carplaynative.airplay

import java.net.Inet6Address
import java.net.InetAddress

/** An address as a log line shows it: the short IPv6 form, lower case, no scope. */
object Ipv6Text {
    fun of(address: InetAddress): String = if (address is Inet6Address) format(address.address) else address.hostAddress ?: "?"

    fun format(bytes: ByteArray): String {
        require(bytes.size == 16) { "an IPv6 address has 16 bytes" }
        val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLength) { bestStart = i; bestLength = j - i }
            i = j
        }
        if (bestLength < 2) return groups.joinToString(":") { Integer.toHexString(it) }
        val head = groups.take(bestStart).joinToString(":") { Integer.toHexString(it) }
        val tail = groups.drop(bestStart + bestLength).joinToString(":") { Integer.toHexString(it) }
        return "$head::$tail"
    }
}
