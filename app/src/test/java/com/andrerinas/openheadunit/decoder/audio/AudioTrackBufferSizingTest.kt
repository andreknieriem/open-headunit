package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTrackBufferSizingTest {
    @Test fun `platform byte estimates become whole stereo frames without losing the safety floor`() {
        assertEquals(960, AudioTrackBufferSizing.minimumFrames(768))
        assertEquals(960, AudioTrackBufferSizing.minimumFrames(3840))
        assertEquals(1922, AudioTrackBufferSizing.minimumFrames(7688))
        assertEquals(5928, AudioTrackBufferSizing.minimumFrames(23712))
        assertEquals(5929, AudioTrackBufferSizing.minimumFrames(23713))
    }

    @Test fun `large platform floors still have room for the output controller to grow`() {
        for (minimum in listOf(960, 3343, 5928, 19001, 19201, 30000)) {
            val capacity = AudioTrackBufferSizing.allocationBytes(minimum, true) / 4
            val policy = OutputBufferPolicy(48000, 480, minimum)
            assertTrue(capacity >= policy.maximumFrames)
            assertTrue(capacity >= 19200)
            assertEquals(minimum * 4, AudioTrackBufferSizing.allocationBytes(minimum, false))
        }
    }

    @Test fun `invalid or unrepresentable platform sizes fail before creating a track`() {
        for (bytes in listOf(-2, -1, 0)) {
            assertThrows(IllegalArgumentException::class.java) { AudioTrackBufferSizing.minimumFrames(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioTrackBufferSizing.allocationBytes(AudioTrackBufferSizing.minimumFrames(Int.MAX_VALUE), true)
        }
    }
}
