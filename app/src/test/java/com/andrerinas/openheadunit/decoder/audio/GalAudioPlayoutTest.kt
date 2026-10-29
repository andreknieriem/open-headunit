package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class GalAudioPlayoutTest {
    private val options = GalRuntimeOptions(avSyncRequested = true,
        avSync = GalRuntimeOptions.AvSync(maxVideoDelayNs = 250_000_000))
    private fun clock() = GalPresentationController().also {
        it.start(Channel.ID_AUD, 10, options)
        it.start(Channel.ID_VID, 20, options)
        it.videoReleaseTimeNs(20, 0, 0, 0) // Ready, not a fabricated presentation.
    }
    private class Output : PcmOutput {
        override val name = "deterministic frame counter"
        override val capacityFrames = 19200
        override val bufferFrames = 960
        override val burstFrames = 480
        override val underruns = 0
        override var writtenFrames = 0L
        override var outputEpoch = 0L
        override fun playedFrames() = 0L
        override fun setBufferFrames(frames: Int) = frames
        override fun start() = Unit
        override fun pause() = Unit
        override fun write(data: ShortArray, offset: Int, count: Int) = count
        override fun close() = Unit
    }

    @Test fun `video follows actual output cursor not bank render or write time`() {
        val clock = clock()
        val mixer = AudioMixer()
        val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
        state.buffer.write(ShortArray(2880), 2880, 0, 1_000_000, 10)
        state.buffer.finish()
        val output = Output()
        mixer.renderCycle(0, 480)
        output.writtenFrames = 480
        mixer.recordRealOutput(output, 0, 0, false, arrayOf(state))
        mixer.renderCycle(10, 480)
        output.writtenFrames = 960
        mixer.recordRealOutput(output, 0, 480, false, arrayOf(state))
        assertEquals(0L, clock.audioObservationCount)
        mixer.publishPlayout(output, 720, 1_000_000_000, arrayOf(state))
        assertEquals(1L, clock.audioObservationCount)
        assertEquals(1_014_979L, clock.lastAudioSourceUs)
        assertEquals(1_000_000_000L, clock.lastAudioPresentationNs)
        assertEquals(1_040_000_000L,
            clock.videoReleaseTimeNs(20, 1_054_979, 1_000_000_000, 1_000_000_000))
        // A frozen hardware head is not a new measurement at a later time.
        mixer.publishPlayout(output, 720, 1_100_000_000, arrayOf(state))
        assertEquals(1L, clock.audioObservationCount)
        output.outputEpoch++
        mixer.publishPlayout(output, 720, 1_100_000_000, arrayOf(state))
        assertEquals(0L, clock.videoReleaseTimeNs(20, 1_054_979, 1_100_000_000, 1_100_000_000))
    }

    @Test fun `recovery staging and old start metadata cannot become new audio observation`() {
        val clock = clock()
        val mixer = AudioMixer()
        val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
        state.buffer.write(ShortArray(2880), 2880, 0, 0, 10)
        state.buffer.finish()
        val output = Output()
        mixer.renderCycle(0, 480)
        mixer.renderCycle(10, 480)
        output.writtenFrames = 480
        mixer.recordRealOutput(output, 0, 0, true, arrayOf(state))
        mixer.publishPlayout(output, 240, 1_000_000_000, arrayOf(state))
        assertEquals(0L, clock.audioObservationCount)
        clock.start(Channel.ID_AUD, 11, options)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 11)
        mixer.renderCycle(20, 480) // Remaining source still belongs to Start 10.
        output.writtenFrames = 960
        mixer.recordRealOutput(output, 0, 480, false, arrayOf(state))
        mixer.publishPlayout(output, 720, 1_010_000_000, arrayOf(state))
        assertEquals(0L, clock.audioObservationCount)
    }

    @Test fun `initial media wait never stalls ready speech and disabled mode creates no observation`() {
        val clock = GalPresentationController()
        clock.start(Channel.ID_AUD, 10, options)
        clock.start(Channel.ID_VID, 20, options)
        val mixer = AudioMixer()
        val music = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(music, clock, Channel.ID_AUD, 10)
        music.buffer.write(ShortArray(1920) { 1000 }, 1920, 0, 0, 10)
        music.buffer.finish()
        val speech = mixer.registerChannel(Channel.ID_AU1, 48000, 2)
        speech.buffer.write(ShortArray(960) { 2000 }, 960, 0)
        speech.buffer.finish()
        assertEquals(2000, mixer.renderCycle(0, 480, nowNs = 0).last().toInt())
        assertEquals(960, music.buffer.depthFrames())
        assertEquals(0, speech.buffer.depthFrames())
        clock.update(Channel.ID_AUD, 10, GalRuntimeOptions())
        clock.update(Channel.ID_VID, 20, GalRuntimeOptions())
        mixer.renderCycle(10, 480)
        assertEquals(480, music.buffer.depthFrames())
        assertFalse(music.sourceMappingWasEnabled)
        assertEquals(0L, clock.audioObservationCount)
    }

    @Test fun `local suspension discards old mapping but resumed PCM keeps its Start identity`() {
        val clock = clock()
        val mixer = AudioMixer()
        val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
        state.buffer.write(ShortArray(2880), 2880, 0, 1_000_000, 10)
        state.buffer.finish()
        val output = Output()
        repeat(2) { cycle ->
            mixer.renderCycle(cycle * 10L, 480)
            output.writtenFrames += 480
            mixer.recordRealOutput(output, 0, cycle * 480L, false, arrayOf(state))
        }
        mixer.publishPlayout(output, 720, 1_000_000_000, arrayOf(state))
        assertEquals(1L, clock.audioObservationCount)
        mixer.invalidatePresentation(state)
        assertEquals(10L, state.presentationGeneration)
        assertTrue(state.initialAudioRendered) // A local resume cannot rearm initial waiting.
        assertEquals(Long.MIN_VALUE, state.buffer.firstSourceUs())
        assertEquals(0L, clock.videoReleaseTimeNs(20, 1_054_979, 1_000_000_000, 1_000_000_000))
        state.buffer.write(ShortArray(960), 960, 20, 2_000_000, 10)
        state.buffer.finish()
        repeat(2) { cycle ->
            mixer.renderCycle(20 + cycle * 10L, 480)
            val before = output.writtenFrames
            output.writtenFrames += 480
            mixer.recordRealOutput(output, 0, before, false, arrayOf(state))
        }
        mixer.publishPlayout(output, 1680, 2_000_000_000, arrayOf(state))
        assertEquals(2L, clock.audioObservationCount)
        assertEquals(2_004_979L, clock.lastAudioSourceUs)
        assertEquals(2_040_000_000L,
            clock.videoReleaseTimeNs(20, 2_044_979, 2_000_000_000, 2_000_000_000))
    }

    @Test fun `playback head crossing an unknown span cannot retain an older AV anchor`() {
        val clock = clock()
        val mixer = AudioMixer()
        val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
        val output = Output()
        state.sourceOutputEpoch = output.outputEpoch
        state.playoutSources.append(0, 480, 0, 10)
        mixer.publishPlayout(output, 480, 1_000_000_000, arrayOf(state))
        assertEquals(9_979L, clock.lastAudioSourceUs)

        // A coarse playback head skips a whole concealed/unattributable block.
        // The new known block has a different source origin, without a new Start.
        state.playoutSources.append(480, 480, Long.MIN_VALUE, 10)
        state.playoutSources.append(960, 480, 500_000, 10)
        mixer.publishPlayout(output, 1440, 1_020_000_000, arrayOf(state))
        assertEquals(509_979L, clock.lastAudioSourceUs)
        assertEquals(1_060_000_000L,
            clock.videoReleaseTimeNs(20, 549_979, 1_020_000_000, 1_020_000_000))
    }

    @Test fun `adjacent source jump rebases the AV clock without waiting for its correction interval`() {
        val clock = clock()
        val mixer = AudioMixer()
        val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
        val output = Output()
        state.sourceOutputEpoch = output.outputEpoch
        state.playoutSources.append(0, 480, 1_000_000, 10)
        mixer.publishPlayout(output, 480, 1_000_000_000, arrayOf(state))
        state.playoutSources.append(480, 480, 1_500_000, 10)
        mixer.publishPlayout(output, 960, 1_010_000_000, arrayOf(state))
        assertEquals(1_509_979L, clock.lastAudioSourceUs)
        assertEquals(1_050_000_000L,
            clock.videoReleaseTimeNs(20, 1_549_979, 1_010_000_000, 1_010_000_000))
    }

    @Test fun `continuous crossed spans preserve smoothing rather than rebasing every observation`() {
        val clock = clock()
        val mixer = AudioMixer()
        val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
        mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
        val output = Output()
        state.sourceOutputEpoch = output.outputEpoch
        state.playoutSources.append(0, 480, 0, 10)
        mixer.publishPlayout(output, 480, 1_000_000_000, arrayOf(state))
        state.playoutSources.append(480, 480, 10_000, 10)
        mixer.publishPlayout(output, 960, 1_030_000_000, arrayOf(state))
        assertEquals(19_979L, clock.lastAudioSourceUs)
        assertEquals(1_050_000_000L,
            clock.videoReleaseTimeNs(20, 59_979, 1_030_000_000, 1_030_000_000))
    }
    @Test fun `packet quantization cannot bypass AV smoothing at unaligned playback heads`() {
        for (packetFrames in intArrayOf(1024, 2048)) {
            val clock = clock()
            val mixer = AudioMixer()
            val state = mixer.registerChannel(Channel.ID_AUD, 48000, 2)
            mixer.setPresentation(state, clock, Channel.ID_AUD, 10)
            val cursor = AudioSourceCursor()
            val packet = ShortArray(packetFrames * 2) { 1000 }
            repeat(8) { index ->
                val wireUs = 1_000_000L + index * packetFrames * 1_000_000L / 48000
                val sourceUs = cursor.advance(wireUs, packetFrames, 48000, 10)
                state.buffer.write(packet, packet.size, index * packetFrames * 1000L / 48000, sourceUs, 10)
            }
            state.buffer.finish()
            val output = Output()
            repeat(8) { cycle ->
                mixer.renderCycle(cycle * 10L, 480)
                val before = output.writtenFrames
                output.writtenFrames += 480
                mixer.recordRealOutput(output, 0, before, false, arrayOf(state))
            }

            // Input packet boundaries do not coincide with 480-frame mixer blocks.
            // The two heads also put the old endpoint inside, not at the start of, its block.
            mixer.publishPlayout(output, 3360, 1_000_000_000, arrayOf(state))
            val previousSource = clock.lastAudioSourceUs
            assertTrue(previousSource in 1_069_977L..1_069_979L)
            mixer.publishPlayout(output, 3840, 1_030_000_000, arrayOf(state))
            val videoSource = clock.lastAudioSourceUs + 40_000
            assertEquals("continuous $packetFrames-frame packets retain the prior clock anchor",
                1_000_000_000L + (videoSource - previousSource) * 1000,
                clock.videoReleaseTimeNs(20, videoSource, 1_030_000_000, 1_030_000_000))
        }
    }
}
