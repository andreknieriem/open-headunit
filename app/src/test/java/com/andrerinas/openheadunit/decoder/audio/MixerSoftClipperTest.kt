package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MixerSoftClipperTest {
    @Test fun `activation and mute release have no amplitude step at either polarity`() {
        for (amplitude in listOf(-30000, 30000)) {
            val limiter = MixerSoftClipper()
            var previous = limiter.process(amplitude).toInt()
            limiter.setEnabled(true)
            repeat(480) {
                limiter.advance()
                val next = limiter.process(amplitude).toInt()
                assertTrue(abs(next - previous) < 20)
                previous = next
            }
            limiter.setEnabled(false)
            repeat(5760) {
                limiter.advance()
                val next = limiter.process(amplitude).toInt()
                assertTrue(abs(next - previous) < 3)
                previous = next
            }
            assertEquals(amplitude, previous)
        }
    }
    @Test fun `single unboosted channel preserves every PCM16 value`() {
        val limiter = MixerSoftClipper()
        for (sample in -32768..32767) assertEquals(sample.toShort(), limiter.process(sample))
    }
}
