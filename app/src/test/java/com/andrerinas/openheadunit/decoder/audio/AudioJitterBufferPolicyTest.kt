package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioJitterBufferPolicyTest {
    @Test fun `unsaved default preserves 400ms while the saved 8x setting preserves 200ms`() {
        assertEquals(400L, AudioJitterBufferPolicy.targetMsFor(AudioJitterBufferPolicy.DEFAULT_MULTIPLIER))
        assertEquals(200L, AudioJitterBufferPolicy.targetMsFor(8))
    }

    @Test fun `invalid settings clamp to a playable bounded dial`() {
        assertEquals(25L, AudioJitterBufferPolicy.targetMsFor(0))
        assertEquals(25L, AudioJitterBufferPolicy.targetMsFor(-4))
        assertEquals(400L, AudioJitterBufferPolicy.targetMsFor(Int.MAX_VALUE))
    }

    @Test fun `active policy still covers an arrival plus one drain at every setting`() {
        for (multiplier in listOf(1, 2, 4, 8, 16)) {
            val policy = AdaptiveJitterPolicy(48000, multiplier)
            policy.onArrival(0, 2048)
            assertTrue(policy.targetFrames >= 2048 + 480)
        }
    }

    @Test fun `active policy respects the dial above its arrival floor`() {
        fun target(multiplier: Int) = AdaptiveJitterPolicy(48000, multiplier).also {
            it.onArrival(0, 480)
        }.targetFrames
        assertTrue(target(16) > target(8))
        assertTrue(target(8) > target(1))
    }
}
