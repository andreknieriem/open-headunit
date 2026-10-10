package com.andrerinas.openheadunit.connection.usb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The flag the attach fallback reads to tell our own switch from a device nobody is handling. */
class UsbSwitchClaimTest {

    @Before
    @After
    fun clearClaim() {
        UsbSwitchClaim.release()
    }

    @Test
    fun `nothing is claimed until something stakes one`() {
        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test
    fun `a staked claim is live and a released one is not`() {
        UsbSwitchClaim.stake()
        assertTrue(UsbSwitchClaim.isLive())

        UsbSwitchClaim.release()
        assertFalse(UsbSwitchClaim.isLive())
    }

    /** Releasing a claim nobody staked must not resurrect one. */
    @Test
    fun `release is idempotent`() {
        UsbSwitchClaim.release()
        UsbSwitchClaim.release()

        assertFalse(UsbSwitchClaim.isLive())
    }

    /** Re-staking pushes the expiry out rather than stacking claims that need matching releases. */
    @Test
    fun `a second stake still needs only one release`() {
        UsbSwitchClaim.stake()
        UsbSwitchClaim.stake()
        UsbSwitchClaim.release()

        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test
    fun `a completed switch moves the count`() {
        val before = UsbSwitchClaim.switches()
        UsbSwitchClaim.noteSwitched()
        assertEquals(before + 1, UsbSwitchClaim.switches())
    }

    @Test
    fun `a stake alone does not move the count`() {
        val before = UsbSwitchClaim.switches()
        UsbSwitchClaim.stake()
        UsbSwitchClaim.release()
        assertEquals(before, UsbSwitchClaim.switches())
    }
}
