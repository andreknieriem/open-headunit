package com.andrerinas.openheadunit.connection.carplaynative.airplay

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerGateTest {

    private fun v6(vararg head: Int, last: Int = 1, scope: Int = 0): Inet6Address {
        val bytes = ByteArray(16)
        head.forEachIndexed { i, b -> bytes[i] = b.toByte() }
        bytes[15] = last.toByte()
        return if (scope == 0) InetAddress.getByAddress(bytes) as Inet6Address else Inet6Address.getByAddress(null, bytes, scope)
    }

    private val phone = v6(0xfe, 0x80, last = 1, scope = 4)
    private val tunnel = v6(0xfe, 0x80, last = 2, scope = 4)

    @Test fun theSameHostPassesWhateverItsScope() {
        assertTrue(PeerGate.accepts(phone, v6(0xfe, 0x80, last = 1)))
        assertTrue(PeerGate.accepts(phone, v6(0xfe, 0x80, last = 1, scope = 9)))
    }

    @Test fun anotherHostOrALookalikeIsRefused() {
        assertFalse(PeerGate.accepts(phone, tunnel))
        assertFalse(PeerGate.accepts(phone, v6(0xfe, 0x81, last = 1)))
        assertFalse(PeerGate.accepts(phone, InetAddress.getByName("127.0.0.1")))
    }

    @Test fun anUnknownPeerPassesNothing() {
        assertFalse(PeerGate.accepts(null, phone))
        assertFalse(PeerGate.accepts(phone, null))
    }

    @Test fun theNcmPeerIsLinkLocalAndNotUs() {
        assertTrue(PeerGate.isNcmPeer(phone, tunnel))
        assertFalse(PeerGate.isNcmPeer(tunnel, tunnel))
        assertFalse(PeerGate.isNcmPeer(v6(0xfd, 0x00, last = 1), tunnel))
        assertFalse(PeerGate.isNcmPeer(v6(0xfe, 0xc0, last = 1), tunnel))
        assertFalse(PeerGate.isNcmPeer(InetAddress.getByName("::1"), tunnel))
        assertFalse(PeerGate.isNcmPeer(InetAddress.getByName("192.168.0.2"), tunnel))
    }
}
