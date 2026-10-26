package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class NativeTempoPolicyTest {
    @Test fun `isolated bursts and render pauses do not authorize acceleration`() {
        val policy = NativeTempoPolicy()
        for (now in 0L..1000L step 10) {
            policy.observe(now, 1920, if (now % 100 == 0L) 9000 else 1920, 480, true)
            assertEquals(1.0, policy.tempo(now, 1920, 9000, 480, true), 0.0)
        }
        policy.observe(5000, 1920, 9000, 480, true)
        assertEquals(1.0, policy.tempo(5000, 1920, 9000, 480, true), 0.0)
    }
    @Test fun `sustained surplus and shortage slew to the requested bounds with finite permits`() {
        for (slow in listOf(false, true)) {
            val policy = NativeTempoPolicy()
            val target = if (slow) 19200 else 1920
            val depth = if (slow) 2400 else 12000
            for (now in 0L..500L step 10) policy.observe(now, target, depth, 480, true)
            var previous = 1.0
            repeat(12) {
                val rate = policy.tempo(500, target, depth, 480, true)
                assertTrue(rate in 0.8..1.2)
                assertTrue(kotlin.math.abs(rate - previous) <= 0.020000001)
                assertTrue(if (slow) rate < 1.0 else rate > 1.0)
                previous = rate
            }
            assertEquals(if (slow) 0.8 else 1.2, previous, 0.000000001)
            val exhausted = if (slow) 1.0 else NativeTempoPolicy.WAIT_FOR_RENDER
            assertEquals(exhausted, policy.tempo(500, target, depth, 480, true), 0.0)
            // A render observation cannot refill an exhausted permit before its expiry.
            policy.observe(510, target, depth, 480, true)
            assertEquals(exhausted, policy.tempo(510, target, depth, 480, true), 0.0)
            policy.observe(600, target, depth, 480, true)
            val renewed = policy.tempo(600, target, depth, 480, true)
            assertTrue(if (slow) renewed in 0.8..<1.0 else renewed > 1.0)
            policy.beginSegment()
            assertEquals(1.0, policy.tempo(600, target, depth, 480, true), 0.0)
        }
    }

    @Test fun `live output correction credit cannot be spent repeatedly on withheld native output`() {
        for (slow in listOf(false, true)) {
            val policy = NativeTempoPolicy()
            val target = if (slow) 19200 else 1920
            val initialDepth = if (slow) 2400 else 12000
            for (now in 0L..500L step 10) policy.observe(now, target, initialDepth, 480, true)
            val almostAtBoundary = if (slow) target - 1 else target + 241
            var correction = 0.0
            repeat(12) {
                val rate = policy.tempo(500, target, almostAtBoundary, 480, true)
                if (rate != NativeTempoPolicy.WAIT_FOR_RENDER)
                    correction += kotlin.math.abs(480.0 - 480.0 / rate)
            }
            assertTrue("only one frame of live credit", correction <= 1.000000001)
            val atBoundary = if (slow) target else target + 240
            assertEquals(1.0, policy.tempo(500, target, atBoundary, 480, true), 0.0)
        }
    }

    @Test fun `late rendering can establish proof but sparse rendering and invalidation cannot`() {
        for (intervalUs in listOf(10500L, 12500L, 100000L)) {
            val policy = NativeTempoPolicy()
            var requested = false
            for (us in 0L..2_000_000L step intervalUs) {
                val now = us / 1000
                policy.observe(now, 1920, 12000, 480, true)
                requested = requested || policy.tempo(now, 1920, 12000, 480, true) > 1.0
            }
            assertEquals(intervalUs < 100000L, requested)
            policy.observe(5000, 1920, 12000, 480, true)
            assertEquals(1.0, policy.tempo(5000, 1920, 12000, 480, true), 0.0)
        }
        for (change in 0..2) {
            val policy = NativeTempoPolicy()
            for (now in 0L..500L step 10) policy.observe(now, 1920, 12000, 480, true)
            when (change) {
                0 -> policy.observe(510, 1920, 12000, 480, false)
                1 -> policy.observe(510, 2400, 12000, 480, true)
                2 -> policy.observe(510, 1920, 2000, 480, true)
            }
            assertEquals(1.0, policy.tempo(510, 1920, 12000, 480, true), 0.0)
        }
    }

    @Test fun `arrival reserve growth invalidates old acceleration before another render`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1, isMediaSink = true, nativeTimeCorrection = true)
        val pcm = ShortArray(24000) { 1000 }
        bank.write(pcm, pcm.size, 0)
        assertTrue(bank.render(ShortArray(960), 0))
        val policy = NativeTempoPolicy()
        val originalTarget = bank.targetFrames()
        for (now in 0L..500L step 10)
            policy.observe(now, originalTarget, bank.depthFrames(), 480, true)
        assertTrue(bank.timeCorrectionTempo(policy, 500, 480) > 1.0)
        bank.noteArrival(500, 19200)
        assertTrue(bank.targetFrames() > bank.depthFrames())
        assertEquals(1.0, bank.timeCorrectionTempo(policy, 500, 480), 0.0)
        // Returning to the previous target cannot resurrect revoked proof either.
        assertEquals(1.0, policy.tempo(500, originalTarget, bank.depthFrames(), 480, true), 0.0)
        val newTarget = bank.targetFrames()
        for (now in 510L..1010L step 10)
            policy.observe(now, newTarget, newTarget + 12000, 480, true)
        assertTrue(policy.tempo(1010, newTarget, newTarget + 12000, 480, true) > 1.0)
    }

    @Test fun `waiting for fresh credit survives expiry but not inactive or stale playback`() {
        for (exit in 0..3) {
            val policy = NativeTempoPolicy()
            for (now in 0L..500L step 10) policy.observe(now, 1920, 12000, 480, true)
            repeat(12) { assertTrue(policy.tempo(500, 1920, 12000, 480, true) > 1.0) }
            assertEquals(NativeTempoPolicy.WAIT_FOR_RENDER, policy.tempo(500, 1920, 12000, 480, true), 0.0)
            policy.observe(590, 1920, 12000, 480, true)
            // Expiry before the renderer's renewal must not dump the queued burst at unity.
            assertEquals(NativeTempoPolicy.WAIT_FOR_RENDER, policy.tempo(600, 1920, 12000, 480, true), 0.0)
            when (exit) {
                0 -> {
                    policy.observe(600, 1920, 12000, 480, true)
                    assertEquals(1.2, policy.tempo(600, 1920, 12000, 480, true), 0.000000001)
                }
                1 -> assertEquals(1.0, policy.tempo(690, 1920, 12000, 480, true), 0.0)
                2 -> {
                    assertEquals(1.0, policy.tempo(600, 1920, 12000, 480, false), 0.0)
                    assertEquals(1.0, policy.tempo(600, 1920, 12000, 480, true), 0.0)
                }
                3 -> assertEquals(1.0, policy.tempo(600, 1920, 2160, 480, true), 0.0)
            }
        }
    }
}
