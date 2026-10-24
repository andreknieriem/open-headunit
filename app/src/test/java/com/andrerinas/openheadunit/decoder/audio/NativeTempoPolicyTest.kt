package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class NativeTempoPolicyTest {
    @Test fun `isolated bursts and render pauses do not authorize acceleration`() {
        val policy = NativeTempoPolicy()
        for (now in 0L..1000L step 10) {
            policy.observe(now, 1920, if (now % 100 == 0L) 9000 else 1920, 480, true)
            assertEquals(1.0, policy.tempo(now, 9000, 480), 0.0)
        }
        policy.observe(5000, 1920, 9000, 480, true)
        assertEquals(1.0, policy.tempo(5000, 9000, 480), 0.0)
    }
    @Test fun `stable excess permits bounded input and expires across segments and low water`() {
        val policy = NativeTempoPolicy()
        for (now in 0L..500L step 10) policy.observe(now, 1920, 9000, 480, true)
        repeat(10) { assertEquals(1.01, policy.tempo(500, 9000, 480), 0.0) }
        assertEquals(1.0, policy.tempo(500, 9000, 480), 0.0)
        assertEquals(1.0, policy.tempo(600, 9000, 480), 0.0)
        policy.observe(600, 1920, 9000, 480, true)
        assertEquals(1.01, policy.tempo(600, 9000, 480), 0.0)
        assertEquals(1.0, policy.tempo(600, 2000, 480), 0.0)
        policy.observe(610, 1920, 2000, 480, true)
        assertEquals(1.0, policy.tempo(610, 9000, 480), 0.0)
        policy.beginSegment()
        assertEquals(1.0, policy.tempo(610, 9000, 480), 0.0)
    }
}
