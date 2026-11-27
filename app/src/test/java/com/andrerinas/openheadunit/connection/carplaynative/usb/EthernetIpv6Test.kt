package com.andrerinas.openheadunit.connection.carplaynative.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EthernetIpv6Test {
    @Test
    fun `maps IPv6 multicast destination to Ethernet multicast`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        val destination = byteArrayOf(
            0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 1, 0xff.toByte(), 0, 0, 2,
        )
        destination.copyInto(packet, 24)

        assertArrayEquals(
            byteArrayOf(0x33, 0x33, 0xff.toByte(), 0, 0, 2),
            EthernetIpv6.multicastDestinationMac(packet),
        )
    }

    @Test
    fun `does not map IPv6 unicast destination`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        packet[24] = 0xfe.toByte()
        packet[25] = 0x80.toByte()

        assertNull(EthernetIpv6.multicastDestinationMac(packet))
    }

    @Test
    fun `rejects truncated or non IPv6 packets`() {
        assertNull(EthernetIpv6.multicastDestinationMac(ByteArray(39)))
        assertNull(EthernetIpv6.multicastDestinationMac(ByteArray(40)))
    }

    @Test
    fun `adds target link layer option to tun neighbor advertisement`() {
        val packet = ByteArray(64)
        packet[0] = 0x60
        packet[5] = 24
        packet[6] = 58
        byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
            .copyInto(packet, 8)
        byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3)
            .copyInto(packet, 24)
        packet[40] = 136.toByte()
        packet[44] = 0x60
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 2)

        val result = EthernetIpv6.addNeighborAdvertisementTargetMac(packet, mac)

        assertEquals(72, result.size)
        assertEquals(32, ((result[4].toInt() and 0xff) shl 8) or (result[5].toInt() and 0xff))
        assertEquals(2, result[64].toInt() and 0xff)
        assertEquals(1, result[65].toInt() and 0xff)
        assertArrayEquals(mac, result.copyOfRange(66, 72))
        assertEquals(0xffff, ipv6IcmpChecksumSum(result))
    }

@Test
    fun `does not wrap the IPv6 payload length when appending the option`() {
        val payloadBytes = 0xfff8
        val packet = ByteArray(40 + payloadBytes + 8)
        packet[0] = 0x60
        packet[4] = 0xff.toByte()
        packet[5] = 0xf8.toByte()
        packet[6] = 58
        packet[40] = 136.toByte()
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 2)

        assertSame(packet, EthernetIpv6.addNeighborAdvertisementTargetMac(packet, mac))
        assertEquals(payloadBytes, ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff))
    }

    @Test
    fun `the mDNS group ff02 fb maps to 33 33 00 00 00 fb`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        packet[24] = 0xff.toByte()
        packet[25] = 0x02
        packet[39] = 0xfb.toByte()
        assertArrayEquals(byteArrayOf(0x33, 0x33, 0, 0, 0, 0xfb.toByte()), EthernetIpv6.multicastDestinationMac(packet))
    }

    @Test
    fun `a frame is 14 header bytes then the packet, and parse reads it back`() {
        val packet = ByteArray(40).also { it[0] = 0x60 }
        val dst = byteArrayOf(0x36, 1, 2, 3, 4, 5)
        val src = byteArrayOf(0x02, 0, 0, 0, 0, 2)
        val frame = EthernetIpv6.build(src, dst, packet)
        assertEquals(54, frame.size)
        assertArrayEquals(dst, frame.copyOfRange(0, 6))
        assertArrayEquals(src, frame.copyOfRange(6, 12))
        assertEquals(0x86, frame[12].toInt() and 0xff)
        assertEquals(0xdd, frame[13].toInt() and 0xff)
        val parsed = EthernetIpv6.parseIpv6(frame)!!
        assertArrayEquals(src, parsed.sourceMac)
        assertArrayEquals(packet, parsed.ipv6)
    }

    @Test
    fun `an IPv4 frame is not parsed as IPv6`() {
        assertNull(EthernetIpv6.parseIpv6(ByteArray(60).also { it[12] = 0x08 }))
    }

    @Test
    fun `a neighbor advertisement that already has the option gets the host mac and a valid checksum`() {
        val packet = ByteArray(72)
        packet[0] = 0x60
        packet[5] = 32
        packet[6] = 58
        packet[40] = 136.toByte()
        packet[64] = 2
        packet[65] = 1
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 2)
        val result = EthernetIpv6.addNeighborAdvertisementTargetMac(packet, mac)
        assertEquals(72, result.size)
        assertArrayEquals(mac, result.copyOfRange(66, 72))
        assertEquals(0xffff, ipv6IcmpChecksumSum(result))
    }

    private fun ipv6IcmpChecksumSum(packet: ByteArray): Int {
        val payloadBytes = ((packet[4].toInt() and 0xff) shl 8) or (packet[5].toInt() and 0xff)
        var sum = 0L
        fun words(start: Int, bytes: Int) {
            var offset = start
            val end = start + bytes
            while (offset + 1 < end) {
                sum += (((packet[offset].toInt() and 0xff) shl 8) or
                    (packet[offset + 1].toInt() and 0xff)).toLong()
                offset += 2
            }
        }
        words(8, 32)
        sum += payloadBytes
        sum += 58
        words(40, payloadBytes)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt()
    }
}
