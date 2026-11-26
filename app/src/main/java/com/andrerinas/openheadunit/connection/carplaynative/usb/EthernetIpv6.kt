package com.andrerinas.openheadunit.connection.carplaynative.usb

/**
 * Untagged Ethernet II framing around IPv6 packets, the shape NCM datagrams carry.
 * Ported from DiPlay (https://github.com/shihabal3amri/DiPlay) `transport/EthernetIpv6Codec.kt`, a fork of xcertplay (https://github.com/shilapi/xcertplay).
 */
object EthernetIpv6 {
    const val ETHERTYPE_IPV6 = 0x86dd
    const val MAC_BYTES = 6

    private const val HEADER_BYTES = 14

    data class Ipv6Frame(
        val sourceMac: ByteArray,
        val destinationMac: ByteArray,
        val ipv6: ByteArray,
    )

    data class Ipv6FrameView(
        val sourceMac: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    /** Returns null for tagged frames, non-IPv6 frames, or truncated input. */
    fun parseIpv6(frame: ByteArray): Ipv6Frame? {
        if (frame.size < HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6Frame(
            sourceMac = frame.copyOfRange(6, 12),
            destinationMac = frame.copyOfRange(0, 6),
            ipv6 = frame.copyOfRange(HEADER_BYTES, frame.size),
        )
    }

    /** Returns an allocation-light view for callers that can consume the payload in place. */
    fun parseIpv6View(frame: ByteArray): Ipv6FrameView? {
        if (frame.size <= HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6FrameView(
            sourceMac = frame.copyOfRange(6, 12),
            payloadOffset = HEADER_BYTES,
            payloadLength = frame.size - HEADER_BYTES,
        )
    }

    fun build(sourceMac: ByteArray, destinationMac: ByteArray, ipv6: ByteArray): ByteArray {
        require(sourceMac.size == MAC_BYTES) { "sourceMac must be $MAC_BYTES bytes" }
        require(destinationMac.size == MAC_BYTES) { "destinationMac must be $MAC_BYTES bytes" }
        require(ipv6.isNotEmpty()) { "ipv6 payload must not be empty" }

        val frame = ByteArray(HEADER_BYTES + ipv6.size)
        destinationMac.copyInto(frame, 0)
        sourceMac.copyInto(frame, 6)
        putU16(frame, 12, ETHERTYPE_IPV6)
        ipv6.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /** Maps an IPv6 multicast destination to its Ethernet 33:33:xx:xx:xx:xx address. */
    fun multicastDestinationMac(ipv6: ByteArray): ByteArray? {
        if (ipv6.size < IPV6_HEADER_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return null
        if ((ipv6[IPV6_DESTINATION_OFFSET].toInt() and 0xff) != 0xff) return null
        return byteArrayOf(
            0x33,
            0x33,
            ipv6[IPV6_DESTINATION_OFFSET + 12],
            ipv6[IPV6_DESTINATION_OFFSET + 13],
            ipv6[IPV6_DESTINATION_OFFSET + 14],
            ipv6[IPV6_DESTINATION_OFFSET + 15],
        )
    }

    /**
     * A TUN interface has no layer-2 address, so Android emits Neighbor Advertisements without a
     * Target Link-Layer Address option. Add the option required by the Ethernet peer and repair
     * the IPv6 payload length and ICMPv6 checksum.
     */
    fun addNeighborAdvertisementTargetMac(ipv6: ByteArray, hostMac: ByteArray): ByteArray {
        require(hostMac.size == MAC_BYTES) { "hostMac must be $MAC_BYTES bytes" }
        if (ipv6.size < ICMPV6_NA_MIN_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return ipv6
        if ((ipv6[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != ICMPV6_NEXT_HEADER ||
            (ipv6[IPV6_HEADER_BYTES].toInt() and 0xff) != ICMPV6_NEIGHBOR_ADVERTISEMENT
        ) return ipv6

        val payloadBytes = readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET)
        if (payloadBytes < ICMPV6_NA_BYTES || IPV6_HEADER_BYTES + payloadBytes > ipv6.size) return ipv6
        var optionOffset = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
        val payloadEnd = IPV6_HEADER_BYTES + payloadBytes
        while (optionOffset + 2 <= payloadEnd) {
            val optionBytes = (ipv6[optionOffset + 1].toInt() and 0xff) * 8
            if (optionBytes == 0 || optionOffset + optionBytes > payloadEnd) break
            if ((ipv6[optionOffset].toInt() and 0xff) == ICMPV6_TARGET_LINK_LAYER_OPTION &&
                optionBytes >= 8
            ) {
                return ipv6.copyOf().also { result ->
                    hostMac.copyInto(result, optionOffset + 2)
                    writeIcmpv6Checksum(result)
                }
            }
            optionOffset += optionBytes
        }

        // The IPv6 payload length is 16 bits. Appending the 8-byte option must not wrap it to zero.
        if (payloadBytes > 0xffff - 8) return ipv6
        val result = ipv6.copyOf(payloadEnd + 8)
        result[IPV6_PAYLOAD_LENGTH_OFFSET] = ((payloadBytes + 8) ushr 8).toByte()
        result[IPV6_PAYLOAD_LENGTH_OFFSET + 1] = (payloadBytes + 8).toByte()
        result[payloadEnd] = ICMPV6_TARGET_LINK_LAYER_OPTION.toByte()
        result[payloadEnd + 1] = 1
        hostMac.copyInto(result, payloadEnd + 2)
        writeIcmpv6Checksum(result)
        return result
    }

    private fun writeIcmpv6Checksum(ipv6: ByteArray) {
        ipv6[IPV6_HEADER_BYTES + 2] = 0
        ipv6[IPV6_HEADER_BYTES + 3] = 0
        val checksum = upperLayerChecksum(ipv6, ICMPV6_NEXT_HEADER, IPV6_HEADER_BYTES, readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET))
        ipv6[IPV6_HEADER_BYTES + 2] = (checksum ushr 8).toByte()
        ipv6[IPV6_HEADER_BYTES + 3] = checksum.toByte()
    }

    /** The RFC 8200 pseudo-header checksum of [length] bytes at [start]. The checksum field in them must be zero. */
    fun upperLayerChecksum(ipv6: ByteArray, nextHeader: Int, start: Int, length: Int): Int {
        var sum = 0L
        fun addWords(from: Int, bytes: Int) {
            var offset = from
            val end = from + bytes
            while (offset + 1 < end) {
                sum += readU16(ipv6, offset).toLong()
                offset += 2
            }
            if (offset < end) sum += (ipv6[offset].toInt() and 0xff).toLong() shl 8
        }
        addWords(IPV6_SOURCE_OFFSET, 32)
        sum += (length ushr 16).toLong()
        sum += (length and 0xffff).toLong()
        sum += nextHeader.toLong()
        addWords(start, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val IPV6_HEADER_BYTES = 40
    const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
    const val IPV6_NEXT_HEADER_OFFSET = 6
    const val IPV6_SOURCE_OFFSET = 8
    const val IPV6_DESTINATION_OFFSET = 24
    private const val ICMPV6_NEXT_HEADER = 58
    private const val ICMPV6_NEIGHBOR_ADVERTISEMENT = 136
    private const val ICMPV6_NA_BYTES = 24
    private const val ICMPV6_NA_MIN_BYTES = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
    private const val ICMPV6_TARGET_LINK_LAYER_OPTION = 2
}
