package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capacity is reserve space; effective size is the active playback budget. */
class AudioBufferSizingPolicyTest {

    @Test fun `effective size follows the cushion instead of the reserved capacity`() {
        assertEquals(3600, AudioBufferSizingPolicy.effectiveFrames(48000, 3360, 960, 19200))
        assertEquals(5040, AudioBufferSizingPolicy.effectiveFrames(48000, 4800, 960, 19200))
    }

    @Test fun `effective size respects hardware bounds`() {
        assertEquals(3840, AudioBufferSizingPolicy.effectiveFrames(48000, 960, 3840, 19200))
        assertEquals(2000, AudioBufferSizingPolicy.effectiveFrames(48000, 4800, 960, 2000))
    }

    // The device minimum both #984's and #979's units report at 48 kHz stereo: 80 ms.
    private val minBytes = 15376

    @Test
    fun `the default multiplier is left alone`() {
        // 15376 * 8 = 123008 bytes, which is what #979's log shows the unit being asked for.
        assertEquals(123008, AudioBufferSizingPolicy.requestedBytes(minBytes, 8, 48000, 4))
    }

    @Test
    fun `a low multiplier is floored rather than left unusable`() {
        // #984 ran multiplier 2, which is 30752 bytes = 160 ms, below anything that can hold.
        val bytes = AudioBufferSizingPolicy.requestedBytes(minBytes, 2, 48000, 4)
        assertTrue(bytes > minBytes * 2)
        assertEquals(AudioBufferSizingPolicy.framesFor(48000, AudioBufferSizingPolicy.MIN_CAPACITY_MS) * 4, bytes)
    }

    @Test
    fun `the floor never takes the request below the device minimum`() {
        // A device whose minimum already exceeds the floor keeps its own minimum.
        val hugeMin = 1_000_000
        assertEquals(hugeMin, AudioBufferSizingPolicy.requestedBytes(hugeMin, 1, 48000, 4))
    }

    @Test
    fun `a framework error is handed back untouched`() {
        assertEquals(-2, AudioBufferSizingPolicy.requestedBytes(-2, 8, 48000, 4))
        assertEquals(0, AudioBufferSizingPolicy.requestedBytes(0, 8, 48000, 4))
    }
}
