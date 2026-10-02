package com.andrerinas.openheadunit.decoder.audio
import org.junit.Assert.*
import org.junit.Test

class RecoveryDebtGuardTest {
    @Test fun `historical target debt is not spendable below the live target`() {
        val p = LatencyRecoveryPolicy(48000)
        p.observe(0, 4800, 2048, 1568, 4800, 0)
        for (now in 10L..1000L step 10) {
            p.observe(now, 3840, 2048, 1568, 3000, now * 48)
            assertEquals(0, p.correction(now, 3000))
        }
    }

    @Test fun `observed renderer slack ages out rather than pinning debt forever`() {
        val p = LatencyRecoveryPolicy(48000)
        p.observe(0, 4800, 2048, 1568, 4800, 0)
        var removed = 0
        for (now in 100L..4000L step 10) {
            p.observe(now, 3840, 2048, 1568, 4800 - removed, now * 48)
            val n = p.correction(now, 4800 - removed)
            if (now < 1000) assertEquals(0, n)
            assertTrue(n in 0..48)
            p.consumed(now, n); removed += n
        }
        assertEquals(960, removed)
    }
}
