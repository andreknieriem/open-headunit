package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class VideoTransportBoundaryTest {
    @Test fun `every split including a bare four byte start code reaches the decoder once`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getInt(any(), any())).thenAnswer { it.arguments[1] }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { it.arguments[1] }
        for (type in 0..1) {
            val header = if (type == 0) byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 7) else byteArrayOf(0, 1)
            val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0x34, 0x56)
            val bytes = header + nal
            for (split in 1 until bytes.size) {
                val decoder = mock<VideoDecoder>()
                val decoded = mutableListOf<ByteArray>()
                doAnswer { call ->
                    val data = call.arguments[0] as ByteArray
                    val offset = call.arguments[1] as Int
                    decoded.add(data.copyOfRange(offset, offset + (call.arguments[2] as Int)))
                    null
                }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any())
                val video = AapVideo(decoder, Settings(context)) { fail("healthy split $split requested recovery") }
                val reassembler = AapMessageReassembler()
                var acks = 0
                listOf(9 to bytes.copyOfRange(0, split), 10 to bytes.copyOfRange(split, bytes.size)).forEach { (flags, data) ->
                    val fragment = AapMessage(Channel.ID_VID, flags.toByte(), if (flags == 9 && data.size >= 2) type else -1,
                        if (flags == 9) 2 else 0, data.size, data)
                    reassembler.accept(fragment, if (flags == 9) bytes.size else 0)?.let {
                        if (AapMessageFraming.completesMediaData(it.type, it.flags.toInt())) acks++
                        video.process(it)
                    }
                }
                assertEquals("type=$type split=$split", 1, decoded.size)
                assertArrayEquals(nal, decoded.single())
                assertEquals(if (type == 0) 1 else 0, acks)
                verify(decoder, never()).noteStreamCorrupted(any())
            }
        }
    }

    @Test fun `delayed video completion acknowledges the session captured on the poll thread`() {
        val video = mock<AapVideo>()
        val worker = FakeLaneWorker()
        var session = 101
        val acks = mutableListOf<Pair<Int, Int>>()
        val lane = VideoLane(Channel.ID_VID, video, "test", { session }, { ch, s -> acks.add(ch to s) }, { worker })
        lane.start()
        whenever(video.isPayload(any())).thenReturn(true)
        val data = ByteArray(16)
        lane.dispatch(AapMessage(Channel.ID_VID, 11, 0, 2, data.size, data))
        session = 202
        worker.jobs.single().run()
        assertEquals(listOf(Channel.ID_VID to 101), acks)
    }
}
