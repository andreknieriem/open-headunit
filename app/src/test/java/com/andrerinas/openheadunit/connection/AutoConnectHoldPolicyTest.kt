package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.AutoConnectHoldPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectHoldPolicyTest {

    private fun decide(
        settingsVisible: Boolean = false,
        sessionLive: Boolean = false,
        cancelledByUser: Boolean = false,
        userRequested: Boolean = false,
    ) = AutoConnectHoldPolicy.decide(settingsVisible, sessionLive, cancelledByUser, userRequested)

    @Test
    fun `nothing holding lets an automatic connection proceed`() {
        assertEquals(Verdict.PROCEED, decide())
    }

    /** The #1011 report: a dongle re-enumerating reconnected while the user was in settings. */
    @Test
    fun `the settings screen holds an automatic connection`() {
        assertEquals(Verdict.HOLD_FOR_SETTINGS, decide(settingsVisible = true))
    }

    @Test
    fun `a request by hand is still held behind the settings screen`() {
        assertEquals(Verdict.HOLD_FOR_SETTINGS, decide(settingsVisible = true, userRequested = true))
    }

    @Test
    fun `a live session is never held`() {
        assertEquals(Verdict.PROCEED, decide(settingsVisible = true, sessionLive = true))
    }

    @Test
    fun `the X refuses an automatic connection`() {
        assertEquals(Verdict.CANCELLED_BY_USER, decide(cancelledByUser = true))
    }

    @Test
    fun `a request by hand passes the X`() {
        assertEquals(Verdict.PROCEED, decide(cancelledByUser = true, userRequested = true))
    }

    /** The replay on close is automatic, so an X pressed meanwhile still wins. */
    @Test
    fun `a held request replayed under the X is refused`() {
        assertEquals(Verdict.HOLD_FOR_SETTINGS, decide(settingsVisible = true))
        assertEquals(Verdict.CANCELLED_BY_USER, decide(settingsVisible = false, cancelledByUser = true))
    }

    @Test
    fun `no screen is raised over settings or after the X`() {
        assertTrue(AutoConnectHoldPolicy.raisesUi(settingsVisible = false, cancelledByUser = false))
        assertFalse(AutoConnectHoldPolicy.raisesUi(settingsVisible = true, cancelledByUser = false))
        assertFalse(AutoConnectHoldPolicy.raisesUi(settingsVisible = false, cancelledByUser = true))
    }

    @Test
    fun `closing the screen replays only what it held`() {
        assertFalse(AutoConnectHoldPolicy.replaysOnRelease(usbHeld = false, bluetoothLaunchHeld = false))
        assertTrue(AutoConnectHoldPolicy.replaysOnRelease(usbHeld = true, bluetoothLaunchHeld = false))
        assertTrue(AutoConnectHoldPolicy.replaysOnRelease(usbHeld = false, bluetoothLaunchHeld = true))
    }
}
