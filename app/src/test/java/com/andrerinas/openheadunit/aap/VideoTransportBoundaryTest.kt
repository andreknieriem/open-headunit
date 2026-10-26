package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class VideoTransportBoundaryTest {
    @Test fun `whole and every split preserve PPS first CSD and ignore start codes in DATA timestamps`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val settings = settings()
        for (type in 0..1) {
            // Both envelopes have a start code at offsets 2 and 10. For CSD, the first is
            // an eight-byte PPS that must survive; for DATA, the first is only timestamp bytes.
            val header = if (type == 0) byteArrayOf(0, 0, 0, 0, 0, 1, 0x12, 0x34, 0, 7) else byteArrayOf(0, 1)
            val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
            val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0x12, 0x34)
            val nal = if (type == 1) pps + sps else byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0x34, 0x56)
            val bytes = header + nal
            // Zero selects COMPLETE; the final split exercises a legal empty LAST. Every
            // fragmented run also contains an empty MIDDLE, with no repeated type/timestamp.
            for (split in 0..bytes.size) {
                val decoder = mock<VideoDecoder>()
                val decoded = mutableListOf<ByteArray>()
                doAnswer { call ->
                    val data = call.arguments[0] as ByteArray
                    val offset = call.arguments[1] as Int
                    decoded.add(data.copyOfRange(offset, offset + (call.arguments[2] as Int)))
                    null
                }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any())
                val video = AapVideo(decoder, settings) { fail("healthy split $split requested recovery") }
                video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
                val reassembler = AapMessageReassembler()
                var acks = 0
                val fragments = if (split == 0) listOf(11 to bytes) else listOf(
                    9 to bytes.copyOfRange(0, split), 8 to byteArrayOf(), 10 to bytes.copyOfRange(split, bytes.size)
                )
                fragments.forEach { (flags, data) ->
                    val first = AapMessageFraming.carriesMessageType(flags)
                    val fragment = AapMessage(Channel.ID_VID, flags.toByte(), if (first && data.size >= 2) type else -1,
                        if (first) 2 else 0, data.size, data)
                    reassembler.accept(fragment, if (flags == 9) bytes.size else 0)?.let {
                        assertEquals(type, it.type)
                        assertTrue("type=$type split=$split flags=${it.flags}", video.isPayload(it))
                        if (AapMessageFraming.completesMediaData(it.type, it.flags.toInt())) acks++
                        assertTrue(video.process(it))
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
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val video = mock<AapVideo>()
        val handler = mock<Handler>()
        val jobs = mutableListOf<Runnable>()
        whenever(handler.post(any())).thenAnswer { jobs.add(it.arguments[0] as Runnable); true }
        fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        field("aapVideo", video)
        field("videoHandler", handler)
        field("videoBacklog", AtomicInteger())
        field("videoBufferPool", LinkedBlockingQueue<ByteArray>())
        whenever(video.isPayload(any())).thenReturn(true)
        whenever(video.announcedCodec).thenReturn(VideoDecoder.CodecType.H264)
        doReturn(101).whenever(transport).getSessionId(Channel.ID_VID)
        doNothing().whenever(transport).sendMediaAck(any(), any())
        val data = ByteArray(16)
        transport.dispatchVideo(AapMessage(Channel.ID_VID, 11, 0, 2, data.size, data))
        doReturn(202).whenever(transport).getSessionId(Channel.ID_VID)
        jobs.single().run()
        verify(transport).sendMediaAck(Channel.ID_VID, 101)
        verify(transport, never()).sendMediaAck(Channel.ID_VID, 202)
    }
    @Test fun `timestamp false positives and channel controls never decode but DATA still returns its session credit`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val decoder = mock<VideoDecoder>()
        val video = AapVideo(decoder, settings()) { fail("complete rejected packets need no recovery") }
        video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val handler = mock<Handler>()
        val jobs = mutableListOf<Runnable>()
        whenever(handler.post(any())).thenAnswer { jobs.add(it.arguments[0] as Runnable); true }
        fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        field("aapVideo", video)
        field("videoHandler", handler)
        field("videoBacklog", AtomicInteger())
        field("videoBufferPool", LinkedBlockingQueue<ByteArray>())
        doReturn(101).whenever(transport).getSessionId(Channel.ID_VID)
        doNothing().whenever(transport).sendMediaAck(any(), any())
        // DATA has a start code only in its timestamp. It is not CSD, but owns one credit.
        val invalidData = byteArrayOf(0, 0, 0, 0, 0, 1, 0x65, 0x12, 0, 7, 0x12, 0x34)
        // Service setup with Annex B-looking bytes must still fall through to the control handler.
        val control = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 1, 0x67, 0x12, 0, 7, 0, 0, 0, 1, 0x68)
        val reassembler = AapMessageReassembler()
        for (bytes in listOf(invalidData, control)) {
            val type = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            val message = reassembler.accept(AapMessage(Channel.ID_VID, 11, type, 2, bytes.size, bytes), 0)!!
            assertFalse(video.isPayload(message))
            assertFalse(transport.dispatchVideo(message))
        }
        verify(transport, never()).sendMediaAck(any(), any())
        doReturn(202).whenever(transport).getSessionId(Channel.ID_VID)
        jobs.forEach { it.run() }
        verify(transport, times(1)).sendMediaAck(Channel.ID_VID, 101)
        verify(transport, never()).sendMediaAck(Channel.ID_VID, 202)
        verify(decoder, never()).decode(any<ByteArray>(), any(), any(), any(), any())
        verify(decoder, never()).noteStreamCorrupted(any())
    }

    @Test fun `release discards an open run and premature CSD without leaking into the next session`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val decoder = mock<VideoDecoder>()
        val decoded = mutableListOf<ByteArray>()
        doAnswer { call ->
            val bytes = call.arguments[0] as ByteArray
            val offset = call.arguments[1] as Int
            decoded.add(bytes.copyOfRange(offset, offset + (call.arguments[2] as Int)))
            null
        }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any())
        val video = AapVideo(decoder, settings()) { fail("new session inherited a fragment run") }
        video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
        val nal = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte(),
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1e)
        val bytes = byteArrayOf(0, 1) + nal
        val first = AapMessageReassembler().accept(
            AapMessage(Channel.ID_VID, 9, 1, 2, bytes.size, bytes), bytes.size + 1)!!
        assertTrue(video.process(first))
        video.release()
        val complete = AapMessageReassembler().accept(
            AapMessage(Channel.ID_VID, 11, 1, 2, bytes.size, bytes), 0)!!
        assertTrue(video.process(complete))
        assertTrue(decoded.isEmpty())
        video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
        assertTrue(video.process(complete))
        assertEquals(1, decoded.size)
        assertArrayEquals(nal, decoded.single())
        verify(decoder, never()).noteStreamCorrupted(any())
    }

    @Test fun `shed middle stays discarded through surviving middles and LAST returns its original session credit`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val boundary = FragmentBoundary()
        boundary.accept(9, boundary.data.copyOfRange(0, 16), boundary.data.size)
        boundary.drain()
        boundary.shedMiddle()
        // The hole notification is queued before these survivors, not immediately before LAST.
        boundary.accept(8, boundary.data.copyOfRange(18, 19))
        boundary.accept(8, boundary.data.copyOfRange(19, 20))
        boundary.accept(10, boundary.data.copyOfRange(20, boundary.data.size))
        doReturn(202).whenever(boundary.transport).getSessionId(Channel.ID_VID)
        boundary.drain()
        assertTrue(boundary.decoded.isEmpty())
        verify(boundary.transport, times(1)).sendMediaAck(Channel.ID_VID, 101)
        verify(boundary.transport, never()).sendMediaAck(Channel.ID_VID, 202)
        verify(boundary.decoder, times(1)).noteStreamCorrupted(any())

        boundary.accept(11, boundary.data)
        boundary.healthyFragmentedRun()
        boundary.drain()
        assertEquals(2, boundary.decoded.size)
        boundary.decoded.forEach { assertArrayEquals(boundary.nal, it) }
        verify(boundary.transport, times(2)).sendMediaAck(Channel.ID_VID, 202)
        verify(boundary.transport, times(3)).sendMediaAck(eq(Channel.ID_VID), any())
    }

    @Test fun `reader audit at LAST drops only the short run and still acknowledges DATA`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val boundary = FragmentBoundary(auditReader = true)
        boundary.accept(9, boundary.data.copyOfRange(0, 16), boundary.data.size)
        // Unlike worker shedding, these missing bytes never reach the reassembler or audit.
        boundary.accept(8, boundary.data.copyOfRange(18, 20))
        boundary.accept(10, boundary.data.copyOfRange(20, boundary.data.size))
        boundary.drain()
        assertTrue(boundary.decoded.isEmpty())
        verify(boundary.transport, times(1)).sendMediaAck(Channel.ID_VID, 101)
        verify(boundary.decoder, times(1)).noteStreamCorrupted(any())

        boundary.healthyFragmentedRun()
        boundary.drain()
        assertEquals(1, boundary.decoded.size)
        assertArrayEquals(boundary.nal, boundary.decoded.single())
        verify(boundary.transport, times(2)).sendMediaAck(Channel.ID_VID, 101)
    }

    @Test fun `replacement FIRST whole and release cannot transfer a lost run verdict to healthy video`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        for (replacement in listOf("FIRST", "whole", "release")) {
            val boundary = FragmentBoundary()
            boundary.accept(9, boundary.data.copyOfRange(0, 16), boundary.data.size)
            boundary.drain()
            boundary.shedMiddle()
            boundary.drain()
            // A repair-only finding must not erase a previous worker-loss verdict.
            boundary.transport.dispatchVideoRunHoled(false)
            boundary.drain()
            when (replacement) {
                "whole" -> boundary.accept(11, boundary.data)
                "release" -> {
                    boundary.video.release()
                    boundary.video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
                }
            }
            boundary.healthyFragmentedRun()
            boundary.drain()
            assertEquals(replacement, if (replacement == "whole") 2 else 1, boundary.decoded.size)
            boundary.decoded.forEach { assertArrayEquals(boundary.nal, it) }
        }
    }

    /** Real reassembly, dispatch, loss notification and video assembly; only worker scheduling
     * and the device decoder are doubled so queue order and decoded payloads remain observable. */
    private inner class FragmentBoundary(private val auditReader: Boolean = false) {
        val decoder = mock<VideoDecoder>()
        val decoded = mutableListOf<ByteArray>()
        val video = AapVideo(decoder, settings()) {}
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val data = ByteArray(10) + nal
        private val jobs = mutableListOf<Runnable>()
        private val backlog = AtomicInteger()
        private val audit = FragmentedMessageAudit()
        private val reassembler = AapMessageReassembler(onDroppedMediaData = { channel ->
            transport.sendMediaAck(channel, transport.getSessionId(channel))
        })

        init {
            doAnswer { call ->
                val bytes = call.arguments[0] as ByteArray
                val offset = call.arguments[1] as Int
                decoded.add(bytes.copyOfRange(offset, offset + (call.arguments[2] as Int)))
                null
            }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any())
            video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
            val handler = mock<Handler>()
            whenever(handler.post(any())).thenAnswer { jobs.add(it.arguments[0] as Runnable); true }
            fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(transport, value)
            field("aapVideo", video)
            field("videoHandler", handler)
            field("videoBacklog", backlog)
            field("videoShedTotal", AtomicLong())
            field("videoBufferPool", LinkedBlockingQueue<ByteArray>())
            doReturn(101).whenever(transport).getSessionId(Channel.ID_VID)
            doNothing().whenever(transport).sendMediaAck(any(), any())
        }

        fun accept(flags: Int, bytes: ByteArray, total: Int = 0) {
            val first = AapMessageFraming.carriesMessageType(flags)
            val fragment = AapMessage(Channel.ID_VID, flags.toByte(), if (first) 0 else -1,
                if (first) 2 else 0, bytes.size, bytes)
            val message = reassembler.accept(fragment, total)
            if (auditReader) {
                val result = audit.onMessage(Channel.ID_VID, flags, bytes.size, total)
                if (result != null && AuditRecoveryPolicy.shouldRequestKeyframe(result.outcome, result.channel)) {
                    transport.dispatchVideoRunHoled(message != null &&
                        AuditRecoveryPolicy.shouldDiscardAssembledUnit(result))
                }
            }
            if (message != null) assertTrue(transport.dispatchVideo(message))
        }

        fun shedMiddle() {
            // Force the actual transport shedding branch without manufacturing queued frames.
            // Call only after draining, so restoring the depth cannot mask pending worker work.
            backlog.set(Int.MAX_VALUE)
            accept(8, data.copyOfRange(16, 18))
            backlog.set(0)
        }

        fun healthyFragmentedRun() {
            accept(9, data.copyOfRange(0, 16), data.size)
            accept(8, data.copyOfRange(16, 20))
            accept(10, data.copyOfRange(20, data.size))
        }

        fun drain() {
            while (jobs.isNotEmpty()) jobs.removeAt(0).run()
        }
    }

    private fun settings(): Settings {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getInt(any(), any())).thenAnswer { it.arguments[1] }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { it.arguments[1] }
        return Settings(context)
    }
}
