package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class MixerDuckingEnvelopeTest {
    @Test fun `media remains unchanged while speech is only buffering`() {
        val envelope = MixerDuckingEnvelope()
        repeat(48000) { assertEquals(1f, envelope.nextGain(), 0f) }
    }

    @Test fun `audible speech ducks over ten milliseconds and restores over 120`() {
        val envelope = MixerDuckingEnvelope()
        envelope.setSpeechActive(true)
        var previous = 1f
        repeat(480) {
            val gain = envelope.nextGain()
            assertTrue(previous - gain in 0f..0.0013f)
            previous = gain
        }
        assertEquals(.4f, previous, 0f)
        repeat(4800) { assertEquals(.4f, envelope.nextGain(), 0f) }
        envelope.setSpeechActive(false)
        repeat(5760) {
            val gain = envelope.nextGain()
            assertTrue(gain >= previous && gain - previous < .0002f)
            previous = gain
        }
        assertEquals(1f, previous, 0f)
    }

    @Test fun `overlapping prompts and prompt resumption preserve continuous gain`() {
        val envelope = MixerDuckingEnvelope()
        envelope.setSpeechActive(true)
        repeat(480) { envelope.nextGain() }
        repeat(20) { // either prompt is still audible: no transport stop can unduck it
            envelope.setSpeechActive(true)
            assertEquals(.4f, envelope.nextGain(), 0f)
        }
        envelope.setSpeechActive(false)
        repeat(480) { envelope.nextGain() }
        val before = envelope.nextGain()
        envelope.setSpeechActive(true)
        assertTrue(kotlin.math.abs(envelope.nextGain() - before) < .002f)
        repeat(479) { envelope.nextGain() }
        assertEquals(.4f, envelope.nextGain(), 0f)
    }
}
