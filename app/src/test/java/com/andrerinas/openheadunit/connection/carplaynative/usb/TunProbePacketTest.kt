package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.crypto.hex
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunProbePacketTest {
    // Both packets were computed with an independent script (struct and a plain one's-complement sum).
    private val synVector = "6000000000140640fe800000000000000000000000000001fe8000000000000000000000000000029c40c35001020304000000005002ffff4f470000"
    private val synAckVector = "6000000000140640fe800000000000000000000000000002fe800000000000000000000000000001c3509c400a0b0c0d010203055012ffff391e0000"

    @Test
    fun `the probe SYN matches the independent vector with a valid checksum`() {
        assertEquals(synVector, TunProbePacket.syn(50000).toHex())
        assertEquals(0, EthernetIpv6.upperLayerChecksum(TunProbePacket.syn(50000), 6, 40, 20))
    }

    @Test
    fun `the vector SYN-ACK is accepted for its port only`() {
        val packet = hex(synAckVector)
        assertTrue(TunProbePacket.isSynAck(packet, 50000))
        assertFalse(TunProbePacket.isSynAck(packet, 50001))
    }

    @Test
    fun `a SYN-ACK with a flipped checksum, wrong ack or wrong address is refused`() {
        fun mutate(index: Int) = hex(synAckVector).also { it[index] = (it[index].toInt() xor 1).toByte() }
        assertFalse(TunProbePacket.isSynAck(mutate(56), 50000))
        assertFalse(TunProbePacket.isSynAck(mutate(51), 50000))
        assertFalse(TunProbePacket.isSynAck(mutate(23), 50000))
        assertFalse(TunProbePacket.isSynAck(mutate(39), 50000))
    }

    @Test
    fun `a bare SYN, a RST and a short packet are not a SYN-ACK`() {
        assertFalse(TunProbePacket.isSynAck(hex(synVector), 50000))
        val rst = hex(synAckVector).also { it[53] = 0x14 }
        assertFalse(TunProbePacket.isSynAck(rst, 50000))
        assertFalse(TunProbePacket.isSynAck(ByteArray(30), 50000))
    }
}
