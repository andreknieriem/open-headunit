package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnresponsivePeerPolicyTest {

    private val phone = "192.168.41.113:5277"
    private val otherPhone = "192.168.41.200:5277"

    @Test
    fun `the ordinary cadence is unchanged below the threshold`() {
        for (failures in 0 until UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF) {
            assertEquals(
                UnresponsivePeerPolicy.NORMAL_RESCAN_MS,
                UnresponsivePeerPolicy.rescanDelayMs(failures)
            )
        }
    }

    @Test
    fun `the cadence drops at the threshold and stays down`() {
        assertEquals(
            UnresponsivePeerPolicy.BACKOFF_RESCAN_MS,
            UnresponsivePeerPolicy.rescanDelayMs(UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF)
        )
        assertEquals(UnresponsivePeerPolicy.BACKOFF_RESCAN_MS, UnresponsivePeerPolicy.rescanDelayMs(34))
    }

    @Test
    fun `backing off never becomes giving up`() {
        // The user fixes this on the phone, at a moment we cannot predict. An app that stopped
        // retrying for good would never notice, so every delay has to stay finite.
        assertTrue(UnresponsivePeerPolicy.rescanDelayMs(Int.MAX_VALUE) < Long.MAX_VALUE)
        assertTrue(UnresponsivePeerPolicy.rescanDelayMs(Int.MAX_VALUE) > 0)
    }

    @Test
    fun `the explanation goes out once, at the point the backoff engages`() {
        assertFalse(UnresponsivePeerPolicy.shouldExplain(0))
        assertFalse(
            UnresponsivePeerPolicy.shouldExplain(UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF - 1)
        )
        assertTrue(
            UnresponsivePeerPolicy.shouldExplain(UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF)
        )
        // Not on every cycle afterwards: 34 consecutive failures is a measured run, and 34
        // copies of the same paragraph would bury the log the user attaches to a report.
        assertFalse(
            UnresponsivePeerPolicy.shouldExplain(UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF + 1)
        )
        assertFalse(UnresponsivePeerPolicy.shouldExplain(34))
    }

    @Test
    fun `a streak against one endpoint accumulates`() {
        var count = 0
        var endpoint: String? = null
        repeat(3) {
            count = UnresponsivePeerPolicy.countAfterSilentFailure(count, endpoint, phone)
            endpoint = phone
        }
        assertEquals(3, count)
    }

    @Test
    fun `a different endpoint is a different server and starts its own count`() {
        assertEquals(1, UnresponsivePeerPolicy.countAfterSilentFailure(9, phone, otherPhone))
    }

    @Test
    fun `the first failure counts as one even with nothing recorded before it`() {
        assertEquals(1, UnresponsivePeerPolicy.countAfterSilentFailure(0, null, phone))
    }

    @Test
    fun `an unknown endpoint does not inherit a previous streak`() {
        // CommManager.lastAttemptedEndpoint is null on the USB path. Treating null as "the
        // same endpoint" would let a silent USB dongle push the WiFi discovery loop into
        // backoff, which has nothing to do with it.
        assertEquals(1, UnresponsivePeerPolicy.countAfterSilentFailure(2, phone, null))
        assertEquals(1, UnresponsivePeerPolicy.countAfterSilentFailure(2, null, null))
    }
}
