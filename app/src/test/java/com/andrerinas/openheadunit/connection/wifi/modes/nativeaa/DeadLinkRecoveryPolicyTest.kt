package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothWakePolicy.WakeReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadLinkRecoveryPolicyTest {

    @Test
    fun `a single failed pass is a slow phone, not a dead radio`() {
        assertFalse(DeadLinkRecoveryPolicy.shouldCycle(0, WakeReason.NO_LINK))
        assertFalse(
            DeadLinkRecoveryPolicy.shouldCycle(
                DeadLinkRecoveryPolicy.MIN_FAILED_POKES - 1,
                WakeReason.NO_LINK
            )
        )
    }

    @Test
    fun `two failed passes at a phone with no link is evidence enough`() {
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(2, WakeReason.NO_LINK))
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(2, WakeReason.TARGET_ABSENT))
        assertTrue(DeadLinkRecoveryPolicy.shouldCycle(5, WakeReason.TARGET_ABSENT))
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
                expected == DeadLinkRecoveryPolicy.shouldCycle(9, reason)
            )
        }
    }

    @Test
    fun `the stand-down path keeps its own reason and is not double counted`() {
        // TARGET_CONNECTED reaches the cycle through BluetoothRadioCyclePolicy.cycleDecision; it
        // must not also qualify as a dead-link streak, or one pass would spend two levers.
        assertFalse(DeadLinkRecoveryPolicy.shouldCycle(9, WakeReason.TARGET_CONNECTED))
    }
}
