package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpRefreshPolicyTest {

    @Test
    fun `asks only once both the keyframe and the last request are an interval old`() {
        assertFalse(RtpRefreshPolicy.shouldRequest(9_999, 0, 0, 10_000))
        assertTrue(RtpRefreshPolicy.shouldRequest(10_000, 0, 0, 10_000))
        assertFalse(RtpRefreshPolicy.shouldRequest(15_000, 0, 10_000, 10_000))
        assertFalse(RtpRefreshPolicy.shouldRequest(15_000, 6_000, 0, 10_000))
    }

    @Test
    fun `an interval of zero never asks`() {
        assertFalse(RtpRefreshPolicy.shouldRequest(Long.MAX_VALUE / 2, 0, 0, 0))
    }

    @Test
    fun `a stored value outside the choices falls back to the default`() {
        assertEquals(10, RtpRefreshPolicy.DEFAULT_INTERVAL_S)
        for (s in RtpRefreshPolicy.INTERVALS_S) assertEquals(s, RtpRefreshPolicy.intervalOrDefault(s))
        assertEquals(10, RtpRefreshPolicy.intervalOrDefault(7))
        assertEquals(10, RtpRefreshPolicy.intervalOrDefault(-1))
    }
}
