package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GalMediaControlTest {
    private class Fixture(advertised: Boolean = true) {
        val sessions = if (advertised) galTestSessions() else GalMediaSessions()
        val transport = mock<AapTransport>()
        val audio = mock<AapAudio>()
        val sent = mutableListOf<AapMessage>()
        val control = AapControlMedia(transport, mock(), audio)
        init {
            whenever(transport.mediaSessions).thenReturn(sessions)
            whenever(transport.galContract).thenReturn(galTestContract())
            whenever(audio.selectFormat(any(), any())).thenReturn(true)
            whenever(transport.beginVideoSession(any())).thenReturn(true)
            doAnswer { sent += it.getArgument<AapMessage>(0); null }.whenever(transport).send(any<AapMessage>())
            whenever(transport.sendVideoSetupResponse(any())).thenAnswer {
                sent += it.getArgument<AapMessage>(0); true
            }
        }
        fun setup(channel: Int, codec: Int) = control.execute(AapMessage(channel,
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, Media.MediaSetupRequest.newBuilder().setType(codec).build()))
        fun start(channel: Int, id: Int, index: Int = 0) = control.execute(AapMessage(channel,
            Media.MsgType.MEDIA_MESSAGE_START_VALUE, Media.Start.newBuilder().setSessionId(id)
                .setConfigurationIndex(index).build()))
    }

    @Test fun `Setup refuses absent or mismatched offers without READY`() {
        for (advertised in listOf(false, true)) {
            val f = Fixture(advertised)
            assertEquals(-1, f.setup(Channel.ID_AUD, Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE))
            assertTrue(f.sent.isEmpty())
            verify(f.transport).quit()
            verify(f.audio, never()).selectFormat(any(), any())
        }
    }

    @Test fun `Setup returns only advertised indices and selects the actual audio format`() {
        val f = Fixture()
        assertEquals(0, f.setup(Channel.ID_AUD, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE))
        val config = f.sent.single { it.type == Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE }
            .parse(Media.Config.newBuilder()).build()
        assertEquals(Media.Config.ConfigStatus.STATUS_READY, config.status)
        assertEquals(listOf(0), config.configurationIndicesList)
        val format = (f.sessions.format(Channel.ID_AUD, 0) as GalMediaFormat.Audio).value
        verify(f.audio).selectFormat(Channel.ID_AUD, format)
        verify(f.transport, never()).quit()
    }

    @Test fun `failed audio format ownership cannot publish READY`() {
        val f = Fixture()
        whenever(f.audio.selectFormat(any(), any())).thenReturn(false)
        assertEquals(-1, f.setup(Channel.ID_AUD, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE))
        assertTrue(f.sent.isEmpty())
        verify(f.transport).quit()
    }

    @Test fun `Start validates audio format ownership before runtime and playback`() {
        for (channel in listOf(Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2)) {
            val f = Fixture()
            assertEquals(0, f.start(channel, 41))
            val session = f.sessions.current(channel)!!
            val format = (session.format as GalMediaFormat.Audio).value
            inOrder(f.audio, f.transport) {
                verify(f.audio).selectFormat(channel, format)
                verify(f.transport).startMediaRuntime(channel, session)
                verify(f.transport).noteAudioSinkStarted(channel)
                verify(f.audio).preparePlayback(channel)
            }
            verify(f.transport, never()).quit()
        }
    }

    @Test fun `failed Start audio ownership cannot publish runtime or playback`() {
        val f = Fixture()
        whenever(f.audio.selectFormat(any(), any())).thenReturn(false)
        assertEquals(-1, f.start(Channel.ID_AUD, 41))
        verify(f.transport).quit()
        verify(f.transport, never()).startMediaRuntime(any(), any())
        verify(f.transport, never()).noteAudioSinkStarted(any())
        verify(f.audio, never()).preparePlayback(any())
    }

    @Test fun `Start owns only advertised sinks and replacement receives a fresh generation`() {
        val f = Fixture()
        assertEquals(0, f.start(Channel.ID_VID, 41))
        val first = f.sessions.current(Channel.ID_VID)!!
        assertEquals(0, f.start(Channel.ID_VID, 41))
        val replacement = f.sessions.current(Channel.ID_VID)!!
        assertTrue(replacement.generation > first.generation)
        verify(f.transport).beginVideoSession(first)
        verify(f.transport).beginVideoSession(replacement)
        assertEquals(-1, f.start(Channel.ID_VID, 99, 8))
        assertSame(replacement, f.sessions.current(Channel.ID_VID))
        assertEquals(-1, f.start(Channel.ID_MIC, 99))
        assertNull(f.sessions.current(Channel.ID_MIC))
        verify(f.transport, times(2)).quit()
    }

    @Test fun `stopped channel ignores malformed options rather than parsing or changing another sink`() {
        val f = Fixture()
        f.start(Channel.ID_AUD, 5)
        f.start(Channel.ID_AU1, 6)
        val other = f.sessions.current(Channel.ID_AU1)
        f.sessions.stop(Channel.ID_AUD)
        clearInvocations(f.transport)
        assertEquals(0, f.control.execute(AapMessage(Channel.ID_AUD, 11,
            Media.MsgType.MEDIA_MESSAGE_OPTIONS_VALUE, 0, 1, byteArrayOf(-1))))
        assertSame(other, f.sessions.current(Channel.ID_AU1))
        assertFalse(f.sessions.current(Channel.ID_AUD)!!.active)
        verify(f.transport, never()).applyMediaRuntime(any())
    }
}
