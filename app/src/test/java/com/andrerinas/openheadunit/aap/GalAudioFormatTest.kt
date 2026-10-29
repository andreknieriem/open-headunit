package com.andrerinas.openheadunit.aap

import android.app.Application
import android.media.AudioManager
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.audio.*
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.mockito.kotlin.*

// Exercise AudioConfigs' real Android collection and AapAudio's main-loop scheduling.
// Only decoder/device boundaries are mocked; the production App needs native services.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// This audio fixture must not install a process-wide TLS provider for later JVM tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
class GalAudioFormatTest {
    private class Fixture {
        val session = AudioDecoder.PlaybackSession(AudioDecoder.PlaybackCallbacks())
        val decoder = mock<AudioDecoder>()
        var track: AudioTrackWrapper? = null
        var codec = AudioSinkCodec.PCM
        val formats = mutableListOf<Pair<Int, Int>>()
        val settings = mock<Settings> {
            on { playbackFocusMode } doReturn PlaybackFocusPolicy.Mode.AUTO
            on { audioMatchingMode } doReturn AudioTimeCorrectionMode.OHU
        }
        val audio: AapAudio
        init {
            whenever(decoder.openSession(any())).thenReturn(session)
            whenever(decoder.getTrack(any(), eq(session))).thenAnswer { track }
            whenever(decoder.sinkCodecFor(any(), eq(session))).thenAnswer { if (track == null) null else codec }
            doAnswer { track = null; null }.whenever(decoder).stop(any<Int>(), eq(session))
            doAnswer { track = null; null }.whenever(decoder).stop(eq(session))
            doAnswer {
                formats.add(it.getArgument<Int>(2) to it.getArgument<Int>(4))
                codec = if (it.getArgument<Boolean>(5)) AudioSinkCodec.AAC_LC else AudioSinkCodec.PCM
                track = mock {
                    on { canAcceptAudioInput() } doReturn true
                    on { playbackOwner } doReturn Any()
                }
                null
            }.whenever(decoder).start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(session), any())
            audio = AapAudio(decoder, mock<AudioManager>(), settings)
        }
        fun format(rate: Int, channels: Int = 1) = Media.AudioConfiguration.newBuilder().setSampleRate(rate)
            .setNumberOfBits(16).setNumberOfChannels(channels).build()
    }

    @Test fun `laboratory buffer override reaches playback track and Stop clears it`() {
        val f = Fixture()
        val channel = Channel.ID_AUD
        val contract = GalConnectionContract.resolve(galTestContract().negotiation,
            GalDisplaySettings(false, false, false),
            GalLaboratoryOptions(audioBuffer = GalLaboratoryOptions.Mode.ON, audioBufferLimitMs = 75))
        val sessions = GalMediaSessions().apply {
            advertise(contract.media, GalMediaOffer.from(galTestDiscovery()))
        }
        val session = sessions.start(channel, Media.Start.newBuilder()
            .setSessionId(1).setConfigurationIndex(0).build())!!
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE)
        f.audio.selectFormat(channel, (session.format as GalMediaFormat.Audio).value)
        f.audio.applyGalBufferLimit(channel, session.runtime.audioBufferLimitMs)
        verify(f.track!!).setGalBufferLimit(75L)
        clearInvocations(f.track!!)
        sessions.stop(channel)
        f.audio.applyGalBufferLimit(channel, sessions.current(channel)!!.runtime.audioBufferLimitMs)
        verify(f.track!!).setGalBufferLimit(null)
    }

    @Test fun `AAC source timestamp stays metadata and repeated stamps do not drop access units`() {
        val f = Fixture()
        val channel = Channel.ID_AU1
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        f.audio.selectFormat(channel, f.format(16000))
        val bytes = byteArrayOf(0, 0, 0x7f, -1, -1, -1, -1, -1, -1, -1, 0x11, 0x22)
        val packet = AapMessage(channel, 11, 0, 2, bytes.size, bytes)
        repeat(2) { assertTrue(f.audio.process(packet)) }
        assertEquals(Long.MAX_VALUE, f.audio.sourceTimestampUs(channel))
        verify(f.decoder, times(2)).decode(channel, bytes, 10, 2, f.session, Long.MAX_VALUE)
        f.audio.preparePlayback(channel)
        assertNull(f.audio.sourceTimestampUs(channel))
    }

    @Test fun `local restart and sleep preserve active Start identity when DATA rebuilds output`() {
        for (sleep in listOf(false, true)) {
            val f = Fixture()
            val channel = Channel.ID_AUD
            val controller = GalPresentationController()
            val options = GalRuntimeOptions(avSyncRequested = true, avSync = GalRuntimeOptions.AvSync())
            controller.start(channel, 42, options)
            controller.start(Channel.ID_VID, 43, options)
            f.audio.bindPresentationController(controller)
            f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
            f.audio.selectFormat(channel, f.format(48000, 2))
            f.audio.setPresentationGeneration(channel, 42)
            controller.observeAudio(channel, 42, 1_000_000, 1_000_000_000)
            assertEquals(1_040_000_000L,
                controller.videoReleaseTimeNs(43, 1_040_000, 1_000_000_000, 1_000_000_000))
            val oldTrack = f.track
            if (sleep) {
                f.audio.pauseAllAudio()
                verify(f.decoder).pauseAll(f.session)
                // A parked device may retire before DATA resumes; exercise its replacement.
                f.track = null
            } else {
                f.audio.restartAudio()
                verify(f.decoder).stop(f.session)
            }
            assertEquals(0L,
                controller.videoReleaseTimeNs(43, 1_040_000, 1_000_000_000, 1_000_000_000))
            val bytes = ByteArray(14)
            assertTrue(f.audio.process(AapMessage(channel, 11, 0, 2, bytes.size, bytes)))
            val replacement = requireNotNull(f.track)
            assertNotSame(oldTrack, replacement)
            verify(replacement).setPresentation(controller, 42)
            verify(replacement).invalidatePresentation()
            assertTrue(controller.isAudioSyncEnabled(channel, 42))
            controller.observeAudio(channel, 42, 2_000_000, 2_000_000_000)
            assertEquals(2_040_000_000L,
                controller.videoReleaseTimeNs(43, 2_040_000, 2_000_000_000, 2_000_000_000))
        }
    }

    @Test fun `selected PCM and AAC rates and channels rebuild only on format changes`() {
        for (codec in listOf(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)) {
            val f = Fixture()
            val channel = Channel.ID_AU1
            f.audio.noteSinkCodec(channel, codec)
            assertTrue(f.audio.selectFormat(channel, f.format(16000)))
            assertTrue(f.audio.selectFormat(channel, f.format(48000, 2)))
            assertTrue(f.audio.selectFormat(channel, f.format(48000, 2)))
            assertEquals(listOf(16000 to 1, 48000 to 2), f.formats)
            verify(f.decoder, times(1)).stop(channel, f.session)
            for (invalid in listOf(f.format(48000).toBuilder().setNumberOfBits(24).build(), f.format(12345), f.format(48000, 6))) {
                assertFalse(f.audio.selectFormat(channel, invalid))
            }
            assertEquals(listOf(16000 to 1, 48000 to 2), f.formats)
        }
    }

    @Test fun `failed input ownership cannot report selected format ready`() {
        val f = Fixture()
        f.audio.noteSinkCodec(Channel.ID_AU1, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE)
        assertTrue(f.audio.selectFormat(Channel.ID_AU1, f.format(16000)))
        whenever(f.track!!.canAcceptAudioInput()).thenReturn(false)
        assertFalse(f.audio.selectFormat(Channel.ID_AU1, f.format(16000)))
    }

    @Test fun `invalid CSD retires the pending copy before a later format switch`() {
        for (size in listOf(0, 1, 65)) {
            val f = Fixture()
            val channel = Channel.ID_AU1
            f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
            f.audio.selectFormat(channel, f.format(16000))
            val csd = AacCodecConfig.default(48000, 1).copyBytes()
            f.audio.process(AapMessage(channel, 11, 1, 0, csd.size, csd))
            f.audio.process(AapMessage(channel, 11, 1, 0, size, ByteArray(size)))
            f.audio.selectFormat(channel, f.format(48000))
            verify(f.decoder, never()).configure(any(), any(), any(), any(), any())
        }
    }

    @Test fun `CSD before Start is copied and reaches only a matching selected format`() {
        val f = Fixture()
        val channel = Channel.ID_AU1
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        f.audio.selectFormat(channel, f.format(16000))
        val csd = AacCodecConfig.default(48000, 1).copyBytes()
        val expected = csd.copyOf()
        f.audio.process(AapMessage(channel, 11, Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE, 0, csd.size, csd))
        verify(f.decoder, never()).configure(any(), any(), any(), any(), any())
        csd.fill(0)
        f.audio.selectFormat(channel, f.format(48000))
        val data = argumentCaptor<ByteArray>()
        verify(f.decoder).configure(eq(channel), data.capture(), eq(0), eq(expected.size), eq(f.session))
        assertArrayEquals(expected, data.firstValue)
    }

    @Test fun `new Setup cannot replay previous setup CSD into a rebuilt format`() {
        val f = Fixture()
        val channel = Channel.ID_AU1
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        f.audio.selectFormat(channel, f.format(16000))
        val csd = AacCodecConfig.default(48000, 1).copyBytes()
        f.audio.process(AapMessage(channel, 11, 1, 0, csd.size, csd))
        f.audio.noteSinkCodec(channel, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        f.audio.selectFormat(channel, f.format(48000))
        verify(f.decoder, never()).configure(any(), any(), any(), any(), any())
    }
}
