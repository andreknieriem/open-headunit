package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothWakePolicy.WakeReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadLinkRecoveryPolicyTest {

    private val ripe = DeadLinkRecoveryPolicy.MIN_STREAK_MS

    @Test
    fun `a single failed pass is a slow phone, not a dead radio`() {
        assertFalse(DeadLinkRecoveryPolicy.shouldCycle(0, WakeReason.NO_LINK, ripe))
        assertFalse(
            DeadLinkRecoveryPolicy.shouldCycle(
                DeadLinkRecoveryPolicy.MIN_FAILED_POKES - 1,
                WakeReason.NO_LINK,
                ripe
            )
        )
    }

    @Test
    fun `two failed passes at a phone with no link is evidence enough`() {
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(2, WakeReason.NO_LINK, ripe))
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(2, WakeReason.TARGET_ABSENT, ripe))
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(5, WakeReason.TARGET_ABSENT, ripe))
    }

    /** A burst of fast errors inside one round is not a streak yet. */
    @Test
    fun `failures younger than one round are left to retry`() {
        assertFalse(DeadLinkRecoveryPolicy.shouldCycle(9, WakeReason.NO_LINK, 0L))
        assertFalse(DeadLinkRecoveryPolicy.shouldCycle(9, WakeReason.NO_LINK, ripe - 1))
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(9, WakeReason.NO_LINK, ripe))
    }

    /**
     * The floor has to be reachable by the loop it breaks. A chosen-driver wake is budgeted at
     * [NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS] rounds inside
     * [NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS], and each round here costs about 50 s
     * (two RFCOMM records at an ~18 s connect timeout plus the retry gap). The first version of
     * this policy waited the stand-down's 90 s and therefore never fired at all: the last round's
     * check landed at ~84 s. This is that defect, pinned.
     */
    @Test
    fun `the floor fits inside the wake budget it has to fire within`() {
        val roundCostMs = 50_000L
        val lastCheckMs =
            (NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS - 1) * roundCostMs
        assertTrue(
            "the cycle must be reachable before the last round ends",
            DeadLinkRecoveryPolicy.MIN_STREAK_MS <= lastCheckMs
        )
        assertTrue(
            "and still inside the exclusive budget",
            DeadLinkRecoveryPolicy.MIN_STREAK_MS < NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS
        )
        assertTrue(
            "while staying well past the stand-down floor's intent, one round",
            DeadLinkRecoveryPolicy.MIN_STREAK_MS >= roundCostMs / 2
        )
    }

    /**
     * The other reasons describe a link that belongs to someone else or could not be read. Dropping
     * this unit's radio for them would be a guess, and the cycle policy already refuses them.
     */
    @Test
    fun `a link that is somebody else's is never evidence for a cycle`() {
        for (reason in WakeReason.entries) {
            val expected = reason == WakeReason.NO_LINK || reason == WakeReason.TARGET_ABSENT
            assertTrue(
                reason.name,
                expected == DeadLinkRecoveryPolicy.shouldCycle(9, reason, ripe)
            )
        }
    }

    @Test
    fun `the stand-down path keeps its own reason and is not double counted`() {
        // TARGET_CONNECTED reaches the cycle through BluetoothRadioCyclePolicy.cycleDecision; it
        // must not also qualify as a dead-link streak, or one pass would spend two levers.
        assertFalse(DeadLinkRecoveryPolicy.shouldCycle(9, WakeReason.TARGET_CONNECTED, ripe))
    }
}
