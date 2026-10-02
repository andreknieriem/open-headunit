package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class OutputBufferPolicyTest {
    @Test fun `callback output retains a scheduling reserve while probing extra bursts`() {
        val policy = OutputBufferPolicy(48000, 192, CallbackBufferSizing.minimumFrames(192, 192))
        assertEquals(1152, policy.update(0, 0)) // 24ms combined: 8ms device + 16ms producer
        assertEquals(1344, policy.update(100, 1))
        assertEquals(1344, policy.update(10099, 1))
        assertEquals(1152, policy.update(10100, 1))
        assertEquals(1152, policy.update(20100, 1))
    }

    @Test fun `all tuning steps and the ceiling remain aligned to the device burst`() {
        val policy = OutputBufferPolicy(48000, 256, 768)
        policy.update(0, 0)
        repeat(40) {
            val frames = policy.update(100L + it, it + 1)
            assertEquals(0, frames % 256)
            assertTrue(frames <= 2880)
        }
        assertEquals(2816, policy.targetFrames)
        repeat(40) { assertEquals(0, policy.update(20000L + it * 10000L, 40) % 256) }
        assertEquals(768, policy.targetFrames)
    }

    @Test fun `a device floor exceeding sixty milliseconds is not silently undersized`() {
        val policy = OutputBufferPolicy(48000, 1536, 4608)
        assertEquals(4608, policy.update(0, 0))
        assertEquals(6144, policy.update(1, 1))
        assertEquals(7680, policy.update(2, 2))
        assertEquals(7680, policy.update(3, 3))
    }

    @Test fun `a reset xrun counter restarts the stability interval`() {
        val policy = OutputBufferPolicy(48000, 192, 576)
        policy.update(0, 0)
        policy.update(100, 1)
        assertEquals(768, policy.update(10000, 0))
        assertEquals(768, policy.update(19999, 0))
        assertEquals(576, policy.update(20000, 0))
    }
}
