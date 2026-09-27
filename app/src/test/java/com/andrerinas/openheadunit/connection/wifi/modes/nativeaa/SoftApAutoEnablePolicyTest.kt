package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApAutoEnablePolicyTest {

    @Test
    fun `off in settings never attempts`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(false, 0, 60_000, 0, false, 60_000))
    }

    @Test
    fun `the first attempt waits for the grace`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 4_999, 0, false, 4_999))
        assertTrue(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 5_000, 0, false, 5_000))
    }

    @Test
    fun `a failed attempt is retried once, spaced out`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 14_000, 5_000, false, 14_999))
        assertTrue(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 15_000, 5_000, false, 15_000))
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 2, 60_000, 15_000, false, 60_000))
    }

    @Test
    fun `a retry never asks over an attempt still running`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 60_000, 0, false, 60_000, attemptInFlight = true))
    }

    @Test
    fun `the retry is spaced from when the first attempt returned`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 30_000, 25_000, false, 34_999))
        assertTrue(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 35_000, 25_000, false, 35_000))
    }

    @Test
    fun `the unit gets time to restore its own access point after a wake`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 120_000, 0, false, 120_000, sinceWakeMs = 14_999))
        assertTrue(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 120_000, 0, false, 120_000, sinceWakeMs = 15_000))
    }

    @Test
    fun `an attempt that succeeded is not repeated`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 60_000, 5_000, true, 60_000))
    }

    @Test
    fun `nothing is attempted while the car is off`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 60_000, 0, false, 60_000, accOff = true))
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 60_000, 5_000, false, 60_000, accOff = true))
    }

    @Test
    fun `the car coming back restores the schedule`() {
        assertTrue(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 60_000, 0, false, 60_000, accOff = false))
    }

    @Test
    fun `nothing is asked for while the radio settles after the access point drops`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 120_000, 0, false, 120_000, sinceApDownMs = 0))
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 120_000, 0, false, 120_000, sinceApDownMs = 9_999))
        assertTrue(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 120_000, 0, false, 120_000, sinceApDownMs = 10_000))
    }

    @Test
    fun `nothing is attempted while the screen is dark`() {
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 0, 60_000, 0, false, 60_000, screenOff = true))
        assertFalse(SoftApAutoEnablePolicy.shouldAttempt(true, 1, 60_000, 5_000, false, 60_000, screenOff = true))
    }

    @Test
    fun `an attempt is owed until the budget is spent or one succeeds`() {
        assertTrue(SoftApAutoEnablePolicy.attemptOwed(true, 0, false, false))
        assertTrue(SoftApAutoEnablePolicy.attemptOwed(true, 1, false, false))
        assertTrue(SoftApAutoEnablePolicy.attemptOwed(true, 2, false, true))
        assertFalse(SoftApAutoEnablePolicy.attemptOwed(true, 2, false, false))
        assertFalse(SoftApAutoEnablePolicy.attemptOwed(true, 1, true, false))
        assertFalse(SoftApAutoEnablePolicy.attemptOwed(false, 0, false, false))
    }
}
