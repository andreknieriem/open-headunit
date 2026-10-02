package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class OutputBufferTunerTest {
    private class Output(private val minimumGrant: Int = 0) : PcmOutput {
        override val name = "fake"
        override val capacityFrames = 19200
        override var bufferFrames = 19200
        override val burstFrames = 480
        override val underruns = 0
        var requests = 0
        override fun setBufferFrames(frames: Int): Int {
            requests++
            bufferFrames = maxOf(frames, minimumGrant)
            return bufferFrames
        }
        override fun start() = Unit
        override fun pause() = Unit
        override fun write(data: ShortArray, offset: Int, count: Int) = count
        override fun close() = Unit
    }

    @Test fun `framework restore reapplies the target even when xruns and request are unchanged`() {
        val output = Output()
        val tuner = OutputBufferTuner()
        assertTrue(tuner.update(output, 960))
        assertEquals(960, output.bufferFrames)
        assertFalse(tuner.update(output, 960))
        output.bufferFrames = 19200 // audioserver restored the original track capacity
        assertTrue(tuner.update(output, 960))
        assertEquals(960, output.bufferFrames)
        assertEquals(2, output.requests)
        assertFalse(tuner.update(output, 960))
    }

    @Test fun `unchangeable legacy HAL grant does not cause a request on every tuning tick`() {
        for (grant in intArrayOf(2229, 3343)) {
            val output = Output(grant)
            val tuner = OutputBufferTuner()
            assertTrue(tuner.update(output, 960))
            repeat(1000) { assertFalse(tuner.update(output, 960)) }
            assertEquals(1, output.requests)
            assertEquals(grant, output.bufferFrames)
        }
    }

    @Test fun `clamped target still repairs a later external resize`() {
        val output = Output(1440)
        val tuner = OutputBufferTuner()
        tuner.update(output, 960)
        output.bufferFrames = 19200
        assertTrue(tuner.update(output, 960))
        assertEquals(1440, output.bufferFrames)
        assertFalse(tuner.update(output, 960))
    }

    @Test fun `changed target or replacement backend can force a fresh request`() {
        val output = Output()
        val tuner = OutputBufferTuner()
        tuner.update(output, 960)
        assertTrue(tuner.update(output, 1440))
        assertEquals(1440, output.bufferFrames)
        assertTrue(tuner.update(output, 1440, force = true))
        assertEquals(3, output.requests)
        assertFalse(tuner.update(output, 1440))
    }
}
