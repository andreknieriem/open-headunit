package com.andrerinas.openheadunit.connection.carplaynative.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunBridgeRulesTest {
    private val hostMac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val peer = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())

    private fun v6(last: Int, first: Int = 0xfe) = ByteArray(16).also { it[0] = first.toByte(); if (first == 0xfe) it[1] = 0x80.toByte(); it[15] = last.toByte() }

    private fun packet(dst: ByteArray, nextHeader: Int, payload: ByteArray): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x60; p[4] = (payload.size ushr 8).toByte(); p[5] = payload.size.toByte(); p[6] = nextHeader.toByte(); p[7] = 64
        v6(2).copyInto(p, 8); dst.copyInto(p, 24); payload.copyInto(p, 40)
        return p
    }

    private fun neighborAdvertisement(): ByteArray = packet(v6(3), 58, ByteArray(24).also { it[0] = 136.toByte(); it[4] = 0x60; v6(2).copyInto(it, 8) })

    @Test fun anOutboundNeighborAdvertisementGainsOurMacOption() {
        val out = TunBridgeRules.outbound(neighborAdvertisement(), hostMac, peer) as TunOutbound.Frame
        val ipv6 = out.frame.copyOfRange(14, out.frame.size)
        assertEquals(72, ipv6.size)
        assertEquals(2, ipv6[64].toInt()); assertEquals(1, ipv6[65].toInt())
        assertArrayEquals(hostMac, ipv6.copyOfRange(66, 72))
        assertArrayEquals(peer, out.frame.copyOfRange(0, 6))
        assertArrayEquals(hostMac, out.frame.copyOfRange(6, 12))
    }

    @Test fun multicastLeavesAtOnceEvenBeforeThePeerIsKnown() {
        val dst = v6(1, first = 0xff).also { it[1] = 2; it[11] = 1; it[12] = 0xff.toByte(); it[13] = 0x11; it[14] = 0x22; it[15] = 0x33 }
        val out = TunBridgeRules.outbound(packet(dst, 17, ByteArray(8)), hostMac, null) as TunOutbound.Frame
        assertTrue(out.multicast)
        assertArrayEquals(byteArrayOf(0x33, 0x33, 0xff.toByte(), 0x11, 0x22, 0x33), out.frame.copyOfRange(0, 6))
    }

    @Test fun unicastWaitsUntilThePeerMacIsKnown() {
        val p = packet(v6(1), 6, ByteArray(20))
        assertTrue(TunBridgeRules.outbound(p, hostMac, null) === TunOutbound.WaitForPeer)
        val out = TunBridgeRules.outbound(p, hostMac, peer) as TunOutbound.Frame
        assertFalse(out.multicast)
        assertArrayEquals(peer, out.frame.copyOfRange(0, 6))
    }

    @Test fun otherPacketsAreCarriedUnchanged() {
        val p = packet(v6(1), 6, ByteArray(20) { it.toByte() })
        val out = TunBridgeRules.outbound(p, hostMac, peer) as TunOutbound.Frame
        assertArrayEquals(p, out.frame.copyOfRange(14, out.frame.size))
    }
}
