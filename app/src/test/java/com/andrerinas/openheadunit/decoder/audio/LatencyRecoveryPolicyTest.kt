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

    @Test fun `target and slack reductions retain the previously permitted PCM`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 4272, 3072, 2592, 6240, 0)
        assertEquals(6864, policy.trimLimit)
        policy.observe(10, 3248, 2048, 1568, 6240, 2048)
        assertEquals(6864, policy.trimLimit)
        var removed = 0
        for (now in 20L..5000L step 10) {
            removed += tick(policy, now, 3248, 2048, 6240 - removed)
        }
        assertTrue(removed >= 6240 - 4816)
        assertTrue(removed < 2048) // unused slack is not an additional consumption debt
        assertEquals(4816, policy.trimLimit)
    }

    @Test fun `slack alone recovers only observed excess and then expires`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 9600, 8192, 7712, 14000, 0)
        var removed = 0
        for (now in 10L..10000L step 10) {
            removed += tick(policy, now, 9600, 2048, 14000 - removed)
        }
        assertTrue(removed >= 14000 - 11168)
        assertTrue(removed < 3000)
        assertEquals(11168, policy.trimLimit)
        assertEquals(0, tick(policy, 10010, 9600, 2048, 11168))
    }

    @Test fun `unused slack expires without consuming audio`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 9600, 8192, 7712, 10500, 0)
        for (now in 10L..1000L step 10) {
            assertEquals(0, tick(policy, now, 9600, 2048, 10500))
        }
        assertEquals(11168, policy.trimLimit)
    }

    @Test fun `rising target cancels correction without removing trim protection`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 7088, 2048, 1568, 7000, 0)
        policy.observe(10, 3248, 2048, 1568, 7000, 480)
        policy.observe(20, 3728, 2048, 1568, 7000, 960)
        assertEquals(8656, policy.trimLimit)
        assertEquals(0, policy.correction(20, 7000))
    }

    @Test fun `oscillating slack never accumulates extra headroom or follows backlog`() {
        val policy = LatencyRecoveryPolicy(48000)
        for (i in 0..100) {
            policy.observe(i * 10L, 4000, 2048, if (i % 2 == 0) 8000 else 1568, 30000, i * 480L)
            assertEquals(12000, policy.trimLimit)
        }
        policy.reset()
        policy.observe(2000, 4000, 2048, 1568, 30000, 100000)
        assertEquals(5568, policy.trimLimit) // new backlog still requires hard trim
        policy.observe(2010, 47000, 2048, 8000, 48000, 100480)
        assertEquals(48000, policy.trimLimit) // physical capacity is always the upper bound
    }

    @Test fun `proposed catch-up cannot lower trim protection before actual consumption`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 10000, 2048, 1568, 11000, 0)
        for (now in 10L..510L step 10) policy.observe(now, 6000, 2048, 1568, 11000, now * 48)
        assertEquals(48, policy.correction(510, 11000))
        assertEquals(11568, policy.trimLimit)
        policy.consumed(510, 48)
        assertEquals(11520, policy.trimLimit)
        assertEquals(0, policy.correction(520, 10952))
    }

    @Test fun `pause or finished tail does not establish a stable input window`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 9600, 8192, 7712, 10500, 0)
        for (now in 10L..1000L step 10) policy.observe(now, 9600, 2048, 1568, 10500, 0)
        assertEquals(17312, policy.trimLimit)
        policy.observe(5000, 9600, 2048, 1568, 10500, 2048)
        assertEquals(17312, policy.trimLimit)
        for (now in 5010L..6000L step 10) {
            policy.observe(now, 9600, 2048, 1568, 10500, now * 48, canRecover = false)
            assertEquals(0, policy.correction(now, 10500))
        }
        assertEquals(17312, policy.trimLimit)
        policy.reset()
        policy.observe(7000, 9600, 2048, 1568, 10500, 400000)
        assertEquals(11168, policy.trimLimit)
    }

    @Test fun `sparse rendering cannot retire grace just because wall time passed`() {
        val policy = LatencyRecoveryPolicy(48000)
        policy.observe(0, 9600, 8192, 7712, 10500, 0)
        for (now in 10L..1010L step 100) {
            policy.observe(now, 9600, 2048, 1568, 10500, now * 48)
            assertEquals(0, policy.correction(now, 10500))
        }
        assertEquals(17312, policy.trimLimit)
    }
    @Test fun `target reductions remove at most one millisecond per hundred milliseconds`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 4800, 2048, 4800)
        var removed = 0
        for (now in 10L..5000L step 10) {
            val frames = tick(policy, now, 3840, 2048, 4800 - removed)
            assertTrue(frames in 0..48)
            removed += frames
            assertTrue(removed <= 960)
        }
        assertEquals(960, removed)
        assertEquals(0, tick(policy, 10000, 3840, 2048, 4800))
    }

    @Test fun `a shallow trough blocks catch-up even if packet peaks are large`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 4800, 2048, 4800)
        for (now in 10L..5000L step 10) {
            val depth = if (now % 40 == 0L) 1500 else 4000
            assertEquals(0, tick(policy, now, 3840, 2048, depth))
        }
    }

    @Test fun `new jitter and deliberate reset cancel outstanding catch-up`() {
        val policy = LatencyRecoveryPolicy(48000)
        tick(policy, 0, 4800, 2048, 4800)
        tick(policy, 10, 3840, 2048, 4800)
        tick(policy, 20, 5760, 2048, 6000)
        assertEquals(0, tick(policy, 1000, 5760, 2048, 6000))
        tick(policy, 1100, 3840, 2048, 6000)
        policy.reset()
        assertEquals(0, tick(policy, 2000, 3840, 2048, 6000))
    }
}
