package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
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
                }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
                val video = AapVideo(decoder, settings) { fail("healthy split $split requested recovery") }
                video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
                val session = startVideo(video, galTestSessions(), 101)
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
                        assertTrue(video.process(it, session))
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
        val sessions = galTestSessions()
        field("mediaSessions", sessions)
        startVideo(video, sessions, 101)
        doNothing().whenever(transport).sendMediaAck(any(), any())
        val data = ByteArray(16)
        transport.dispatchVideo(AapMessage(Channel.ID_VID, 11, 0, 2, data.size, data))
        startVideo(video, sessions, 202)
        jobs.single().run()
        verify(transport).sendMediaAck(Channel.ID_VID, 101)
        verify(transport, never()).sendMediaAck(Channel.ID_VID, 202)
        verify(video, never()).process(any(), anyOrNull(), any())
    }

    @Test fun `reused Start id cannot let old queued media enter the replacement decoder`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            val boundary = FragmentBoundary()
            boundary.accept(11, boundary.data)
            val previous = boundary.sessions.current(Channel.ID_VID)!!
            boundary.start(101)
            assertTrue(boundary.sessions.current(Channel.ID_VID)!!.generation > previous.generation)
            boundary.drain()
            assertTrue(boundary.decoded.isEmpty())
            verify(boundary.transport, times(1)).sendMediaAck(Channel.ID_VID, 101)
            boundary.accept(11, boundary.data)
            boundary.drain()
            assertEquals(1, boundary.decoded.size)
            assertArrayEquals(boundary.nal, boundary.decoded.single())
            verify(boundary.transport, times(2)).sendMediaAck(Channel.ID_VID, 101)
        }
    @Test fun `queued fragmented initial CSD survives reader accepting first Start`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            val boundary = FragmentBoundary(startInitially = false)
            val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                0, 0, 0, 1, 0x68, 0x12)
            boundary.accept(9, csd.copyOfRange(0, 8), csd.size)
            boundary.accept(10, csd.copyOfRange(8, csd.size))
            val first = boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                .setSessionId(101).setConfigurationIndex(0).build())!!
            // Reader ownership changes before the worker reaches CSD and its queued Start.
            boundary.drain()
            boundary.video.beginSession(first)
            assertEquals(1, boundary.decoded.size)
            assertArrayEquals(csd.copyOfRange(2, csd.size), boundary.decoded.single())
            verify(boundary.transport, never()).sendMediaAck(any(), any())
            boundary.accept(11, boundary.data)
            boundary.drain()
            assertArrayEquals(boundary.nal, boundary.decoded.last())
            verify(boundary.transport).sendMediaAck(Channel.ID_VID, 101)
        }

    @Test fun `repeated Stop Setup CSD Start preserves fresh queued CSD without flushing it at Start`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            for (split in listOf(0, 8, 15)) {
                val boundary = FragmentBoundary()
                boundary.sessions.stop(Channel.ID_VID) // Stop.
                boundary.sessions.stop(Channel.ID_VID) // A valid Setup also retains inactive ownership.
                val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                    0, 0, 0, 1, 0x68, 0x12)
                if (split != 0) {
                    boundary.accept(9, csd.copyOfRange(0, split), csd.size)
                    boundary.accept(8, byteArrayOf())
                    boundary.accept(10, csd.copyOfRange(split, csd.size))
                } else boundary.accept(11, csd)
                val replacement = boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                    .setSessionId(101).setConfigurationIndex(0).build())!!
                // Reader has accepted Start, but its decoder boundary remains behind the CSD.
                boundary.drain()
                assertEquals("split=$split", 1, boundary.decoded.size)
                assertArrayEquals(csd.copyOfRange(2, csd.size), boundary.decoded.single())
                clearInvocations(boundary.decoder)
                boundary.video.beginSession(replacement)
                verify(boundary.decoder, never()).stop(any())
                boundary.accept(11, boundary.data)
                boundary.drain()
                assertArrayEquals(boundary.nal, boundary.decoded.last())
            }
        }

    @Test fun `fresh pre-Start CSD cannot inherit old DATA CSD or an open fragment run`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            val boundary = FragmentBoundary()
            val oldCsd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                0, 0, 0, 1, 0x68, 0x12, 0x34, 0x56)
            val freshCsd = oldCsd.copyOf().also { it[it.lastIndex] = 0x7f }
            boundary.accept(9, oldCsd.copyOfRange(0, 15), oldCsd.size)
            boundary.drain() // Old decoder has an incomplete CSD run.
            boundary.accept(11, oldCsd) // Old active-owner CSD still waits in the queue.
            boundary.accept(11, boundary.data)
            boundary.sessions.stop(Channel.ID_VID)
            // A late continuation is not a fresh inactive-owner FIRST.
            boundary.transport.dispatchVideo(AapMessage(Channel.ID_VID, 10, 1, 0,
                oldCsd.size - 15, oldCsd.copyOfRange(15, oldCsd.size)))
            boundary.accept(9, freshCsd.copyOfRange(0, 15), freshCsd.size)
            boundary.accept(10, freshCsd.copyOfRange(15, freshCsd.size))
            val replacement = boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                .setSessionId(101).setConfigurationIndex(0).build())!!
            boundary.drain()
            assertEquals(1, boundary.decoded.size)
            assertArrayEquals(freshCsd.copyOfRange(2, freshCsd.size), boundary.decoded.single())
            verify(boundary.decoder).stop("GAL pre-Start CSD")
            clearInvocations(boundary.decoder)
            boundary.video.beginSession(replacement)
            verify(boundary.decoder, never()).stop(any())
            // A captured old inactive owner is stale after the worker's Start boundary too.
            val stale = replacement.copy(generation = replacement.previousGeneration!!, active = false)
            boundary.video.process(AapMessage(Channel.ID_VID, 11, 1, 2, oldCsd.size, oldCsd), stale)
            assertEquals(1, boundary.decoded.size)
        }

    @Test fun `pre-Start CSD belongs only to the immediately following Start`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            for (initial in listOf(false, true)) {
                for (alreadyProcessed in listOf(false, true)) {
                    val boundary = FragmentBoundary(startInitially = !initial)
                    boundary.sessions.stop(Channel.ID_VID)
                    val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                        0, 0, 0, 1, 0x68, 0x12)
                    boundary.accept(11, csd)
                    if (alreadyProcessed) boundary.drain()
                    boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                        .setSessionId(101).setConfigurationIndex(0).build())!!
                    val replacement = boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                        .setSessionId(101).setConfigurationIndex(0).build())!!
                    boundary.drain()
                    assertEquals(if (alreadyProcessed) 1 else 0, boundary.decoded.size)
                    clearInvocations(boundary.decoder)
                    // The intervening Start was superseded before its worker boundary ran.
                    boundary.video.beginSession(replacement)
                    if (alreadyProcessed || !initial) {
                        verify(boundary.decoder).stop("GAL media Start replaced")
                    }
                    boundary.accept(11, boundary.data)
                    boundary.drain()
                    assertArrayEquals(boundary.nal, boundary.decoded.last())
                }
            }
        }

    @Test fun `pre-Start partial CSD cannot complete across the worker Start boundary`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            val boundary = FragmentBoundary()
            boundary.sessions.stop(Channel.ID_VID)
            val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                0, 0, 0, 1, 0x68, 0x12, 0x34, 0x56)
            boundary.accept(9, csd.copyOfRange(0, 15), csd.size)
            val replacement = boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                .setSessionId(101).setConfigurationIndex(0).build())!!
            boundary.drain()
            boundary.video.beginSession(replacement)
            boundary.accept(10, csd.copyOfRange(15, csd.size))
            boundary.drain()
            assertTrue(boundary.decoded.isEmpty())
            boundary.accept(11, csd)
            boundary.drain()
            assertEquals(1, boundary.decoded.size)
            assertArrayEquals(csd.copyOfRange(2, csd.size), boundary.decoded.single())
        }

    @Test fun `repeated Setup retires already prepared CSD before its queued Start`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            for (initial in listOf(false, true)) {
                val boundary = FragmentBoundary(startInitially = !initial)
                boundary.sessions.stop(Channel.ID_VID)
                val csd = byteArrayOf(0, 1, 0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                    0, 0, 0, 1, 0x68, 0x12)
                boundary.accept(11, csd)
                boundary.drain()
                assertEquals(1, boundary.decoded.size)
                clearInvocations(boundary.decoder)
                val setup = byteArrayOf(0x80.toByte(), 0)
                assertFalse(boundary.transport.dispatchVideo(AapMessage(Channel.ID_VID, 11,
                    Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, 2, setup.size, setup)))
                boundary.sessions.stop(Channel.ID_VID)
                val replacement = boundary.sessions.start(Channel.ID_VID, Media.Start.newBuilder()
                    .setSessionId(101).setConfigurationIndex(0).build())!!
                boundary.drain()
                verify(boundary.decoder).stop("GAL Setup replaced pre-Start CSD")
                boundary.video.beginSession(replacement)
                verify(boundary.decoder, times(1)).stop(any())
            }
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
        val sessions = galTestSessions()
        field("mediaSessions", sessions)
        startVideo(video, sessions, 101)
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
        startVideo(video, sessions, 202)
        jobs.forEach { it.run() }
        verify(transport, times(1)).sendMediaAck(Channel.ID_VID, 101)
        verify(transport, never()).sendMediaAck(Channel.ID_VID, 202)
        verify(decoder, never()).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
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
        }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
        val video = AapVideo(decoder, settings()) { fail("new session inherited a fragment run") }
        video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
        val sessions = galTestSessions()
        val session = startVideo(video, sessions, 101)
        val nal = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte(),
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1e)
        val bytes = byteArrayOf(0, 1) + nal
        val first = AapMessageReassembler().accept(
            AapMessage(Channel.ID_VID, 9, 1, 2, bytes.size, bytes), bytes.size + 1)!!
        assertTrue(video.process(first, session))
        video.release()
        val complete = AapMessageReassembler().accept(
            AapMessage(Channel.ID_VID, 11, 1, 2, bytes.size, bytes), 0)!!
        assertTrue(video.process(complete, null))
        assertTrue(decoded.isEmpty())
        video.recordAnnouncedCodec(VideoDecoder.CodecType.H264)
        val replacement = startVideo(video, sessions, 202)
        assertTrue(video.process(complete, replacement))
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
        boundary.start(202)
        boundary.drain()
        assertTrue(boundary.decoded.isEmpty())
        verify(boundary.transport, times(1)).sendMediaAck(Channel.ID_VID, 101)
        verify(boundary.transport, never()).sendMediaAck(Channel.ID_VID, 202)
        // The queued old-generation loss notification must not corrupt the replacement.
        verify(boundary.decoder, never()).noteStreamCorrupted(any())

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
                    boundary.start(202)
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
    private inner class FragmentBoundary(private val auditReader: Boolean = false,
                                         startInitially: Boolean = true) {
        val decoder = mock<VideoDecoder>()
        val decoded = mutableListOf<ByteArray>()
        val video = AapVideo(decoder, settings()) {}
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val sessions = galTestSessions()
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val data = ByteArray(10) + nal
        private val jobs = mutableListOf<Runnable>()
        private val backlog = AtomicInteger()
        private val audit = FragmentedMessageAudit()
        private val reassembler = AapMessageReassembler(onDroppedMediaData = { channel ->
            sessions.ackSession(channel)?.let { transport.sendMediaAck(channel, it) }
        })

        init {
            doAnswer { call ->
                val bytes = call.arguments[0] as ByteArray
                val offset = call.arguments[1] as Int
                decoded.add(bytes.copyOfRange(offset, offset + (call.arguments[2] as Int)))
                null
            }.whenever(decoder).decode(any<ByteArray>(), any(), any(), any(), any(), anyOrNull(), any())
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
            field("mediaSessions", sessions)
            if (startInitially) start(101)
            doNothing().whenever(transport).sendMediaAck(any(), any())
        }

        fun start(id: Int) = startVideo(video, sessions, id)

        fun accept(flags: Int, bytes: ByteArray, total: Int = 0) {
            val first = AapMessageFraming.carriesMessageType(flags)
            val type = if (first && bytes.size >= 2)
                ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255) else -1
            val fragment = AapMessage(Channel.ID_VID, flags.toByte(), type,
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

    private fun startVideo(video: AapVideo, sessions: GalMediaSessions, id: Int): GalMediaSessions.Session =
        sessions.start(Channel.ID_VID, Media.Start.newBuilder().setSessionId(id)
            .setConfigurationIndex(0).build())!!.also { video.beginSession(it) }

    private fun settings(): Settings {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getInt(any(), any())).thenAnswer { it.arguments[1] }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { it.arguments[1] }
        return Settings(context)
    }
}
