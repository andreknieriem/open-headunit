package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class GalAudioReserveTest {
    @Test fun `normal sender limits retain default reserve behavior at every preference`() {
        for (multiplier in listOf(1, 2, 4, 8, 16)) {
            for (hint in listOf(300L, 400L, Long.MAX_VALUE)) {
                val baseline = AdaptiveJitterPolicy(48000, multiplier)
                val requested = AdaptiveJitterPolicy(48000, multiplier).apply { requestedLimitMs = hint }
                for (ms in 0L..40_000L step 40) {
                    baseline.onArrival(ms, 1920)
                    requested.onArrival(ms, 1920)
                    if (ms == 4000L) { baseline.onUnderrun(ms); requested.onUnderrun(ms) }
                    assertEquals("multiplier=$multiplier hint=$hint at $ms", baseline.targetFrames, requested.targetFrames)
                }
            }
        }
    }

    @Test fun `sender ceiling never shrinks saved preference or a full observed batch`() {
        val preferred = AdaptiveJitterPolicy(48000, 16)
        preferred.requestedLimitMs = 50
        assertTrue(preferred.targetFrames >= 19200)
        val batched = AdaptiveJitterPolicy(48000, 1)
        batched.requestedLimitMs = 60
        batched.onArrival(0, 4800)
        assertTrue(batched.targetFrames >= 5280)
    }

    @Test fun `hint follows quiet decay instead of a target step and cannot block deficit recovery`() {
        val policy = AdaptiveJitterPolicy(48000, 1)
        policy.onArrival(0, 4800)
        val initial = policy.targetFrames
        policy.requestedLimitMs = 60
        assertEquals(initial, policy.targetFrames)
        var last = initial
        for (ms in 100L..30_000L step 100) {
            policy.onArrival(ms, 4800)
            assertTrue("target fell too fast at $ms", last - policy.targetFrames <= 240)
            last = policy.targetFrames
        }
        assertTrue(last < initial)
        policy.onArrival(30_400, 4800)
        assertTrue("a supply deficit must immediately override a low hint", policy.targetFrames > last)
    }

    @Test fun `receiving a buffer request neither discards stored PCM nor fabricates events`() {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = 1)
        buffer.noteArrival(0, 4800)
        buffer.write(ShortArray(9600) { 1000 }, 9600, 0)
        buffer.setRequestedLimit(60)
        assertEquals(4800, buffer.depthFrames())
        assertEquals(0L, buffer.droppedFrames)
        buffer.setRequestedLimit(null)
        assertEquals(Triple(100_000L, 0L, 0L), buffer.telemetry())
    }

    @Test fun `queue duration measures frames and overflow counts events not lost samples`() {
        val buffer = AdaptivePcmBuffer(sampleRate = 16000, channels = 1, latencyMultiplier = 1)
        buffer.write(ShortArray(160), 160, 0)
        assertEquals(Triple(10_000L, 0L, 0L), buffer.telemetry())
        val oversized = ShortArray(16000 * 20)
        buffer.write(oversized, oversized.size, 1)
        assertEquals(1L, buffer.telemetry().second)
        buffer.write(oversized, oversized.size, 2)
        assertEquals(2L, buffer.telemetry().second)
        buffer.reset()
        assertEquals(0L, buffer.telemetry().first)
        assertEquals(2L, buffer.telemetry().second)
        assertEquals(0L, buffer.telemetry().third)
    }
}
