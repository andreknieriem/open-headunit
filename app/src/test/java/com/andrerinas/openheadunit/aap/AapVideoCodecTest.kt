package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.*

class AapVideoCodecTest {
    private fun decodedCodecs(announced: VideoDecoder.CodecType?): List<VideoDecoder.CodecType> =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            val settings = mock<Settings>()
            whenever(settings.videoCodec).thenReturn(VideoDecoder.CodecType.H265.settingsValue)
            whenever(settings.forceSoftwareDecoding).thenReturn(true)
            whenever(settings.debugVideoFaultInjection).thenReturn(VideoFaultInjector.Mode.OFF)
            val decoder = mock<VideoDecoder>()
            val video = AapVideo(decoder, settings) { fail("a healthy frame requested recovery") }
            announced?.let(video::recordAnnouncedCodec)
            val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 1, 0x65, 0x12, 0x34)
            video.process(AapMessage(Channel.ID_VID2, 11, 0, 2, bytes.size, bytes))
            val codec = argumentCaptor<VideoDecoder.CodecType>()
            verify(decoder, atMost(1)).decode(any(), any(), any(), any(), codec.capture())
            codec.allValues
        }

    @Test fun `the announced codec wins over the codec setting`() {
        assertEquals(listOf(VideoDecoder.CodecType.H264), decodedCodecs(VideoDecoder.CodecType.H264))
    }

    @Test fun `nothing is decoded before a codec is announced`() {
        assertTrue(decodedCodecs(null).isEmpty())
    }
}
