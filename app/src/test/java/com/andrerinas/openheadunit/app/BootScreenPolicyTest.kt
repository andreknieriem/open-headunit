package com.andrerinas.openheadunit.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootScreenPolicyTest {

    @Test
    fun `start on boot opens the screen by default`() {
        assertTrue(BootScreenPolicy.opensScreen(startOnBoot = true, waitInBackground = false))
    }

    @Test
    fun `waiting in the background keeps the screen closed`() {
        assertFalse(BootScreenPolicy.opensScreen(startOnBoot = true, waitInBackground = true))
    }

    @Test
    fun `nothing opens when start on boot is off`() {
        assertFalse(BootScreenPolicy.opensScreen(startOnBoot = false, waitInBackground = false))
        assertFalse(BootScreenPolicy.opensScreen(startOnBoot = false, waitInBackground = true))
    }
}
