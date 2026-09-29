package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class LatencyRecoveryPolicyTest {
    private fun tick(policy: LatencyRecoveryPolicy, now: Long, target: Int, packet: Int,
                     depth: Int, slack: Int = 1568): Int {
        policy.observe(now, target, packet, slack, depth, now * 48)
        val frames = policy.correction(now, depth)
        policy.consumed(now, frames)
        return frames
    }

    @Test fun `lower target repays only its debt in one millisecond overlaps`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 4800, 2048, 4800)
        var removed = 0
        var lastCorrection = -100L
        for (now in 10L..5000L step 10) {
            val frames = tick(policy, now, 3840, 2048, 4800 - removed)
            assertTrue(frames in 0..48)
            if (frames > 0) {
                assertTrue(now - lastCorrection >= 100)
                lastCorrection = now
            }
            removed += frames
            assertTrue(removed <= 960)
        }
        assertEquals(960, removed)
        assertEquals(0, tick(policy, 10000, 3840, 2048, 4800))
    }

    @Test fun `a fixed target recovers persistent excess after a full rendering window`() {
        val policy = LatencyRecoveryPolicy(48000)
        val target = 19200
        val slack = 3407
        var removed = 0
        for (now in 0L..5000L step 10) {
            val frames = tick(policy, now, target, 1024, 22752 - removed, slack)
            if (now < 500) assertEquals(0, frames)
            assertTrue(frames in 0..48)
            removed += frames
        }
        assertEquals(22752 - target - slack, removed)
        assertEquals(0, tick(policy, 5010, target, 1024, 22752 - removed, slack))
    }

    @Test fun `isolated decoder peaks do not authorize catch-up`() {
        val policy = LatencyRecoveryPolicy(48000)
        for (now in 0L..5000L step 10) {
            val depth = if (now % 500 == 0L) 26000 else 20000
            assertEquals(0, tick(policy, now, 19200, 1024, depth, 3407))
        }
    }

    @Test fun `smaller output or packet slack repays sustained excess without a target change`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 9600, 8192, 14000, 7712)
        var removed = 0
        for (now in 10L..10000L step 10) {
            removed += tick(policy, now, 9600, 2048, 14000 - removed)
        }
        assertEquals(14000 - 11168, removed)
        assertEquals(0, tick(policy, 10010, 9600, 2048, 14000 - removed))
    }

    @Test fun `a shallow trough blocks catch-up even if packet peaks are large`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 4800, 2048, 4800)
        for (now in 10L..5000L step 10) {
            val depth = if (now % 40 == 0L) 1500 else 4000
            assertEquals(0, tick(policy, now, 3840, 2048, depth))
        }
    }

    @Test fun `no input sparse rendering and stopped tails cannot authorize catch-up`() {
        for (mode in 0..2) {
            val policy = LatencyRecoveryPolicy(48000)
            val interval = if (mode == 1) 100L else 10L
            for (now in 0L..2000L step interval) {
                policy.observe(now, 4800, 2048, 1568, 12000,
                    if (mode == 0) 0 else now * 48, canRecover = mode != 2)
                assertEquals(0, policy.correction(now, 12000))
            }
        }
    }

    @Test fun `growing target reset or starvation cancels outstanding correction`() {
        for (mode in 0..2) {
            val policy = LatencyRecoveryPolicy(48000)
            tick(policy, 0, 4800, 2048, 4800)
            for (now in 10L..510L step 10) policy.observe(now, 3840, 2048, 1568, 4800, now * 48)
            assertEquals(48, policy.correction(510, 4800))
            when (mode) {
                0 -> policy.observe(520, 5760, 2048, 1568, 6000, 24960)
                1 -> policy.reset()
                2 -> policy.observe(520, 3840, 2048, 1568, 4800, 24960, canRecover = false)
            }
            assertEquals(0, policy.correction(520, 4800))
        }
    }

    @Test fun `proposals do not repay debt until the renderer consumes them`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 4800, 2048, 4800)
        for (now in 10L..510L step 10) policy.observe(now, 3840, 2048, 1568, 4800, now * 48)
        assertEquals(48, policy.correction(510, 4800))
        assertEquals(48, policy.correction(510, 4800))
        policy.consumed(510, 48)
        assertEquals(0, policy.correction(520, 4752))
    }

    @Test fun `a renderer stall invalidates a previously safe catch-up window`() {
        val policy = LatencyRecoveryPolicy(48000)
        for (now in 0L..510L step 10) policy.observe(now, 4800, 2048, 1568, 9000, now * 48)
        assertEquals(48, policy.correction(510, 9000))
        policy.observe(2000, 4800, 2048, 1568, 9000, 96000)
        assertEquals(0, policy.correction(2000, 9000))
    }
}
