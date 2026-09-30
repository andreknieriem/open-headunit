package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class RenderedDuckingTest {
    private fun media(mixer: AudioMixer): AudioMixer.Channel =
        mixer.registerChannel(4, 48000, 2, 2, true).also {
            it.buffer.write(ShortArray(48000) { 10000 }, 48000, 0)
            it.buffer.finish()
        }

    @Test fun `packet arrival does not duck music until buffered speech is rendered`() {
        val mixer = AudioMixer(duckMediaForSpeech = true)
        media(mixer)
        val speech = mixer.registerChannel(5, 16000, 1, 4)
        speech.buffer.write(ShortArray(960) { 1000 }, 960, 0)
        assertEquals(10000, mixer.renderCycle(0, 480).last().toInt())
        assertEquals(10000, mixer.renderCycle(10, 480).last().toInt())
        // Sink Stop makes the short prompt playable. Its PCM is still present and audible.
        mixer.finishChannel(speech)
        assertEquals(5000, mixer.renderCycle(20, 480).last().toInt())
        assertEquals(0, speech.buffer.depthFrames())
        for (now in 30L..140L step 10) mixer.renderCycle(now, 480)
        assertEquals(10000, mixer.renderCycle(150, 480).last().toInt())
    }

    @Test fun `one stopped prompt cannot restore music over another prompt`() {
        val mixer = AudioMixer(duckMediaForSpeech = true)
        media(mixer)
        val guidance = mixer.registerChannel(5, 16000, 1, 4)
        val notification = mixer.registerChannel(6, 16000, 1, 4)
        guidance.buffer.write(ShortArray(960) { 1000 }, 960, 0)
        notification.buffer.write(ShortArray(9600) { 1000 }, 9600, 0)
        mixer.finishChannel(guidance)
        mixer.finishChannel(notification)
        assertEquals(6000, mixer.renderCycle(0, 480).last().toInt())
        for (now in 10L..90L step 10) {
            assertEquals(5000, mixer.renderCycle(now, 480).last().toInt())
        }
    }

    @Test fun `independently routed or muted speech does not attenuate media`() {
        for (duck in listOf(false, true)) {
            val mixer = AudioMixer(duckMediaForSpeech = duck)
            media(mixer)
            val speech = mixer.registerChannel(5, 16000, 1, 4)
            speech.buffer.write(ShortArray(960) { 1000 }, 960, 0)
            speech.gain = if (duck) 0f else 1f
            mixer.finishChannel(speech)
            assertEquals(if (duck) 10000 else 11000, mixer.renderCycle(0, 480).last().toInt())
        }
    }

    @Test fun `muted prompt does not compress full scale music`() {
        val mixer = AudioMixer(duckMediaForSpeech = true)
        val music = mixer.registerChannel(4, 48000, 2, 2, true)
        music.buffer.write(ShortArray(960) { 30000 }, 960, 0)
        mixer.finishChannel(music)
        val speech = mixer.registerChannel(5, 16000, 1, 4)
        speech.buffer.write(ShortArray(960) { 1000 }, 960, 0)
        speech.gain = 0f
        mixer.finishChannel(speech)
        assertEquals(30000, mixer.renderCycle(0, 480).last().toInt())
    }
}
