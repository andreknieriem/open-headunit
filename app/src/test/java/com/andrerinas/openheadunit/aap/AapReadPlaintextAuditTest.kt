package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import java.nio.ByteBuffer

/**
 * Exercises the real socket-style and USB-style readers at the TLS output boundary. The fake
 * unwrap removes synthetic overhead and reuses an oversized array, so auditing enc_len or array
 * capacity instead of the returned limit fails. This is not a TLS capture or a cipher-size model.
 */
class AapReadPlaintextAuditTest {
    private data class Packet(val flags: Int, val bytes: ByteArray, val total: Int = 0, val overhead: Int = 29,
                              val channel: Int = Channel.ID_VID) {
        fun wire(): ByteArray {
            val bodyLength = overhead + bytes.size
            val header = byteArrayOf(channel.toByte(), flags.toByte(),
                (bodyLength ushr 8).toByte(), bodyLength.toByte())
            val totalBytes = if (flags and 3 == 1) ByteBuffer.allocate(4).putInt(total).array() else byteArrayOf()
            // The first synthetic body byte encodes overhead for our fake unwrap only.
            return header + totalBytes + ByteArray(overhead) { overhead.toByte() } + bytes
        }
    }

    private data class Observation(val events: List<String>, val payloads: List<ByteArray>, val unwraps: Int)

    private fun read(packets: List<Packet>, bulk: Boolean, injector: VideoFaultInjector? = null,
                     chunkSize: Int = 65536, beforeHandle: (AapMessage) -> Unit = {},
                     failDecryptAt: Int = -1, fatalAtUnwrap: Int = failDecryptAt,
                     delegate: AapMessageHandler? = null): Observation {
        val wire = java.io.ByteArrayOutputStream().apply {
            packets.forEach { write(it.wire()) }
        }.toByteArray()
        var position = 0
        val connection = mock<ProjectionConnection>()
        whenever(connection.isConnected).thenAnswer { position < wire.size }
        whenever(connection.recvBlocking(any(), any(), any(), any())).thenAnswer { call ->
            val buffer = call.arguments[0] as ByteArray
            val requested = call.arguments[1] as Int
            val readFully = call.arguments[3] as Boolean
            if (position == wire.size) -1 else {
                val count = minOf(requested, wire.size - position, if (readFully) Int.MAX_VALUE else chunkSize)
                wire.copyInto(buffer, 0, position, position + count)
                position += count
                count
            }
        }
        val ssl = mock<AapSsl>()
        val plaintext = ByteArray(65536)
        var unwraps = 0
        whenever(ssl.decrypt(any(), any(), any())).thenAnswer { call ->
            unwraps++
            if (unwraps == failDecryptAt) throw javax.net.ssl.SSLException("invalid TLS record")
            val start = call.arguments[0] as Int
            val length = call.arguments[1] as Int
            val data = call.arguments[2] as ByteArray
            val overhead = data[start].toInt() and 255
            plaintext.fill(0x7f)
            data.copyInto(plaintext, 0, start + overhead, start + length)
            ByteArrayWithLimit(plaintext, length - overhead)
        }
        val events = mutableListOf<String>()
        val payloads = mutableListOf<ByteArray>()
        val handler = object : AapMessageHandler {
            override fun handle(message: AapMessage) {
                beforeHandle(message)
                events += "data:${message.flags.toInt() and 255}"
                // Match the production asynchronous handoff's borrowed-buffer ownership rule.
                payloads += message.data.copyOfRange(0, message.size)
                delegate?.handle(message)
            }
            override fun onDroppedMediaData(channel: Int) {
                delegate?.onDroppedMediaData(channel)
            }
        }
        val recovery: (Boolean) -> Unit = { events += "repair:$it" }
        val reader: AapRead = if (bulk)
            AapReadMultipleMessages(connection, ssl, handler, recovery, injector)
        else AapReadSingleMessage(connection, ssl, handler, recovery, injector)
        var calls = 0
        while (position < wire.size) {
            val result = reader.read()
            if (fatalAtUnwrap > 0 && unwraps == fatalAtUnwrap) {
                assertEquals(-1, result)
                return Observation(events, payloads, unwraps)
            }
            assertEquals("bulk=$bulk events=$events", 0, result)
            check(++calls <= wire.size + 1) { "Reader made no progress" }
        }
        return Observation(events, payloads, unwraps)
    }

    @Test fun `recorded fragment counts remain healthy through both reader unwrap boundaries`() =
        mockStatic(SystemClock::class.java).use {
            // Counts from the original MT50 measurement; payload and TLS bytes are synthetic.
            val counts = listOf(3, 7, 8, 8, 7, 7, 4, 2, 2, 2, 2, 3, 5, 4, 8, 4, 4, 3, 3, 3, 3)
            for (bulk in listOf(false, true)) for (overhead in listOf(29, 37)) {
                val packets = counts.flatMap { count ->
                    List(count) { part -> Packet(when (part) { 0 -> 9; count - 1 -> 10; else -> 8 },
                        ByteArray(16), if (part == 0) count * 16 else 0, overhead) }
                }
                val result = read(packets, bulk)
                assertEquals(packets.size, result.unwraps)
                assertEquals(packets.size, result.payloads.size)
                assertFalse(result.events.any { event -> event.startsWith("repair:") })
            }
        }

