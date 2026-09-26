package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class LatencyRecoveryPolicyTest {
    @Test fun `target reductions remove at most one millisecond per hundred milliseconds`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.correction(0, 4800, 2048, 4800)
        var removed = 0
        for (now in 10L..5000L step 10) {
            val frames = policy.correction(now, 3840, 2048, 4800 - removed)
            assertTrue(frames in 0..48)
            removed += frames
            assertTrue(removed <= 960)
        }
        assertEquals(960, removed)
        assertEquals(0, policy.correction(10000, 3840, 2048, 4800))
    }

    @Test fun `a shallow trough blocks catch-up even if packet peaks are large`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.correction(0, 4800, 2048, 4800)
        for (now in 10L..5000L step 10) {
            val depth = if (now % 40 == 0L) 1500 else 4000
            assertEquals(0, policy.correction(now, 3840, 2048, depth))
        }
    }

    @Test fun `new jitter and deliberate reset cancel outstanding catch-up`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.correction(0, 4800, 2048, 4800)
        policy.correction(10, 3840, 2048, 4800)
        policy.correction(20, 5760, 2048, 6000)
        assertEquals(0, policy.correction(1000, 5760, 2048, 6000))
        policy.correction(1100, 3840, 2048, 6000)
        policy.reset()
        assertEquals(0, policy.correction(2000, 3840, 2048, 6000))
    }
}
