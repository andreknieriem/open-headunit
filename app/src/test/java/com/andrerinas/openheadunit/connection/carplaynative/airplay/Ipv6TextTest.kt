package com.andrerinas.openheadunit.connection.carplaynative.airplay

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ipv6TextTest {

    private fun text(vararg words: Int) = Ipv6Text.format(ByteArray(16) { i -> if (i % 2 == 0) (words[i / 2] shr 8).toByte() else words[i / 2].toByte() })

    @Test fun theTunnelPeerReadsAsFe80ColonColon1() = assertEquals("fe80::1", text(0xfe80, 0, 0, 0, 0, 0, 0, 1))

    @Test fun loopbackAndUnspecified() {
        assertEquals("::1", text(0, 0, 0, 0, 0, 0, 0, 1))
        assertEquals("::", text(0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test fun theLongestRunOfZerosIsCompressedAndTheFirstOfTwoEqualRuns() {
        assertEquals("1::4:0:0:7", text(1, 0, 0, 0, 4, 0, 0, 7))
        assertEquals("1:0:0:4::8", text(1, 0, 0, 4, 0, 0, 0, 8))
        assertEquals("1:0:0:4:0:0:7:8".replace("1:0:0:4:0:0:7:8", "1::4:0:0:7:8"), text(1, 0, 0, 4, 0, 0, 7, 8))
    }

    @Test fun aSingleZeroGroupStaysWritten() = assertEquals("1:0:3:4:5:6:7:8", text(1, 0, 3, 4, 5, 6, 7, 8))

    @Test fun digitsAreLowerCaseWithoutLeadingZeros() = assertEquals("abcd:12:0:1:2:3:4:ff", text(0xABCD, 0x0012, 0, 1, 2, 3, 4, 0xff))

    @Test fun theScopeOfALinkLocalAddressIsLeftOut() {
        val bytes = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        val scoped = Inet6Address.getByAddress(null, bytes, 3)
        assertEquals("fe80::1", Ipv6Text.of(scoped))
    }

    @Test fun anIpv4AddressKeepsItsDottedForm() = assertEquals("127.0.0.1", Ipv6Text.of(InetAddress.getByName("127.0.0.1")))

    @Test fun aWrongLengthIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Ipv6Text.format(ByteArray(4)) }
    }
}
