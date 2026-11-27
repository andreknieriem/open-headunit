package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpRebindPolicyTest {

    @Test
    fun `no rebind inside the gap whatever the flags`() {
        assertFalse(RtpRebindPolicy.shouldRebind(4_999, 0, false, true))
        assertFalse(RtpRebindPolicy.shouldRebind(10_000, 6_000, false, true))
    }

    @Test
    fun `rebinds at the gap after a failure`() {
        assertTrue(RtpRebindPolicy.shouldRebind(5_000, 0, true, true))
    }

    @Test
    fun `rebinds at the gap when the socket is on the default network with no failure`() {
        assertTrue(RtpRebindPolicy.shouldRebind(5_000, 0, false, false))
    }

    @Test
    fun `no rebind on the destination network with no failure`() {
        assertFalse(RtpRebindPolicy.shouldRebind(60_000, 0, true, false))
    }

    @Test
    fun `a send failure swaps the socket`() {
        assertFalse(RtpRebindPolicy.keepsSocket(true, "100", "100", null))
        assertFalse(RtpRebindPolicy.keepsSocket(true, null, null, null))
    }

    @Test
    fun `no candidate keeps the socket`() {
        assertTrue(RtpRebindPolicy.keepsSocket(false, null, null, null))
    }

    @Test
    fun `a socket already on the candidate is kept`() {
        assertTrue(RtpRebindPolicy.keepsSocket(false, "100", "100", null))
    }

    @Test
    fun `a candidate whose last bind failed keeps the socket`() {
        assertTrue(RtpRebindPolicy.keepsSocket(false, "100", null, "100"))
    }

    @Test
    fun `a new candidate swaps the socket`() {
        assertFalse(RtpRebindPolicy.keepsSocket(false, "100", null, null))
        assertFalse(RtpRebindPolicy.keepsSocket(false, "101", "100", null))
    }

    @Test
    fun `a candidate that changes after a failed bind swaps the socket`() {
        assertFalse(RtpRebindPolicy.keepsSocket(false, "101", null, "100"))
    }
}
