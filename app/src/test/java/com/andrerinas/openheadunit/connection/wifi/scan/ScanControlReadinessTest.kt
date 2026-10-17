package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanControlReadinessTest {
    @Test fun `permission granted outside the app resumes a waiting session once`() {
        val observer = ScanControlReadiness()
        var requests = 0
        observer.remember(ScanControlReadiness.Snapshot(ScanControlPolicy.AUTOJOIN, false))
        repeat(3) { observer.poll(ScanControlReadiness.Snapshot(ScanControlPolicy.AUTOJOIN, false)) { requests++ } }
        assertEquals(0, requests)
        repeat(3) { observer.poll(ScanControlReadiness.Snapshot(ScanControlPolicy.AUTOJOIN, true)) { requests++ } }
        assertEquals(1, requests)
    }

    @Test fun `station off unlocks legacy hotspot without repeated retries after a refusal`() {
        val observer = ScanControlReadiness()
        var requests = 0
        observer.remember(ScanControlReadiness.Snapshot(ScanControlPolicy.UNSUPPORTED, true))
        repeat(4) { observer.poll(ScanControlReadiness.Snapshot(ScanControlPolicy.HOTSPOT_SCAN, true)) { requests++ } }
        assertEquals(1, requests)
    }

    @Test fun `USB self mode and disabled option do not schedule setup`() {
        val observer = ScanControlReadiness()
        var requests = 0
        repeat(4) { observer.poll(null) { requests++ } }
        assertEquals(0, requests)
        // Session end/option changes already refresh the controller and reset the baseline.
        observer.remember(ScanControlReadiness.Snapshot(ScanControlPolicy.AUTOJOIN, true))
        observer.remember(null)
        repeat(4) { observer.poll(null) { requests++ } }
        assertEquals(0, requests)
    }

    @Test fun `permission revocation is observed once and a later grant allows another attempt`() {
        val observer = ScanControlReadiness()
        var requests = 0
        val granted = ScanControlReadiness.Snapshot(ScanControlPolicy.AUTOJOIN, true)
        val revoked = granted.copy(allowed = false)
        observer.remember(granted)
        repeat(3) { observer.poll(revoked) { requests++ } }
        assertEquals(1, requests)
        observer.poll(granted) { requests++ }
        assertEquals(2, requests)
    }
}