    @Test fun `one byte and empty tails survive USB splits and socket reads`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) for (tailSize in 0..1) {
                val packets = listOf(Packet(9, ByteArray(16), 16 + tailSize), Packet(10, ByteArray(tailSize)))
                val result = read(packets, bulk, chunkSize = 1)
                assertEquals(listOf("data:9", "data:10"), result.events)
                assertEquals(2, result.unwraps)
                assertEquals(tailSize, result.payloads.last().size)
            }
        }

    @Test fun `reader injection still unwraps dropped middle and repairs before final delivery`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                val injector = VideoFaultInjector(VideoFaultInjector.Mode.DROP_MIDDLE_FRAGMENT_IN_READER, 2, 1)
                val packets = listOf(Packet(9, ByteArray(16), 34), Packet(8, byteArrayOf(41)),
                    Packet(8, byteArrayOf(42)), Packet(10, ByteArray(16)), Packet(11, ByteArray(16)))
                val result = read(packets, bulk, injector)
                assertEquals(5, result.unwraps)
                assertEquals(1L, injector.injectedCount)
                assertEquals(listOf("data:9", "data:8", "repair:true", "data:10", "data:11"), result.events)
            }
        }

    @Test fun `suppressed repeated reports cannot suppress repairs or reorder last fragments`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                // Frozen clock and repeated findings exhaust the print budget. Every damaged
                // run still requires a repair verdict before LAST reaches the video worker.
                val result = read(List(30) { listOf(Packet(9, ByteArray(16), 33), Packet(10, ByteArray(16))) }
                    .flatten(), bulk)
                assertEquals(List(30) { listOf("data:9", "repair:true", "data:10") }.flatten(), result.events)
            }
        }

    @Test fun `replacement FIRST reports previous truncation without discarding replacement`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                val result = read(listOf(Packet(9, ByteArray(16), 32),
                    Packet(9, ByteArray(16), 17), Packet(10, byteArrayOf(1))), bulk)
                assertEquals(listOf("data:9", "repair:false", "data:9", "data:10"), result.events)
            }
        }

    @Test fun `every short video prefix reaches the handler as one complete owned message`() =
        mockStatic(SystemClock::class.java).use {
            val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 1, 0x65, 0x12)
            for (bulk in listOf(false, true)) for (split in 1..14) {
                val result = read(listOf(Packet(9, bytes.copyOfRange(0, split), bytes.size),
                    Packet(10, bytes.copyOfRange(split, bytes.size))), bulk, chunkSize = 7)
                assertEquals(listOf("data:11"), result.events)
                assertArrayEquals(bytes, result.payloads.single())
            }
        }

    @Test fun `empty TLS-only frames leave pending messages intact while empty LAST completes`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) for (channel in listOf(Channel.ID_VID, Channel.ID_AUD)) {
                val packets = listOf(Packet(9, byteArrayOf(0, 0), 2, channel = channel),
                    Packet(11, byteArrayOf(), channel = channel),
                    Packet(9, byteArrayOf(), 100, channel = channel),
                    Packet(10, byteArrayOf(), channel = channel),
                    Packet(11, byteArrayOf(0, 1), channel = channel))
                val result = read(packets, bulk, chunkSize = 3)
                assertEquals(listOf("data:11", "data:11"), result.events)
                assertArrayEquals(byteArrayOf(0, 0), result.payloads[0])
                assertEquals(5, result.unwraps)
            }
        }

    @Test fun `bad service lengths drop one run without losing interleaved or following messages`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) for (total in listOf(-1, 0, 1, 2, 4, Int.MAX_VALUE)) {
                val packets = listOf(
                    Packet(9, byteArrayOf(0, 7), 3, channel = Channel.ID_MPB),
                    Packet(9, byteArrayOf(0, 0), total, channel = Channel.ID_AUD),
                    Packet(10, byteArrayOf(42), channel = Channel.ID_AUD),
                    Packet(10, byteArrayOf(7), channel = Channel.ID_MPB),
                    Packet(11, byteArrayOf(0, 0, 9), channel = Channel.ID_AUD))
                val result = read(packets, bulk, chunkSize = 7)
                assertEquals(listOf("data:11", "data:11"), result.events)
                assertArrayEquals(byteArrayOf(0, 7, 7), result.payloads[0])
                assertArrayEquals(byteArrayOf(0, 0, 9), result.payloads[1])
                assertEquals(5, result.unwraps)
            }
        }

    @Test fun `protobuf handler failure leaves the next frame readable in both readers`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                val result = read(listOf(Packet(11, byteArrayOf(0, 7), channel = Channel.ID_MPB),
                    Packet(11, byteArrayOf(0, 0, 42), channel = Channel.ID_AUD)), bulk,
                    beforeHandle = { message ->
                        if (message.channel == Channel.ID_MPB)
                            throw com.google.protobuf.InvalidProtocolBufferException("malformed metadata")
                    })
                assertEquals(listOf("data:11"), result.events)
                assertArrayEquals(byteArrayOf(0, 0, 42), result.payloads.single())
                assertEquals(2, result.unwraps)
            }
        }

    @Test fun `TLS failures still terminate both readers before later records are consumed`() =
        mockStatic(SystemClock::class.java).use {
            mockStatic(android.util.Log::class.java).use { logs ->
                logs.`when`<String> { android.util.Log.getStackTraceString(any()) }.thenReturn("synthetic TLS failure")
                for (bulk in listOf(false, true)) {
                    val result = read(List(3) { Packet(11, ByteArray(16)) }, bulk, failDecryptAt = 2)
                    assertEquals(listOf("data:11"), result.events)
                    assertEquals(2, result.unwraps)
                }
            }
        }

    @Test fun `discarded short video run does not arm a discard for the next healthy frame`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                val result = read(listOf(Packet(9, byteArrayOf(0, 0), 4),
                    Packet(10, byteArrayOf(1)), Packet(11, ByteArray(16))), bulk)
                assertEquals(listOf("repair:false", "data:11"), result.events)
                assertEquals(3, result.unwraps)
            }
        }

    private fun mediaHandler(): Pair<AapMessageHandlerType, AapTransport> {
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val audio = mock<AapAudio>()
        whenever(audio.process(any())).thenReturn(true)
        val video = mock<AapVideo>()
        whenever(video.isPayload(any())).thenReturn(true)
        fun field(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(target, value)
        field(transport, "aapVideo", video) // No video worker: the production drop path ACKs directly.
        doNothing().whenever(transport).noteMessageReceived(any(), any())
        doReturn(0).whenever(transport).videoQueueDepth()
        doReturn(0L).whenever(transport).videoShedCount()
        doReturn(23).whenever(transport).getSessionId(any())
        doNothing().whenever(transport).sendMediaAck(any(), any())
        val handler = mock<AapMessageHandlerType>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        field(handler, "transport", transport)
        field(handler, "aapAudio", audio)
        field(handler, "dispatchMonitor", TransportDispatchMonitor())
        return handler to transport
    }

    @Test fun `discarded DATA returns one real transport credit and leaves normal DATA ack intact`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
                val (handler, transport) = mediaHandler()
                val packets = listOf(
                    Packet(9, ByteArray(10), 15, channel = channel),
                    Packet(10, ByteArray(4), channel = channel),
                    Packet(11, ByteArray(16), channel = channel))
                val result = read(packets, bulk, delegate = handler)
                assertEquals(1, result.payloads.size)
                verify(transport, times(2)).sendMediaAck(channel, 23)
            }
        }

    @Test fun `budget-dropped DATA and CSD repair without giving CSD credit or discarding next frame`() =
        mockStatic(SystemClock::class.java).use {
            val maximum = AapMessageReassembler.MAX_MESSAGE_BYTES
            val chunk = ByteArray(32768)
            val bankPackets = buildList {
                for (channel in listOf(Channel.ID_MPB, Channel.ID_NAV)) {
                    add(Packet(9, chunk, maximum, channel = channel))
                    repeat(maximum / chunk.size - 1) { add(Packet(8, chunk, channel = channel)) }
                }
            }
            for (bulk in listOf(false, true)) for (type in 0..1) {
                val prefix = if (type == 0) ByteArray(10) else byteArrayOf(0, 1)
                val tail = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
                val packets = bankPackets + listOf(Packet(9, prefix, prefix.size + tail.size),
                    Packet(10, tail), Packet(11, ByteArray(16)))
                val (handler, transport) = mediaHandler()
                val result = read(packets, bulk, delegate = handler)
                assertEquals(listOf("repair:false", "data:11"), result.events)
                assertEquals(1, result.payloads.size)
                verify(transport, times(if (type == 0) 2 else 1)).sendMediaAck(Channel.ID_VID, 23)
            }
        }

    @Test fun `fragment routing change keeps the original fatal policy without inferring media credit`() =
        mockStatic(SystemClock::class.java).use {
            mockStatic(android.util.Log::class.java).use { logs ->
                logs.`when`<String> { android.util.Log.getStackTraceString(any()) }.thenReturn("routing changed")
                for (bulk in listOf(false, true)) for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
                    for (type in 0..1) {
                        val (handler, transport) = mediaHandler()
                        val prefix = ByteArray(10).apply { this[1] = type.toByte() }
                        val result = read(listOf(Packet(9, prefix, 16, channel = channel),
                            Packet(14, ByteArray(6), channel = channel),
                            Packet(11, ByteArray(16), channel = channel)), bulk,
                            fatalAtUnwrap = 2, delegate = handler)
                        assertEquals(2, result.unwraps)
                        assertTrue(result.events.isEmpty())
                        assertTrue(result.payloads.isEmpty())
                        verify(transport, never()).sendMediaAck(any(), any())
                    }
                }
            }
        }
}
