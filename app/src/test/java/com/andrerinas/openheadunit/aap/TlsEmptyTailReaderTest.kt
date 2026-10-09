package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class TlsEmptyTailReaderTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    @org.junit.Before fun stubClock() { clock = org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java) }
    @org.junit.After fun restoreClock() { clock.close() }

    @Test fun singleReaderCompletesEmptyTail() = exercise(false)
    @Test fun bulkReaderCompletesEmptyTail() = exercise(true)

    private fun exercise(bulk: Boolean) {
        val settings = mock<Settings>()
        whenever(settings.debugVideoFaultInjection).thenReturn(VideoFaultInjector.Mode.OFF)
        whenever(settings.videoCodec).thenReturn("h264")
        val decoder = mock<VideoDecoder>()
        var corruptions = 0
        val video = AapVideo(decoder, settings) { corruptions++ }
        val decoded = mutableListOf<List<Byte>>()
        doAnswer {
            val data = it.getArgument<ByteArray>(0)
            val offset = it.getArgument<Int>(1)
            val size = it.getArgument<Int>(2)
            decoded.add(data.copyOfRange(offset, offset + size).toList())
            null
        }.whenever(decoder).decode(any(), any(), any(), any(), any())
        val payload = byteArrayOf(0, 1, 0, 0, 0, 1, 0x65)
        val ssl = mock<AapSsl>()
        whenever(ssl.decrypt(any(), any(), any())).thenAnswer {
            val start = it.getArgument<Int>(0)
            val data = it.getArgument<ByteArray>(2)
            if (data[start] == 2.toByte()) ByteArrayWithLimit(ByteArray(16), 0)
            else ByteArrayWithLimit(payload.copyOf(16), payload.size)
        }
        // FIRST declares plaintext bytes, including the type. The two body tokens stand in for TLS records.
        val wire = byteArrayOf(3, 9, 0, 1, 0, 0, 0, 7, 1, 3, 10, 0, 1, 2, 3, 11, 0, 1, 3)
        var position = 0
        val connection = mock<ProjectionConnection>()
        whenever(connection.recvBlocking(any(), any(), any(), any())).thenAnswer {
            val buffer = it.getArgument<ByteArray>(0)
            val count = minOf(it.getArgument<Int>(1), wire.size - position)
            wire.copyInto(buffer, 0, position, position + count)
            position += count
            count
        }
        val handler = object : AapMessageHandler {
            override fun handle(message: AapMessage) { video.process(message) }
        }
        val reader = if (bulk) AapReadMultipleMessages(connection, ssl, handler)
            else AapReadSingleMessage(connection, ssl, handler)
        repeat(if (bulk) 1 else 3) { assertEquals(0, reader.read()) }
        assertEquals(listOf(listOf<Byte>(0, 0, 0, 1, 0x65), listOf<Byte>(0, 0, 0, 1, 0x65)), decoded)
        assertEquals(0, corruptions)
    }
}
