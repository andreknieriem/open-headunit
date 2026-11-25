package com.andrerinas.openheadunit.connection.carkey.fyt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FytCallScreenPolicyTest {

    @Test
    fun `suppresses on an FYT unit with the setting on and the projection in front`() {
        assertTrue(FytCallScreenPolicy.shouldSuppress(enabled = true, fytUnit = true, foreground = true))
    }

    @Test
    fun `does not suppress with the setting off`() {
        assertFalse(FytCallScreenPolicy.shouldSuppress(enabled = false, fytUnit = true, foreground = true))
    }

    @Test
    fun `does not suppress on another unit`() {
        assertFalse(FytCallScreenPolicy.shouldSuppress(enabled = true, fytUnit = false, foreground = true))
    }

    @Test
    fun `does not suppress while the projection is covered`() {
        assertFalse(FytCallScreenPolicy.shouldSuppress(enabled = true, fytUnit = true, foreground = false))
    }

    @Test
    fun `the setting shows in Self Mode or on an FYT unit`() {
        assertTrue(FytCallScreenPolicy.showsSetting(showsSelf = true, fytUnit = false))
        assertTrue(FytCallScreenPolicy.showsSetting(showsSelf = false, fytUnit = true))
        assertFalse(FytCallScreenPolicy.showsSetting(showsSelf = false, fytUnit = false))
    }

    @Test
    fun `the ask repeats faster than a call can follow a call`() {
        assertTrue(FytCallScreenPolicy.REPEAT_MS in 250L..1_000L)
    }
}
