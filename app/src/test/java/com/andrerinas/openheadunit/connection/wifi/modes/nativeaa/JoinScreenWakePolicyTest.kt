package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.JoinScreenWakePolicy.Hold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinScreenWakePolicyTest {

    // Measured on a Note8 hosting the group with its screen off: the phone finished the WPA
    // handshake, its DHCP never reached the unit, and it reported WIFI_NETWORK_UNAVAILABLE. The
    // same join landed in 3 s once the screen was lit.
    @Test
    fun `a dark screen is woken for the phone's join`() {
        assertEquals(Hold.WAKE, JoinScreenWakePolicy.hold(screenInteractive = false, refusalsInARow = 0))
    }

    // The same Note8 drops a lit keyguard after 5 s, inside the time a join takes.
    @Test
    fun `a lit screen is kept lit until the join is over`() {
        assertEquals(Hold.KEEP_LIT, JoinScreenWakePolicy.hold(screenInteractive = true, refusalsInARow = 0))
    }

    @Test
    fun `a screen state that cannot be read is left alone`() {
        assertEquals(Hold.NONE, JoinScreenWakePolicy.hold(screenInteractive = null, refusalsInARow = 0))
    }

    // One lost join can be a slow lease, exactly as the refusal backoff assumes.
    @Test
    fun `one refusal still gets the screen for the next join`() {
        assertEquals(
            Hold.WAKE,
            JoinScreenWakePolicy.hold(screenInteractive = false, refusalsInARow = JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE)
        )
    }

    // A phone that keeps refusing with the screen held for it is refusing for another reason, a
    // hotspot on the phone being the usual one, and every retry would light a parked unit again.
    @Test
    fun `a phone that keeps refusing with the screen held rules the screen out`() {
        val refused = JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE + 1
        assertEquals(Hold.RULED_OUT, JoinScreenWakePolicy.hold(screenInteractive = false, refusalsInARow = refused))
        assertEquals(Hold.RULED_OUT, JoinScreenWakePolicy.hold(screenInteractive = true, refusalsInARow = refused))
    }

    // From Type 3 to the phone's verdict took 36-37 s in every refused round measured.
    @Test
    fun `the hold outlasts the phone's own join attempt`() {
        assertTrue(JoinScreenWakePolicy.HOLD_MS >= 40_000L)
    }

    // A hold beyond the handoff's own settle ceiling would light the screen for a join nobody is
    // waiting on any more.
    @Test
    fun `the hold never outlasts the handoff's settle ceiling`() {
        assertTrue(JoinScreenWakePolicy.HOLD_MS <= NativeHandoffPolicy.MAX_SETTLE_MS)
    }
}
