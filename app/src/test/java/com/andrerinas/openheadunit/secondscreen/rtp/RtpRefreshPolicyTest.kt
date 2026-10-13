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

    @Test
    fun `one and two seconds are accepted and three falls back`() {
        assertEquals(1, RtpRefreshPolicy.intervalOrDefault(1))
        assertEquals(2, RtpRefreshPolicy.intervalOrDefault(2))
        assertEquals(10, RtpRefreshPolicy.intervalOrDefault(3))
    }

    @Test
    fun `each request is logged from five seconds up`() {
        assertFalse(RtpRefreshPolicy.logsEachRequest(1_000))
        assertFalse(RtpRefreshPolicy.logsEachRequest(4_999))
        assertTrue(RtpRefreshPolicy.logsEachRequest(5_000))
        assertTrue(RtpRefreshPolicy.logsEachRequest(10_000))
    }

    @Test
    fun `at 1 s an ask with no keyframe yet waits the grace before the next`() {
        assertFalse(RtpRefreshPolicy.shouldRequest(6_000, 4_000, 5_000, 1_000))
        assertFalse(RtpRefreshPolicy.shouldRequest(7_999, 4_000, 5_000, 1_000))
        assertTrue(RtpRefreshPolicy.shouldRequest(8_000, 4_000, 5_000, 1_000))
    }

    @Test
    fun `a keyframe after the ask ends the grace`() {
        assertFalse(RtpRefreshPolicy.shouldRequest(6_399, 5_400, 5_000, 1_000))
        assertTrue(RtpRefreshPolicy.shouldRequest(6_400, 5_400, 5_000, 1_000))
    }

    @Test
    fun `at 10 s a pending ask is repeated at 12 s`() {
        assertFalse(RtpRefreshPolicy.shouldRequest(21_999, 0, 10_000, 10_000))
        assertTrue(RtpRefreshPolicy.shouldRequest(22_000, 0, 10_000, 10_000))
    }
}
