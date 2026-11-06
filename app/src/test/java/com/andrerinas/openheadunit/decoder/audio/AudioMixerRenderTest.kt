package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AudioMixerRenderTest {
    /** `renderCycle` as it was before the trim, copied verbatim, with its own limiter and ducking state. */
    private class OldRender(private val duckMediaForSpeech: Boolean) {
        val limiter = MixerSoftClipper()
        private val ducking = MixerDuckingEnvelope(AudioMixer.OUTPUT_SAMPLE_RATE)
        private val mixBuffer = IntArray(960)
        private val mediaBuffer = IntArray(960)
        private val channelBuffer = ShortArray(960)
        private val outputBuffer = ShortArray(960)

        fun renderCycle(nowMs: Long, outputBurstFrames: Int, cycleChannels: Array<AudioMixer.Channel>): ShortArray {
            mixBuffer.fill(0)
            if (duckMediaForSpeech) mediaBuffer.fill(0)
            var activeChannels = 0
            var speechActive = false
            var boosted = false
            for (state in cycleChannels) {
                state.timeStretch?.let { state.buffer.observeTimeCorrection(it.policy, nowMs, outputBurstFrames) }
                val active = state.buffer.render(channelBuffer, nowMs, outputBurstFrames)
                state.timeStretch?.spaceAvailable()
                state.activeThisCycle = active
                val gain = state.gain
                if (active && gain > 0f) activeChannels++
                speechActive = speechActive || (active && !state.isMediaSink && gain > 0f)
                boosted = boosted || (active && gain > 1f)
                if (!active || gain == 0f) continue
                val destination = if (duckMediaForSpeech && state.isMediaSink) mediaBuffer else mixBuffer
                if (gain == 1f) {
                    for (i in destination.indices) destination[i] += channelBuffer[i].toInt()
                } else {
                    for (i in destination.indices) destination[i] += (channelBuffer[i] * gain).toInt()
                }
            }
            ducking.setSpeechActive(duckMediaForSpeech && speechActive)
            limiter.setEnabled(activeChannels > 1 || boosted)
            if (limiter.isSettledDry && (!duckMediaForSpeech || ducking.isSettledUnity)) {
                if (duckMediaForSpeech) {
                    for (i in mixBuffer.indices) {
                        val sample = mixBuffer[i] + mediaBuffer[i].toFloat().toInt()
                        outputBuffer[i] = sample.coerceIn(-32768, 32767).toShort()
                    }
                } else {
                    for (i in mixBuffer.indices)
                        outputBuffer[i] = mixBuffer[i].coerceIn(-32768, 32767).toShort()
                }
                return outputBuffer
            }
            var mediaGain = 1f
            for (i in mixBuffer.indices) {
                if (i % AudioMixer.OUTPUT_CHANNELS == 0) {
                    if (duckMediaForSpeech) mediaGain = ducking.nextGain()
                    limiter.advance()
                }
                if (duckMediaForSpeech) mixBuffer[i] += (mediaBuffer[i] * mediaGain).toInt()
                outputBuffer[i] = limiter.process(mixBuffer[i])
            }
            return outputBuffer
        }
    }

    private class Twins(val now: AudioMixer.Channel, val twin: AudioMixer.Channel)

    /** Each spec (channel id, media sink) gets a channel on the mixer under test and a twin for the old code. */
    private class Rig(duck: Boolean, specs: List<Pair<Int, Boolean>>) {
        val mixer = AudioMixer(duckMediaForSpeech = duck)
        private val twinMixer = AudioMixer(duckMediaForSpeech = duck)
        val old = OldRender(duck)
        val pairs = specs.map { (id, media) ->
            Twins(mixer.registerChannel(id, 48000, 2, 1, media), twinMixer.registerChannel(id, 48000, 2, 1, media))
        }

        fun gain(index: Int, g: Float) { pairs[index].now.gain = g; pairs[index].twin.gain = g }

        fun feed(index: Int, pcm: ShortArray, cycle: Int) {
            for (c in listOf(pairs[index].now, pairs[index].twin)) {
                c.buffer.noteArrival(cycle * 10L, 480)
                c.buffer.write(pcm, pcm.size, cycle * 10L)
            }
        }

        /** Renders one cycle on both sides, asserts equal bytes, and returns the new output. */
        fun render(cycle: Int, label: String): ShortArray {
            val now = cycle * 10L
            val expected = old.renderCycle(now, 480, pairs.map { it.twin }.toTypedArray()).copyOf()
            val out = mixer.renderCycle(now, 480, pairs.map { it.now }.toTypedArray())
            assertArrayEquals("$label cycle=$cycle", expected, out)
            return out
        }
    }

    private fun noise(random: Random, amplitude: Int) =
        ShortArray(960) { (random.nextInt(2 * amplitude + 1) - amplitude).coerceIn(-32768, 32767).toShort() }

    private fun parity(duck: Boolean, gain: Float, media: Boolean, amplitude: Int) {
        val rig = Rig(duck, listOf(4 to media))
        rig.gain(0, gain)
        val random = Random(7)
        var sawSound = false
        repeat(60) { cycle ->
            rig.feed(0, noise(random, amplitude), cycle)
            sawSound = rig.render(cycle, "duck=$duck gain=$gain").any { it != 0.toShort() } || sawSound
        }
        assertTrue("never produced sound", sawSound)
    }

    @Test fun `one channel at unity gain matches the old stage`() = parity(false, 1f, false, 32768)

    @Test fun `one channel below unity matches the old stage`() = parity(false, 0.6f, false, 32768)

    @Test fun `a negative gain that clips stays clamped`() = parity(false, -2f, false, 30000)

    // One media channel at gain 1 or less sums within PCM16, so no input on the dry path separates the
    // Float round trip from a plain add. This checks the ducking dry stage, not the round trip.
    @Test fun `ducking on matches the old stage`() = parity(true, 1f, true, 32768)

    @Test fun `two loud channels match the old limiter path`() {
        val rig = Rig(false, listOf(4 to false, 5 to false))
        val random = Random(11)
        repeat(30) { cycle ->
            rig.feed(0, noise(random, 20000), cycle)
            rig.feed(1, noise(random, 20000), cycle)
            rig.render(cycle, "two channels")
        }
        assertFalse("the limiter never engaged", rig.old.limiter.isSettledDry)
    }

    @Test fun `one fed channel beside an idle one stays on the dry path`() {
        val rig = Rig(false, listOf(4 to false, 5 to false))
        val random = Random(13)
        repeat(30) { cycle ->
            rig.feed(0, noise(random, 32768), cycle)
            rig.render(cycle, "one of two")
            assertTrue("left the dry path at cycle $cycle", rig.old.limiter.isSettledDry)
        }
    }

    @Test fun `silence after a limiter release keeps the release running`() {
        val rig = Rig(false, listOf(4 to false, 5 to false))
        val random = Random(17)
        var cycle = 0
        repeat(20) {
            rig.feed(0, noise(random, 30000), cycle); rig.feed(1, noise(random, 30000), cycle)
            rig.render(cycle++, "loud")
        }
        // Gain 0 on both: nothing is summed while the limiter releases over 5760 frames.
        rig.gain(0, 0f); rig.gain(1, 0f)
        repeat(4) {
            rig.feed(0, noise(random, 30000), cycle); rig.feed(1, noise(random, 30000), cycle)
            val out = rig.render(cycle++, "silent release")
            assertArrayEquals(ShortArray(960), out)
        }
        assertFalse("the release ended before the channel came back", rig.old.limiter.isSettledDry)
        rig.gain(0, 1f)
        repeat(20) {
            rig.feed(0, noise(random, 30000), cycle); rig.feed(1, noise(random, 30000), cycle)
            rig.render(cycle++, "resumed")
        }
        assertTrue("the release never settled", rig.old.limiter.isSettledDry)
    }

    @Test fun `silence returns zeros and every bank still renders`() {
        val mixer = AudioMixer()
        val state = mixer.registerChannel(4, 48000, 2, 1, false)
        repeat(10) { cycle ->
            state.buffer.noteArrival(cycle * 10L, 480)
            state.buffer.write(ShortArray(960) { 1234 }, 960, cycle * 10L)
            mixer.renderCycle(cycle * 10L, 480, arrayOf(state))
        }
        state.buffer.finish()
        var now = 100L
        while (!state.buffer.isIdle() && now < 2000L) {
            mixer.renderCycle(now, 480, arrayOf(state))
            now += 10L
        }
        assertTrue(state.buffer.isIdle())
        assertArrayEquals(ShortArray(960), mixer.renderCycle(now, 480, arrayOf(state)))
        assertEquals(false, state.activeThisCycle)
    }
}
