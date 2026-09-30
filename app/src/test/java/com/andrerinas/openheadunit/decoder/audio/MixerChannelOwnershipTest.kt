package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class MixerChannelOwnershipTest {
    @Test fun `delayed old wrapper cleanup cannot unregister its replacement`() {
        val mixer = AudioMixer()
        val old = mixer.registerChannel(4, 48000, 2)
        val current = mixer.registerChannel(4, 16000, 1)
        current.buffer.write(ShortArray(960) { 123 }, 960, 0)
        mixer.unregisterChannel(4, old)
        assertEquals(480, mixer.depthFramesFor(4))
        mixer.unregisterChannel(4, current)
        assertEquals(0, mixer.depthFramesFor(4))
    }

    @Test fun `late PCM gain and stop on retired registration leave new audio intact`() {
        val mixer = AudioMixer()
        val old = mixer.registerChannel(4, 48000, 2)
        val current = mixer.registerChannel(4, 48000, 2)
        old.buffer.write(ShortArray(960), 960, 0)
        old.gain = 0f
        mixer.finishChannel(old)
        assertEquals(0, current.buffer.depthFrames())
        assertEquals(1f, current.gain, 0f)
        current.buffer.noteArrival(0, 480)
        current.buffer.write(ShortArray(960), 960, 0)
        assertFalse(current.buffer.render(ShortArray(960), 0)) // replacement still needs its preroll
    }
}
