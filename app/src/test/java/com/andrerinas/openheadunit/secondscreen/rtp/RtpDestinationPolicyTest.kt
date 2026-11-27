package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpDestinationPolicyTest {

    private fun octets(vararg v: Int) = ByteArray(4) { v[it].toByte() }

    @Test
    fun `a dotted IPv4 address is parsed and trimmed`() {
        assertArrayEquals(octets(192, 168, 1, 50), RtpDestinationPolicy.parse("192.168.1.50"))
        assertArrayEquals(octets(10, 0, 0, 7), RtpDestinationPolicy.parse(" 10.0.0.7 "))
        assertArrayEquals(octets(127, 0, 0, 1), RtpDestinationPolicy.parse("127.0.0.1"))
        assertArrayEquals(octets(1, 2, 3, 4), RtpDestinationPolicy.parse("1.2.3.4"))
        assertArrayEquals(octets(255, 255, 255, 254), RtpDestinationPolicy.parse("255.255.255.254"))
    }

    @Test
    fun `anything that is not a unicast IPv4 address is refused`() {
        val bad = listOf(
            "", "   ", "cluster.local", "::1", "fe80::1", "1.2.3", "1.2.3.4.5", "1.2.3.4.", "256.1.1.1",
            "1.2.3.-4", "+1.2.3.4", "1..3.4", "1234.1.1.1", "0.0.0.0", "255.255.255.255", "224.0.0.1",
            "239.255.255.250",
        )
        for (text in bad) assertNull("'$text'", RtpDestinationPolicy.parse(text))
    }

    @Test
    fun `a port outside 1 to 65535 falls back to the default`() {
        assertEquals(5004, RtpDestinationPolicy.portOrDefault(0))
        assertEquals(5004, RtpDestinationPolicy.portOrDefault(70000))
        for (p in listOf(1, 1023, 25000, 65535)) assertEquals(p, RtpDestinationPolicy.portOrDefault(p))
    }

    @Test
    fun `the defaults are no address and port 5004`() {
        assertEquals("", RtpDestinationPolicy.DEFAULT_DESTINATION)
        assertEquals(5004, RtpDestinationPolicy.DEFAULT_PORT)
    }

    @Test
    fun `the SDP is a complete description a receiver opens as is`() {
        val expected = listOf(
            "v=0", "o=- 0 0 IN IP4 192.168.1.50", "s=Open Headunit second screen", "c=IN IP4 192.168.1.50",
            "t=0 0", "m=video 25000 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1",
        ).joinToString("\n")
        assertEquals(expected, RtpDestinationPolicy.sdp("192.168.1.50", 25000))
    }

    @Test
    fun `the SDP carries 0 0 0 0 while no address is stored`() {
        val sdp = RtpDestinationPolicy.sdp("", 5004)
        assertEquals("o=- 0 0 IN IP4 0.0.0.0", sdp.lines()[1])
        assertEquals("c=IN IP4 0.0.0.0", sdp.lines()[3])
    }

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C)

    @Test
    fun `stream parameters are profile-level-id and padded base64 sets`() {
        assertEquals("profile-level-id=42001f;sprop-parameter-sets=Z0IAHw==,aM48",
            RtpDestinationPolicy.streamParameters(sps, pps))
    }

    @Test
    fun `base64 pads one and two trailing bytes`() {
        assertEquals("AQ==", RtpDestinationPolicy.base64(byteArrayOf(1)))
        assertEquals("AQI=", RtpDestinationPolicy.base64(byteArrayOf(1, 2)))
        assertEquals("AQID", RtpDestinationPolicy.base64(byteArrayOf(1, 2, 3)))
        assertEquals("AQIDBA==", RtpDestinationPolicy.base64(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `a short SPS or an empty PPS gives no parameters`() {
        assertNull(RtpDestinationPolicy.streamParameters(byteArrayOf(0x67, 0x42, 0x00), pps))
        assertNull(RtpDestinationPolicy.streamParameters(sps, ByteArray(0)))
    }

    @Test
    fun `the sdp adds stream parameters to the fmtp line`() {
        val sdp = RtpDestinationPolicy.sdp("192.168.1.50", 25000, "profile-level-id=42001f;sprop-parameter-sets=a,b")
        assertEquals("a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=a,b", sdp.lines().last())
    }

    @Test
    fun `the sdp without stream parameters is unchanged`() {
        assertEquals("a=fmtp:96 packetization-mode=1", RtpDestinationPolicy.sdp("192.168.1.50", 25000, "").lines().last())
        assertEquals(8, RtpDestinationPolicy.sdp("192.168.1.50", 25000).lines().size)
    }
}
