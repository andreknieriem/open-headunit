package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZbtReopenPolicyTest {

    @Test
    fun `delays back off from 2 seconds to the ceiling`() {
        val seconds = (1..6).map { ZbtReopenPolicy.delayAfterRefusalMs(it) / 1000 }
        assertEquals(listOf(2L, 4L, 8L, 16L, 30L, 30L), seconds)
    }

    @Test
    fun `a count of zero or less is the first refusal, never a zero delay`() {
        assertEquals(2_000L, ZbtReopenPolicy.delayAfterRefusalMs(0))
        assertEquals(2_000L, ZbtReopenPolicy.delayAfterRefusalMs(-5))
    }

    @Test
    fun `nothing exceeds the ceiling`() {
        assertEquals(ZbtReopenPolicy.CEILING_MS, ZbtReopenPolicy.delayAfterRefusalMs(Int.MAX_VALUE))
        assertTrue((1..200).all { ZbtReopenPolicy.delayAfterRefusalMs(it) <= ZbtReopenPolicy.CEILING_MS })
    }

    @Test
    fun `the warning waits for the same window the bring-up dial uses`() {
        val window = ZbtReachabilityPolicy.REFUSAL_WINDOW_MS
        assertFalse(ZbtReopenPolicy.warnsNoDaemon(window - 1))
        assertTrue(ZbtReopenPolicy.warnsNoDaemon(window))
    }

    @Test
    fun `the first four delays spend the window, so the warning lands on the fifth refusal`() {
        val spent = (1..4).sumOf { ZbtReopenPolicy.delayAfterRefusalMs(it) }
        assertEquals(ZbtReachabilityPolicy.REFUSAL_WINDOW_MS, spent)
        assertFalse(ZbtReopenPolicy.warnsNoDaemon(spent - 1))
        assertTrue(ZbtReopenPolicy.warnsNoDaemon(spent))
    }
}
