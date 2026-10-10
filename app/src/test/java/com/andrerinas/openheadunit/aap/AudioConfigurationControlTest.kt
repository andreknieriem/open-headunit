package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.google.protobuf.Message
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/** Real protobuf dispatch: Setup advertises indices and Start applies one before playback. */
class AudioConfigurationControlTest {
    private fun message(channel: Int, type: Int, proto: Message): AapMessage {
        val bytes = proto.toByteArray()
        return AapMessage(channel, 0, type, 0, bytes.size, bytes)
    }

    @Test fun `Setup replies with permitted indices after recording the codec`() {
        for (indices in listOf(listOf(0), listOf(0, 1))) {
            val transport = mock<AapTransport>()
            val audio = mock<AapAudio>()
            val control = AapControlMedia(transport, mock<MicRecorder>(), audio)
            whenever(audio.configurationIndices(4)).thenReturn(indices)
            val request = Media.MediaSetupRequest.newBuilder().setType(1).build()
            control.execute(message(4, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, request))
            val replies = argumentCaptor<AapMessage>()
            verify(transport, times(2)).send(replies.capture())
            val response = replies.allValues.first().parse(Media.Config.newBuilder()).build()
            assertEquals(indices, response.configurationIndicesList)
            assertEquals(Media.Config.ConfigStatus.STATUS_READY, response.status)
            inOrder(audio, transport) {
                verify(audio).noteSinkCodec(4, 1)
                verify(audio).configurationIndices(4)
                verify(transport).send(replies.allValues.first())
                verify(audio).precreateAudioTrack(4)
            }
        }
    }

    @Test fun `Start records wire session and applies selection before preparing playback`() {
        val transport = mock<AapTransport>()
        val audio = mock<AapAudio>()
        val control = AapControlMedia(transport, mock<MicRecorder>(), audio)
        whenever(audio.selectConfiguration(4, 1)).thenReturn(true)
        val request = Media.Start.newBuilder().setSessionId(27).setConfigurationIndex(1).build()
        control.execute(message(4, Media.MsgType.MEDIA_MESSAGE_START_VALUE, request))
        inOrder(audio, transport) {
            verify(transport).setSessionId(4, 27)
            verify(transport).noteAudioSinkStarted(4)
            verify(audio).selectConfiguration(4, 1)
            verify(audio).preparePlayback(4)
        }
    }

    @Test fun `invalid selection suppresses playback but keeps ACK session current`() {
        val transport = mock<AapTransport>()
        val audio = mock<AapAudio>()
        val control = AapControlMedia(transport, mock<MicRecorder>(), audio)
        val request = Media.Start.newBuilder().setSessionId(27).setConfigurationIndex(99).build()
        control.execute(message(4, Media.MsgType.MEDIA_MESSAGE_START_VALUE, request))
        verify(audio).selectConfiguration(4, 99)
        verify(audio, never()).preparePlayback(any())
        verify(transport).setSessionId(4, 27)
        verify(transport).noteAudioSinkStarted(4)
    }

    @Test fun `video Setup retains its single configuration`() {
        val transport = mock<AapTransport>()
        whenever(transport.settings).thenReturn(mock())
        val audio = mock<AapAudio>()
        val control = AapControlMedia(transport, mock<MicRecorder>(), audio)
        val request = Media.MediaSetupRequest.newBuilder().setType(3).build()
        control.execute(message(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, request))
        val reply = argumentCaptor<AapMessage>()
        verify(transport).send(reply.capture())
        assertEquals(listOf(0), reply.firstValue.parse(Media.Config.newBuilder()).build().configurationIndicesList)
        verifyNoInteractions(audio)
    }
}
