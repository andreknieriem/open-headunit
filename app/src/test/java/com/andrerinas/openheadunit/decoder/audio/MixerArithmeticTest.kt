package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class MixerArithmeticTest {
    // Reference the original sample arithmetic, including the envelopes, so changes are
    // checked through prompt transitions rather than only on steady unity-gain music.
    private class Reference(private val duck: Boolean) {
        private val envelope = MixerDuckingEnvelope(48000)
        private val limiter = MixerSoftClipper()
        fun render(channels: List<AudioMixer.Channel>, now: Long): ShortArray {
            val mix = IntArray(960)
            val media = IntArray(960)
            val pcm = ShortArray(960)
            var active = 0
            var speech = false
            var boosted = false
            for (channel in channels) {
                val playing = channel.buffer.render(pcm, now, 480)
                val gain = channel.gain
                if (playing && gain > 0) active++
                speech = speech || (playing && !channel.isMediaSink && gain > 0)
                boosted = boosted || (playing && gain > 1)
                val dest = if (duck && channel.isMediaSink) media else mix
                for (i in pcm.indices) dest[i] += (pcm[i] * gain).toInt()
            }
            envelope.setSpeechActive(duck && speech)
            limiter.setEnabled(active > 1 || boosted)
            var gain = 1f
            return ShortArray(960) { i ->
                if (i % 2 == 0) { gain = envelope.nextGain(); limiter.advance() }
                mix[i] += (media[i] * gain).toInt()
                limiter.process(mix[i])
            }
        }
    }

    @Test fun `optimized mixer preserves muted contributors prompt tails and limiter release`() {
        for (duck in listOf(false, true)) {
            val mixer = AudioMixer(duckMediaForSpeech = duck)
            val referenceMixer = AudioMixer(duckMediaForSpeech = duck)
            val channels = (4..6).map { mixer.registerChannel(it, 48000, 2, 1, it == 4) }
            val references = (4..6).map { referenceMixer.registerChannel(it, 48000, 2, 1, it == 4) }
            val reference = Reference(duck)
            val random = java.util.Random(1170)
            repeat(200) { cycle ->
                for (i in channels.indices) {
                    val gain = when {
                        i == 1 && cycle < 50 -> 0f
                        i == 2 && cycle in 80..90 -> 1.5f
                        i == 0 && cycle in 120..125 -> 0.7f
                        else -> 1f
                    }
                    channels[i].gain = gain; references[i].gain = gain
                    val feed = (i == 0 && cycle < 160) ||
                        (i == 1 && (cycle in 40..55 || cycle in 61..65)) ||
                        (i == 2 && cycle in 80..90)
                    if (feed) {
                        val pcm = ShortArray(960) { (random.nextInt(65536) - 32768).toShort() }
                        for (state in listOf(channels[i], references[i])) {
                            state.buffer.noteArrival(cycle * 10L, 480)
                            state.buffer.write(pcm, pcm.size, cycle * 10L)
                        }
                    }
                    if ((i == 0 && cycle == 160) || (i == 1 && (cycle == 56 || cycle == 66)) || (i == 2 && cycle == 91)) {
                        channels[i].buffer.finish(); references[i].buffer.finish()
                    }
                }
                assertArrayEquals("duck=$duck cycle=$cycle", reference.render(references, cycle * 10L),
                    mixer.renderCycle(cycle * 10L, 480, channels.toTypedArray()))
            }
            for (state in channels) assertTrue(state.buffer.isIdle())
        }
    }

    @Test fun `settled mixing preserves rounding of large media sums before cancellation`() {
        for (duck in listOf(false, true)) {
            val mixer = AudioMixer(duckMediaForSpeech = duck)
            val referenceMixer = AudioMixer(duckMediaForSpeech = duck)
            val channels = (4..6).map { mixer.registerChannel(it, 48000, 2, 1, it != 6) }
            val references = (4..6).map { referenceMixer.registerChannel(it, 48000, 2, 1, it != 6) }
            val reference = Reference(duck)
            // Gains are not restricted to positive values. Keep these contributors dry,
            // while media exceeds Float's exact integer range and the other sink cancels it.
            val gains = floatArrayOf(-1024f, -1f, -1024f)
            val samples = arrayOf(
                ShortArray(960) { if (it % 2 == 0) 20000 else -20000 },
                ShortArray(960) { if (it % 2 == 0) 1 else -1 },
                ShortArray(960) { if (it % 2 == 0) -20000 else 20000 }
            )
            repeat(30) { cycle ->
                for (i in channels.indices) {
                    for (state in listOf(channels[i], references[i])) {
                        state.gain = gains[i]
                        state.buffer.noteArrival(cycle * 10L, 480)
                        state.buffer.write(samples[i], samples[i].size, cycle * 10L)
                    }
                }
                assertArrayEquals("duck=$duck cycle=$cycle", reference.render(references, cycle * 10L),
                    mixer.renderCycle(cycle * 10L, 480, channels.toTypedArray()))
            }
        }
    }
}
