package com.andrerinas.openheadunit.connection.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfLaunchTimeoutPolicyTest {

    // No route may tear the session down when it runs out of patience: the timeout once
    // disconnected, which took down the dummy VPN handed to Gearhead and closed the port 5288
    // server the phone still had to arrive on. CommManager.reportLaunchFailure reports without a
    // disconnect on every route; InternalFailureRecoveryTest holds that.

    @Test
    fun `every route gets a deadline, and none of them is the old 2500ms`() {
        for (path in SelfLaunchPath.values()) {
            val deadline = SelfLaunchTimeoutPolicy.deadlineMs(path)
            assertTrue("${path.name} deadline is not positive: $deadline", deadline > 0)
            assertTrue(
                "${path.name} kept a deadline Gearhead cannot meet: $deadline",
                deadline > 2_500L
            )
        }
    }

    @Test
    fun `the legacy route waits longer than the one that dials out itself`() {
        // Legacy hands Gearhead a network and waits to be called back; the head unit server route
        // connects synchronously and has already reported its own failure by this point.
        assertTrue(
            SelfLaunchTimeoutPolicy.deadlineMs(SelfLaunchPath.LEGACY) >
                SelfLaunchTimeoutPolicy.deadlineMs(SelfLaunchPath.HEADUNIT_SERVER)
        )
    }

    @Test
    fun `the legacy deadline clears the measured bring-up time`() {
        // 15 to 20 s measured on a UNISOC head unit. A deadline at that figure would expire on a
        // run that was merely slow, which is the failure being fixed.
        assertTrue(SelfLaunchTimeoutPolicy.deadlineMs(SelfLaunchPath.LEGACY) >= 20_000L)
    }

    @Test
    fun `the deadlines are the named constants`() {
        assertEquals(
            SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS,
            SelfLaunchTimeoutPolicy.deadlineMs(SelfLaunchPath.LEGACY)
        )
        assertEquals(
            SelfLaunchTimeoutPolicy.HEADUNIT_SERVER_DEADLINE_MS,
            SelfLaunchTimeoutPolicy.deadlineMs(SelfLaunchPath.HEADUNIT_SERVER)
        )
    }
}
