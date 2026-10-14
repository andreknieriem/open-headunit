package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pStackWedgePolicyTest {

    private val now = 10_000_000L

    @Test
    fun `the measured wedge - P2P flapping while every create fails - is wedged`() {
        assertTrue(P2pStackWedgePolicy.isWedged(wifiEnabled = true, disableEdgesInWindow = 2, createFailuresInWindow = 3))
    }

    @Test
    fun `a radio refusing a band never disables P2P, so it is not a wedge`() {
        assertFalse(P2pStackWedgePolicy.isWedged(true, disableEdgesInWindow = 0, createFailuresInWindow = 8))
    }

    @Test
    fun `WiFi switched off by the user is not a wedge`() {
        assertFalse(P2pStackWedgePolicy.isWedged(false, 5, 5))
    }

    @Test
    fun `a single WiFi toggle is not a wedge`() {
        assertFalse(P2pStackWedgePolicy.isWedged(true, disableEdgesInWindow = 1, createFailuresInWindow = 5))
        assertFalse(P2pStackWedgePolicy.isWedged(true, disableEdgesInWindow = 3, createFailuresInWindow = 2))
    }

    @Test
    fun `only events inside the window count, and future stamps are ignored`() {
        val times = listOf(now - 1_000, now - 59_000, now - 61_000, now + 5_000)
        assertEquals(2, P2pStackWedgePolicy.countRecent(times, now))
    }

    @Test
    fun `no automatic restart without the opt-in`() {
        assertFalse(P2pStackWedgePolicy.mayAutoRestart(optedIn = false, sessionConnected = false, now = now, restartTimes = emptyList()))
    }

    @Test
    fun `never during a live session`() {
        assertFalse(P2pStackWedgePolicy.mayAutoRestart(true, sessionConnected = true, now = now, restartTimes = emptyList()))
    }

    @Test
    fun `the first restart is allowed`() {
        assertTrue(P2pStackWedgePolicy.mayAutoRestart(true, false, now, emptyList()))
    }

    @Test
    fun `a second restart waits for the cooldown`() {
        assertFalse(P2pStackWedgePolicy.mayAutoRestart(true, false, now, listOf(now - 60_000)))
        assertTrue(P2pStackWedgePolicy.mayAutoRestart(true, false, now, listOf(now - 121_000)))
    }

    @Test
    fun `a unit that never recovers stops after three restarts an hour`() {
        val three = listOf(now - 3_000_000, now - 2_000_000, now - 1_000_000)
        assertFalse(P2pStackWedgePolicy.mayAutoRestart(true, false, now, three))
        val oneExpired = listOf(now - 3_700_000, now - 2_000_000, now - 1_000_000)
        assertTrue(P2pStackWedgePolicy.mayAutoRestart(true, false, now, oneExpired))
    }
}
