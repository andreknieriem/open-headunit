package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.MainActivity.ConnectionUiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectAttemptPolicyTest {

    @Test
    fun serverP2pOverlayBackgroundsWithoutAutomaticPromotion() {
        assertEquals(ConnectionUiMode.PILL, AutoConnectAttemptPolicy.modeAfterOverlayDismiss(true))
    }

    @Test
    fun otherOverlaysStillCancelTheirAttempt() {
        assertNull(AutoConnectAttemptPolicy.modeAfterOverlayDismiss(false))
    }

    @Test
    fun `the overlay keeps its own bound`() {
        assertEquals(
            AutoConnectAttemptPolicy.OVERLAY_WATCHDOG_MS,
            AutoConnectAttemptPolicy.watchdogMs(ConnectionUiMode.OVERLAY)
        )
    }

    @Test
    fun `a pill waits out several wake passes`() {
        assertEquals(
            AutoConnectAttemptPolicy.PILL_WATCHDOG_MS,
            AutoConnectAttemptPolicy.watchdogMs(ConnectionUiMode.PILL)
        )
        assertEquals(
            AutoConnectAttemptPolicy.PILL_WATCHDOG_MS,
            AutoConnectAttemptPolicy.watchdogMs(ConnectionUiMode.PILL_THEN_OVERLAY)
        )
    }

    @Test
    fun `a pill is given longer than an overlay`() {
        assertTrue(
            AutoConnectAttemptPolicy.watchdogMs(ConnectionUiMode.PILL) >
                AutoConnectAttemptPolicy.watchdogMs(ConnectionUiMode.OVERLAY)
        )
    }

    @Test
    fun `every mode is bounded`() {
        ConnectionUiMode.values().forEach {
            assertTrue("$it is unbounded", AutoConnectAttemptPolicy.watchdogMs(it) > 0L)
        }
    }

    // --- the bound as a deadline, which is what survives a recreated activity and a sleeping unit ---

    @Test
    fun `the deadline is the mode's bound past the start`() {
        assertEquals(
            10_000L + AutoConnectAttemptPolicy.PILL_WATCHDOG_MS,
            AutoConnectAttemptPolicy.deadlineAt(ConnectionUiMode.PILL_THEN_OVERLAY, 10_000L)
        )
    }

    @Test
    fun `an attempt taken over part-way through keeps the time it has left`() {
        val deadline = AutoConnectAttemptPolicy.deadlineAt(ConnectionUiMode.PILL, 0L)
        assertEquals(
            AutoConnectAttemptPolicy.PILL_WATCHDOG_MS - 40_000L,
            AutoConnectAttemptPolicy.remainingMs(deadline, 40_000L)
        )
        assertFalse(AutoConnectAttemptPolicy.hasExpired(deadline, 40_000L))
    }

    @Test
    fun `the remainder never goes negative, however long the unit slept`() {
        val deadline = AutoConnectAttemptPolicy.deadlineAt(ConnectionUiMode.OVERLAY, 0L)
        assertEquals(0L, AutoConnectAttemptPolicy.remainingMs(deadline, 900_000L))
        assertTrue(AutoConnectAttemptPolicy.hasExpired(deadline, 900_000L))
    }

    @Test
    fun `the bound has passed the instant it is reached, not after it`() {
        val deadline = AutoConnectAttemptPolicy.deadlineAt(ConnectionUiMode.OVERLAY, 0L)
        assertFalse(
            AutoConnectAttemptPolicy.hasExpired(
                deadline, AutoConnectAttemptPolicy.OVERLAY_WATCHDOG_MS - 1L
            )
        )
        assertTrue(
            AutoConnectAttemptPolicy.hasExpired(
                deadline, AutoConnectAttemptPolicy.OVERLAY_WATCHDOG_MS
            )
        )
    }

    @Test
    fun `a promotion to the overlay cannot shorten a bound already spent`() {
        // PILL_THEN_OVERLAY becomes OVERLAY once the phone answers. Deriving the deadline again
        // there would put it 30 s after a start that is already minutes old.
        val deadline = AutoConnectAttemptPolicy.deadlineAt(ConnectionUiMode.PILL_THEN_OVERLAY, 0L)
        val rederived = AutoConnectAttemptPolicy.deadlineAt(ConnectionUiMode.OVERLAY, 0L)
        assertTrue(AutoConnectAttemptPolicy.hasExpired(rederived, 60_000L))
        assertFalse(AutoConnectAttemptPolicy.hasExpired(deadline, 60_000L))
    }

    @Test
    fun `only the overlay's failure rewinds the pill`() {
        assertTrue(AutoConnectAttemptPolicy.resetsStageOnFailure(ConnectionUiMode.OVERLAY))
        assertFalse(AutoConnectAttemptPolicy.resetsStageOnFailure(ConnectionUiMode.PILL))
        assertFalse(
            AutoConnectAttemptPolicy.resetsStageOnFailure(ConnectionUiMode.PILL_THEN_OVERLAY)
        )
    }
}
