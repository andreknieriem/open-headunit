package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class GalVideoSessionTest {
    private fun session(generation: Long) = GalMediaSessions.Session(
        7, 0, GalMediaFormat.Video(Control.Service.MediaSinkService.VideoConfiguration.getDefaultInstance()),
        false, true, null, generation)

    private class Fixture {
        val decoder = mock<VideoDecoder>()
        val settings = mock<Settings> {
            on { debugVideoFaultInjection } doReturn VideoFaultInjector.Mode.OFF
        }
        val video = AapVideo(decoder, settings) { fail("stale generation requested repair") }
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33) + ByteArray(12) { 0x7f }
        val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 42) + nal
        fun packet(flags: Int = 11, data: ByteArray = bytes) =
            AapMessage(Channel.ID_VID, flags.toByte(), 0,
                if (flags == 8 || flags == 10) 0 else 2, data.size, data)
    }

    @Test fun `same wire session id with new generation rejects queued data and loss callbacks`() =
        mockStatic(SystemClock::class.java).use {
            val f = Fixture()
            f.video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
            val old = session(1)
            val current = session(2)
            f.video.beginSession(old)
            f.video.process(f.packet(9, f.bytes.copyOfRange(0, 16)), old)
            f.video.beginSession(current)
            f.video.process(f.packet(10, f.bytes.copyOfRange(16, f.bytes.size)), old)
            f.video.process(f.packet(), old)
            f.video.onFragmentRunHoled(true, old)
            verify(f.decoder, never()).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
            verify(f.decoder, never()).noteStreamCorrupted(any())
            verify(f.decoder, times(1)).stop("GAL media Start replaced")
            assertNull(f.video.lastSourceTimestampUs)
            assertTrue(f.video.process(f.packet(), current))
            verify(f.decoder).decode(same(f.bytes), eq(10), eq(f.nal.size), eq(false), eq(VideoDecoder.CodecType.H264), eq(42L), any())
            assertEquals(42L, f.video.lastSourceTimestampUs)
        }

    @Test fun `fragmented source timestamp and arrival belong to FIRST not arbitrary tail bytes`() =
        mockStatic(SystemClock::class.java).use {
            val f = Fixture()
            f.video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
            val owner = session(1)
            f.video.beginSession(owner)
            f.video.process(f.packet(9, f.bytes.copyOfRange(0, 16)), owner, 123_000L)
            assertNull(f.video.lastSourceTimestampUs)
            f.video.process(f.packet(10, f.bytes.copyOfRange(16, f.bytes.size)), owner, 456_000L)
            assertEquals(42L, f.video.lastSourceTimestampUs)
            val data = argumentCaptor<ByteArray>()
            verify(f.decoder).decode(data.capture(), eq(0), eq(f.nal.size), eq(false), eq(VideoDecoder.CodecType.H264), eq(42L), eq(123_000L))
            assertArrayEquals(f.nal, data.firstValue.copyOf(f.nal.size))
            val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0x11)
            f.video.process(AapMessage(Channel.ID_VID, 11, 1, 2, csd.size, csd), owner)
            assertNull(f.video.lastSourceTimestampUs)
        }

    @Test fun `startup announcement guard and release reject captured old ownership`() =
        mockStatic(SystemClock::class.java).use {
            val f = Fixture()
            val owner = session(1)
            f.video.beginSession(owner)
            assertTrue(f.video.process(f.packet(), owner))
            verify(f.decoder, never()).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
            f.video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
            f.video.process(f.packet(), owner)
            f.video.release()
            f.video.process(f.packet(), owner)
            verify(f.decoder, times(1)).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
            assertNull(f.video.lastSourceTimestampUs)
        }
}
