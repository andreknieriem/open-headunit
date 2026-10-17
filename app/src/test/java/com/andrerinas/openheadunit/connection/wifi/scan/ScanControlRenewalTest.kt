package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.*
import org.junit.Test

class ScanControlRenewalTest {
    @Test fun `readiness polls and UI refreshes do not shorten five-minute interval`() {
        val renewal = ScanControlRenewal()
        renewal.applied("lease", 1_000)
        for (elapsed in listOf(0L, 5_000L, 60_000L, 299_999L)) {
            assertFalse(renewal.due("lease", ScanControlPolicy.LEGACY_CONNECTIVITY, 1_000 + elapsed))
        }
        assertTrue(renewal.due("lease", ScanControlPolicy.LEGACY_CONNECTIVITY, 301_000))
    }

    @Test fun `delayed poll renews once and starts a fresh interval without catching up`() {
        val renewal = ScanControlRenewal()
        renewal.applied("lease", 0)
        assertTrue(renewal.due("lease", ScanControlPolicy.LEGACY_CONNECTIVITY, 1_200_000))
        renewal.applied("lease", 1_200_000)
        assertFalse(renewal.due("lease", ScanControlPolicy.LEGACY_CONNECTIVITY, 1_200_000))
        assertFalse(renewal.due("lease", ScanControlPolicy.LEGACY_CONNECTIVITY, 1_499_999))
        assertTrue(renewal.due("lease", ScanControlPolicy.LEGACY_CONNECTIVITY, 1_500_000))
    }

    @Test fun `new lease resets timing and old receipts and other modes never renew`() {
        val renewal = ScanControlRenewal()
        assertFalse(renewal.due("old", ScanControlPolicy.LEGACY_CONNECTIVITY, 600_000))
        renewal.applied("old", 0)
        renewal.applied("new", 600_000)
        assertFalse(renewal.due("old", ScanControlPolicy.LEGACY_CONNECTIVITY, 900_000))
        assertFalse(renewal.due("new", ScanControlPolicy.LEGACY_CONNECTIVITY, 600_000))
        for (mode in listOf(0, 1, 2, 3)) assertFalse(renewal.due("new", mode, 900_000))
        assertTrue(renewal.due("new", ScanControlPolicy.LEGACY_CONNECTIVITY, 900_000))
    }
}
