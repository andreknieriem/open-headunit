package com.andrerinas.openheadunit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccPowerStateTest {

    @Test
    fun `a wake with no off heard still moves the wake stamp`() {
        AccPowerState.noteWake(1_000L)
        AccPowerState.noteOn()
        assertEquals(1_000L, AccPowerState.wokeAtMs)
        AccPowerState.noteWake(2_000L)
        assertEquals(2_000L, AccPowerState.wokeAtMs)
        assertFalse(AccPowerState.isOff)
    }

    @Test
    fun `a wake clears an off`() {
        AccPowerState.noteOff("inferred: power lost beside a screen-off")
        assertTrue(AccPowerState.isOff)
        AccPowerState.noteWake(3_000L)
        assertFalse(AccPowerState.isOff)
        assertEquals(3_000L, AccPowerState.wokeAtMs)
    }
}
