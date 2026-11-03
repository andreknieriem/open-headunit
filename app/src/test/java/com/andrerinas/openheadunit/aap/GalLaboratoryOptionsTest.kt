package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.GalLaboratoryOptions.Mode
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class GalLaboratoryOptionsTest {
    private fun contract(options: GalLaboratoryOptions, profile: GalProfile = GalProfile.GAL_6_0) =
        GalConnectionContract.resolve(
            GalSessionProfile(profile, AapVersionNegotiation.Result(6, 1, 0, null)),
            GalDisplaySettings(true, true, true), options)

    private fun sessions(options: GalLaboratoryOptions, profile: GalProfile = GalProfile.GAL_6_0) =
        GalMediaSessions().apply { advertise(contract(options, profile).media, GalMediaOffer.from(galTestDiscovery())) }

    private fun start(options: Media.MediaOptions? = null) = Media.Start.newBuilder()
        .setSessionId(7).setConfigurationIndex(0).apply { if (options != null) setOptions(options) }.build()

    private val forced = GalLaboratoryOptions(avSync = Mode.ON, videoTiming = Mode.ON,
        audioBuffer = Mode.ON, mediaStats = Mode.ON)

    @Test fun `default laboratory settings preserve existing decoded runtime requests exactly`() {
        val phone = Media.MediaOptions.newBuilder().setEnableAvSync(true).setEnableMediaStats(true)
            .setEnableVideoTimingController(true).setAudioBufferLimit(GalMediaStatistics.durationMs(200))
            .setVideoTimingControllerMinDelay(GalMediaStatistics.durationMs(10))
            .setVideoTimingControllerMaxDelay(GalMediaStatistics.durationMs(30)).build()
        val sessions = sessions(GalLaboratoryOptions())
        for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
            assertEquals(GalRuntimeOptions.from(phone), sessions.start(channel, start(phone))!!.runtime)
        }
    }

    @Test fun `saved forced settings cannot promote legacy profile on higher phone response`() {
        val saved = GalLaboratoryOptions.fromStored(forced.storedValues())
        for (profile in listOf(GalProfile.COMPATIBILITY, GalProfile.GAL_1_6)) {
            val contract = contract(saved, profile)
            val sessions = sessions(saved, profile)
            for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
                val session = sessions.start(channel, start())!!
                assertEquals(GalRuntimeOptions(), session.runtime)
                assertFalse(sessions.update(channel, Media.MediaOptions.newBuilder().setEnableAvSync(true).build()))
            }
            assertEquals(profile, contract.negotiation.profile)
            assertNull(contract.display.initial(0, 0))
            assertEquals(GalMediaPolicy.NO_OPTIONS, contract.media.optionsFor(Channel.ID_VID))
        }
        assertEquals(GalLaboratoryCapabilities.forProfile(GalProfile.GAL_6_0),
            GalLaboratoryCapabilities.forProfile(GalProfile.GAL_6_1))
    }

    @Test fun `local forced modes affect real presentation and statistics consumers and stop is inert`() {
        val sessions = sessions(forced.copy(videoMinDelayMs = 15, statisticsIntervalMs = 250))
        val audio = sessions.start(Channel.ID_AUD, start())!!
        val video = sessions.start(Channel.ID_VID, start())!!
        assertNull(audio.runtime.videoTiming)
        assertNull(video.runtime.audioBufferLimitMs)
        assertEquals(100L, audio.runtime.audioBufferLimitMs)
        assertEquals(GalRuntimeOptions.VideoTiming(15_000_000, 33_000_000), video.runtime.videoTiming)
        assertNull(audio.options)
        assertNull(video.options)
        val presentation = GalPresentationController()
        presentation.start(Channel.ID_AUD, audio.generation, audio.runtime)
        presentation.start(Channel.ID_VID, video.generation, video.runtime)
        assertEquals(100_000_000L, presentation.audioStartDelayNs(Channel.ID_AUD, audio.generation, 0, 0))
        assertEquals(1_015_000_000L,
            presentation.videoReleaseTimeNs(video.generation, 0, 1_000_000_000, 1_000_000_000))
        val stats = GalMediaStatistics()
        val sample = GalPlaybackSample(Any(), 100L, 2, 3)
        assertNull(stats.observe(Channel.ID_AUD, audio, sample, 0))
        assertNotNull(stats.observe(Channel.ID_AUD, audio, sample, 250))
        for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
            sessions.stop(channel)
            assertEquals(GalRuntimeOptions(), sessions.current(channel)!!.runtime)
            assertFalse(sessions.update(channel, Media.MediaOptions.getDefaultInstance()))
            assertNotEquals(GalRuntimeOptions(), sessions.start(channel, start())!!.runtime)
        }
    }

    @Test fun `AUTO sparse updates explicit false and Start reset retain raw phone ownership`() {
        val local = GalLaboratoryOptions(maxVideoDelayMs = 40, statisticsIntervalMs = 750,
            audioBufferLimitMs = 80, videoMinDelayMs = 20)
        val sessions = sessions(local)
        val phone = Media.MediaOptions.newBuilder().setEnableAvSync(true).setEnableMediaStats(true)
            .setAudioBufferLimit(GalMediaStatistics.durationMs(200))
            .setMediaStatsReportInterval(GalMediaStatistics.durationMs(500)).build()
        val first = sessions.start(Channel.ID_AUD, start(phone))!!
        assertSame(phone, first.options)
        assertEquals(80L, first.runtime.audioBufferLimitMs)
        assertEquals(750L, first.runtime.statisticsIntervalMs)
        assertEquals(40_000_000L, first.runtime.avSync!!.maxVideoDelayNs)
        val sparse = Media.MediaOptions.newBuilder().setSyncInterval(GalMediaStatistics.durationMs(300)).build()
        assertTrue(sessions.update(Channel.ID_AUD, sparse))
        val updated = sessions.current(Channel.ID_AUD)!!
        assertEquals(300_000_000L, updated.runtime.avSync!!.syncIntervalNs)
        assertEquals(200_000_000L, GalRuntimeOptions.nanos(updated.options!!.audioBufferLimit))
        assertEquals(750L, updated.runtime.statisticsIntervalMs)
        sessions.update(Channel.ID_AUD, Media.MediaOptions.newBuilder().setEnableMediaStats(false)
            .setEnableAvSync(false).build())
        assertNull(sessions.current(Channel.ID_AUD)!!.runtime.statisticsIntervalMs)
        assertNull(sessions.current(Channel.ID_AUD)!!.runtime.avSync)
        assertEquals(GalRuntimeOptions(), sessions.start(Channel.ID_AUD, start())!!.runtime)
        // A numeric override does not enable an unrequested timing controller.
        assertNull(sessions.start(Channel.ID_VID, start())!!.runtime.videoTiming)
        assertTrue(phone.enableAvSync)
        assertTrue(phone.enableMediaStats)
    }

    @Test fun `ON survives explicit false while OFF wins over retained numeric overrides`() {
        val phone = Media.MediaOptions.newBuilder().setEnableAvSync(true).setEnableMediaStats(true)
            .setEnableVideoTimingController(true).setAudioBufferLimit(GalMediaStatistics.durationMs(200))
            .setVideoTimingControllerMinDelay(GalMediaStatistics.durationMs(10))
            .setVideoTimingControllerMaxDelay(GalMediaStatistics.durationMs(30)).build()
        val off = forced.copy(avSync = Mode.OFF, videoTiming = Mode.OFF, audioBuffer = Mode.OFF,
            mediaStats = Mode.OFF, audioBufferLimitMs = 50, statisticsIntervalMs = 250,
            videoMinDelayMs = 20, maxVideoDelayMs = 20)
        for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
            val disabled = sessions(off).start(channel, start(phone))!!
            assertEquals(GalRuntimeOptions(), disabled.runtime)
            val enabled = sessions(forced)
            enabled.start(channel, start(phone))
            enabled.update(channel, Media.MediaOptions.newBuilder().setEnableAvSync(false)
                .setEnableMediaStats(false).setEnableVideoTimingController(false).build())
            val current = enabled.current(channel)!!
            assertFalse(current.options!!.enableAvSync)
            assertNotNull(current.runtime.avSync)
            assertNotNull(current.runtime.statisticsIntervalMs)
            if (channel == Channel.ID_VID) assertNotNull(current.runtime.videoTiming)
        }
    }

    @Test fun `ON retains dormant peer numeric requests without rewriting raw options`() {
        val phone = Media.MediaOptions.newBuilder().setEnableAvSync(false)
            .setSyncInterval(GalMediaStatistics.durationMs(321)).setAverageLagWeight(7)
            .setMaxVideoDelayAllowed(GalMediaStatistics.durationMs(222)).build()
        val session = sessions(forced).start(Channel.ID_VID, start(phone))!!
        assertSame(phone, session.options)
        assertFalse(session.options!!.enableAvSync)
        assertEquals(321_000_000L, session.runtime.avSync!!.syncIntervalNs)
        assertEquals(222_000_000L, session.runtime.avSync!!.maxVideoDelayNs)
        assertEquals(7L, session.runtime.avSync!!.averageLagWeight)
    }

    @Test fun `updates use independent eligible channel gates without suppressing embedded requests`() {
        val phone = Media.MediaOptions.newBuilder().setEnableMediaStats(true).build()
        for (profile in GalProfile.entries) for (audioEnabled in listOf(false, true)) for (videoEnabled in listOf(false, true)) {
            val caps = GalLaboratoryCapabilities.forProfile(profile)
            val sessions = sessions(GalLaboratoryOptions(audioUpdates = audioEnabled, videoUpdates = videoEnabled), profile)
            for (channel in listOf(Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_VID)) {
                val session = sessions.start(channel, start(phone))!!
                assertEquals(caps.mediaOptions, session.runtime.statisticsIntervalMs != null)
                assertEquals(if (channel == Channel.ID_VID) caps.videoUpdates && videoEnabled else caps.audioUpdates && audioEnabled,
                    sessions.update(channel, Media.MediaOptions.newBuilder().setEnableMediaStats(false).build()))
            }
            assertNull(sessions.start(Channel.ID_MIC, start(phone)))
            assertFalse(sessions.update(Channel.ID_MIC, phone))
        }
    }

    @Test fun `bounded imported numbers and mixed phone local timing ranges remain coherent`() {
        val normalized = GalLaboratoryOptions(maxLagToleranceMs = -1, syncIntervalMs = 0,
            maxAudioDelayMs = 501, maxVideoDelayMs = 999, averageLagWeight = -1, syncOffsetMs = -501,
            videoMinDelayMs = 300, videoMaxDelayMs = -1, audioBufferLimitMs = 0, statisticsIntervalMs = Int.MAX_VALUE).normalized()
        assertEquals(GalLaboratoryOptions(maxLagToleranceMs = 0, syncIntervalMs = 1,
            maxAudioDelayMs = 500, maxVideoDelayMs = 500, averageLagWeight = 0, syncOffsetMs = -500,
            videoMinDelayMs = 250, videoMaxDelayMs = 250, audioBufferLimitMs = 1, statisticsIntervalMs = 60000), normalized)
        val phone = Media.MediaOptions.newBuilder().setEnableVideoTimingController(true)
            .setVideoTimingControllerMinDelay(GalMediaStatistics.durationMs(100))
            .setVideoTimingControllerMaxDelay(GalMediaStatistics.durationMs(200)).build()
        val min = sessions(GalLaboratoryOptions(videoMinDelayMs = 220)).start(Channel.ID_VID, start(phone))!!
        assertEquals(GalRuntimeOptions.VideoTiming(220_000_000, 220_000_000), min.runtime.videoTiming)
        val max = sessions(GalLaboratoryOptions(videoMaxDelayMs = 20)).start(Channel.ID_VID, start(phone))!!
        assertEquals(GalRuntimeOptions.VideoTiming(100_000_000, 100_000_000), max.runtime.videoTiming)
        assertEquals(200_000_000L, GalRuntimeOptions.nanos(phone.videoTimingControllerMaxDelay))
    }

    @Test fun `connection snapshot keeps its options and display while next connection sees saved changes`() {
        var saved = forced
        val before = contract(saved)
        saved = saved.copy(audioUpdates = false, modernDisplayConfig = false, mediaStats = Mode.OFF)
        val after = contract(saved)
        assertTrue(before.media.optionsFor(Channel.ID_AUD).standalone)
        assertFalse(after.media.optionsFor(Channel.ID_AUD).standalone)
        assertNotNull(before.display.initial(0, 0))
        assertNull(after.display.initial(0, 0))
        val discovery = galTestDiscovery().toBuilder()
        after.display.applyDiscoveryVisibility(discovery)
        assertEquals(7, discovery.sessionConfiguration)
        assertTrue(discovery.hideProjectedClock)
        val sessions = GalMediaSessions().apply { advertise(before.media, GalMediaOffer.from(galTestDiscovery())) }
        assertEquals(5000L, sessions.start(Channel.ID_AUD, start())!!.runtime.statisticsIntervalMs)
        assertNull(sessions(saved).start(Channel.ID_AUD, start())!!.runtime.statisticsIntervalMs)
    }

    @Test fun `actual guidance offer toggle preserves 16k alternative and selected format`() {
        val baseline = Media.AudioConfiguration.newBuilder().setSampleRate(16000)
            .setNumberOfBits(16).setNumberOfChannels(1).build()
        for (profile in GalProfile.entries) for (enabled in listOf(false, true)) {
            val contract = contract(GalLaboratoryOptions(guidance48Khz = enabled), profile)
            val sink = Control.Service.MediaSinkService.newBuilder()
                .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
            contract.applyGuidanceConfigurations(sink, baseline)
            val expected = if (enabled && GalLaboratoryCapabilities.forProfile(profile).guidance48Khz)
                listOf(48000, 16000) else listOf(16000)
            assertEquals(expected, sink.audioConfigsList.map { it.sampleRate })
            val discovery = galTestDiscovery().toBuilder().clearServices().addServices(
                Control.Service.newBuilder().setId(Channel.ID_AU1).setMediaSinkService(sink)).build()
            val sessions = GalMediaSessions().apply { advertise(contract.media, GalMediaOffer.from(discovery)) }
            for ((index, rate) in expected.withIndex()) {
                val session = sessions.start(Channel.ID_AU1, start().toBuilder().setConfigurationIndex(index).build())!!
                assertEquals(rate, (session.format as GalMediaFormat.Audio).value.sampleRate)
            }
        }
    }

    @Test fun `primitive persistence round trips and malformed fields fall back independently`() {
        val options = forced.copy(guidance48Khz = false, modernDisplayConfig = false, audioUpdates = false,
            videoUpdates = false, syncOffsetMs = -50, audioBufferLimitMs = 250)
        assertEquals(options, GalLaboratoryOptions.fromStored(options.storedValues()))
        val corrupt = options.storedValues().toMutableMap().apply {
            put("gal-laboratory-guidance48Khz", "false")
            put("gal-laboratory-avSync", "unknown")
            put("gal-laboratory-syncOffsetMs", Long.MAX_VALUE)
            put("gal-laboratory-audioBufferLimitMs", Int.MAX_VALUE)
        }
        assertEquals(options.copy(guidance48Khz = true, avSync = Mode.AUTO,
            syncOffsetMs = null, audioBufferLimitMs = 1500), GalLaboratoryOptions.fromStored(corrupt))
        assertEquals(GalLaboratoryOptions(), GalLaboratoryOptions.fromStored(emptyMap<String, Any>()))
    }
}
