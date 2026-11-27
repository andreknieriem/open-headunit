package com.andrerinas.openheadunit.secondscreen.rtp

import com.andrerinas.openheadunit.secondscreen.rtp.RtpNetworkPolicy.Candidate
import com.andrerinas.openheadunit.secondscreen.rtp.RtpNetworkPolicy.LinkAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpNetworkPolicyTest {

    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    private fun cand(i: Int, vpn: Boolean, addr: ByteArray, prefix: Int) =
        Candidate(i, "if$i", vpn, listOf(LinkAddress(addr, prefix)))

    @Test
    fun `the network whose subnet holds the destination wins`() {
        val wifi = cand(0, false, ip(192, 168, 1, 20), 24)
        val other = cand(1, false, ip(172, 16, 0, 5), 24)
        assertEquals(wifi, RtpNetworkPolicy.choose(ip(192, 168, 1, 50), listOf(other, wifi)))
    }

    @Test
    fun `the longer prefix wins when two networks hold it`() {
        val wide = cand(0, false, ip(192, 168, 1, 20), 16)
        val narrow = cand(1, false, ip(192, 168, 1, 21), 24)
        assertEquals(narrow, RtpNetworkPolicy.choose(ip(192, 168, 1, 50), listOf(wide, narrow)))
    }

    @Test
    fun `a VPN network never wins`() {
        val tun = cand(0, true, ip(10, 0, 0, 2), 24)
        assertNull(RtpNetworkPolicy.choose(ip(10, 0, 0, 9), listOf(tun)))
    }

    @Test
    fun `no network holding the destination gives null`() {
        assertNull(RtpNetworkPolicy.choose(ip(8, 8, 8, 8), listOf(cand(0, false, ip(192, 168, 1, 20), 24))))
    }

    @Test
    fun `a loopback destination gives null`() {
        assertNull(RtpNetworkPolicy.choose(ip(127, 0, 0, 1), listOf(cand(0, false, ip(127, 0, 0, 5), 8))))
    }

    @Test
    fun `a zero prefix holds everything and a 32 prefix only itself`() {
        val all = cand(0, false, ip(192, 168, 1, 20), 0)
        assertEquals(all, RtpNetworkPolicy.choose(ip(8, 8, 8, 8), listOf(all)))
        val one = cand(1, false, ip(192, 168, 1, 20), 32)
        assertNull(RtpNetworkPolicy.choose(ip(192, 168, 1, 21), listOf(one)))
        assertEquals(one, RtpNetworkPolicy.choose(ip(192, 168, 1, 20), listOf(one)))
    }
}
