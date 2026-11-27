package com.andrerinas.openheadunit.connection.carplaynative.usb

/** The tunnel check's packets: a TCP SYN from fe80::1 into the tunnel and the SYN-ACK that must come back from fe80::2. */
object TunProbePacket {
    const val SOURCE_PORT = 40000
    const val INITIAL_SEQUENCE = 0x01020304

    private const val TCP_HEADER_BYTES = 20
    private const val NEXT_HEADER_TCP = 6
    private const val HOP_LIMIT = 64
    private const val FLAG_SYN = 0x02
    private const val FLAG_ACK = 0x10
    private const val FLAG_RST = 0x04
    private const val WINDOW = 0xffff

    /** The tunnel's own address, which the AirPlay listener answers on. */
    val TUN_ADDRESS: ByteArray get() = linkLocal(2)

    /** The address the probe pretends to come from. The iPhone uses it too. */
    val PEER_ADDRESS: ByteArray get() = linkLocal(1)

    private fun linkLocal(last: Int) = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = last.toByte() }

    fun syn(port: Int): ByteArray {
        require(port in 1..0xffff) { "port must be a TCP port" }
        val packet = ByteArray(EthernetIpv6.IPV6_HEADER_BYTES + TCP_HEADER_BYTES)
        packet[0] = 0x60
        packet[EthernetIpv6.IPV6_PAYLOAD_LENGTH_OFFSET + 1] = TCP_HEADER_BYTES.toByte()
        packet[EthernetIpv6.IPV6_NEXT_HEADER_OFFSET] = NEXT_HEADER_TCP.toByte()
        packet[EthernetIpv6.IPV6_NEXT_HEADER_OFFSET + 1] = HOP_LIMIT.toByte()
        PEER_ADDRESS.copyInto(packet, EthernetIpv6.IPV6_SOURCE_OFFSET)
        TUN_ADDRESS.copyInto(packet, EthernetIpv6.IPV6_DESTINATION_OFFSET)
        val tcp = EthernetIpv6.IPV6_HEADER_BYTES
        putU16(packet, tcp, SOURCE_PORT)
        putU16(packet, tcp + 2, port)
        putU32(packet, tcp + 4, INITIAL_SEQUENCE)
        packet[tcp + 12] = (TCP_HEADER_BYTES / 4 shl 4).toByte()
        packet[tcp + 13] = FLAG_SYN.toByte()
        putU16(packet, tcp + 14, WINDOW)
        putU16(packet, tcp + 16, EthernetIpv6.upperLayerChecksum(packet, NEXT_HEADER_TCP, tcp, TCP_HEADER_BYTES))
        return packet
    }

    /** True for a valid SYN-ACK of the probe SYN from fe80::2 port [port] to fe80::1. */
    fun isSynAck(packet: ByteArray, port: Int): Boolean {
        val tcp = EthernetIpv6.IPV6_HEADER_BYTES
        if (packet.size < tcp + TCP_HEADER_BYTES || (packet[0].toInt() and 0xf0) != 0x60) return false
        if ((packet[EthernetIpv6.IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != NEXT_HEADER_TCP) return false
        val length = readU16(packet, EthernetIpv6.IPV6_PAYLOAD_LENGTH_OFFSET)
        if (length < TCP_HEADER_BYTES || tcp + length > packet.size) return false
        if (!packet.copyOfRange(EthernetIpv6.IPV6_SOURCE_OFFSET, EthernetIpv6.IPV6_SOURCE_OFFSET + 16).contentEquals(TUN_ADDRESS)) return false
        if (!packet.copyOfRange(EthernetIpv6.IPV6_DESTINATION_OFFSET, EthernetIpv6.IPV6_DESTINATION_OFFSET + 16).contentEquals(PEER_ADDRESS)) return false
        if (readU16(packet, tcp) != port || readU16(packet, tcp + 2) != SOURCE_PORT) return false
        val flags = packet[tcp + 13].toInt() and 0xff
        if (flags and (FLAG_SYN or FLAG_ACK) != (FLAG_SYN or FLAG_ACK) || flags and FLAG_RST != 0) return false
        if (readU32(packet, tcp + 8) != INITIAL_SEQUENCE + 1) return false
        // A segment that includes its own correct checksum sums to zero when the checksum is read as data.
        return EthernetIpv6.upperLayerChecksum(packet, NEXT_HEADER_TCP, tcp, length) == 0
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) target[offset + i] = (value ushr (24 - i * 8)).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (readU16(source, offset) shl 16) or readU16(source, offset + 2)
}
