package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopWaitPolicyTest {

    @Test
    fun `pending transport on a wireless launcher is waited for`() {
        assertTrue(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                transportPending = true,
                wirelessLauncherActive = true,
            )
        )
    }

    @Test
    fun `completed transport needs no wait`() {
        assertFalse(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                transportPending = false,
                wirelessLauncherActive = true,
            )
        )
    }

    @Test
    fun `a wired session holds no network to give back`() {
        assertFalse(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                transportPending = true,
                wirelessLauncherActive = false,
            )
        )
    }

    @Test
    fun `an idle service stops at once`() {
        assertFalse(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                transportPending = false,
                wirelessLauncherActive = false,
            )
        )
    }

}
