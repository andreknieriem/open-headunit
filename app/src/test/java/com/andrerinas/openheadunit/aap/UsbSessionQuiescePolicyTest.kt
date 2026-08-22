package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionQuiescePolicyTest {

    @Test
    fun `a wired session quiesces the wireless stack`() {
        assertTrue(UsbSessionQuiescePolicy.shouldQuiesce(sessionIsWireless = false))
    }

    @Test
    fun `a wireless session leaves it alone`() {
        assertFalse(UsbSessionQuiescePolicy.shouldQuiesce(sessionIsWireless = true))
    }

    @Test
    fun `the group comes down only when we host one and the session is wired`() {
        assertTrue(
            UsbSessionQuiescePolicy.shouldStopWifiDirectGroup(
                sessionIsWireless = false, usesWifiDirect = true
            )
        )
        assertFalse(
            UsbSessionQuiescePolicy.shouldStopWifiDirectGroup(
                sessionIsWireless = false, usesWifiDirect = false
            )
        )
    }

    /** The group carrying a wireless session is the session. Never touch it. */
    @Test
    fun `a wireless session never drops its own group`() {
        assertFalse(
            UsbSessionQuiescePolicy.shouldStopWifiDirectGroup(
                sessionIsWireless = true, usesWifiDirect = true
            )
        )
    }

    @Test
    fun `the wifi lock is for wireless sessions only`() {
        assertTrue(UsbSessionQuiescePolicy.shouldAcquireWifiLock(sessionIsWireless = true))
        assertFalse(UsbSessionQuiescePolicy.shouldAcquireWifiLock(sessionIsWireless = false))
    }

    @Test
    fun `re-arm happens exactly when we took the stack down and a wireless mode is configured`() {
        assertTrue(
            UsbSessionQuiescePolicy.shouldRearmWireless(
                quiescedForThisSession = true, wirelessModeConfigured = true
            )
        )
        assertFalse(
            UsbSessionQuiescePolicy.shouldRearmWireless(
                quiescedForThisSession = true, wirelessModeConfigured = false
            )
        )
        assertFalse(
            UsbSessionQuiescePolicy.shouldRearmWireless(
                quiescedForThisSession = false, wirelessModeConfigured = true
            )
        )
    }

    /**
     * The pairing that matters: anything quiesced must be re-armable, so a wired session with a
     * wireless mode configured always both quiesces and re-arms. A stack taken down with no way
     * back is how a long-lived manager ends up unable to re-arm.
     */
    @Test
    fun `quiesce and re-arm are symmetric for a wired session in a wireless mode`() {
        val quiesced = UsbSessionQuiescePolicy.shouldQuiesce(sessionIsWireless = false)
        assertTrue(quiesced)
        assertTrue(
            UsbSessionQuiescePolicy.shouldRearmWireless(
                quiescedForThisSession = quiesced, wirelessModeConfigured = true
            )
        )
    }

    @Test
    fun `a wireless session neither quiesces nor re-arms`() {
        val quiesced = UsbSessionQuiescePolicy.shouldQuiesce(sessionIsWireless = true)
        assertFalse(quiesced)
        assertFalse(
            UsbSessionQuiescePolicy.shouldRearmWireless(
                quiescedForThisSession = quiesced, wirelessModeConfigured = true
            )
        )
    }
}
