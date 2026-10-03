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
    private data class Packet(val flags: Int, val bytes: ByteArray, val total: Int = 0, val overhead: Int = 29) {
        fun wire(): ByteArray {
            val bodyLength = overhead + bytes.size
            val header = byteArrayOf(Channel.ID_VID.toByte(), flags.toByte(),
                (bodyLength ushr 8).toByte(), bodyLength.toByte())
            val totalBytes = if (flags and 3 == 1) ByteBuffer.allocate(4).putInt(total).array() else byteArrayOf()
            // The first synthetic body byte encodes overhead for our fake unwrap only.
            return header + totalBytes + ByteArray(overhead) { overhead.toByte() } + bytes
        }
    }

    private data class Observation(val events: List<String>, val payloads: List<ByteArray>, val unwraps: Int)

    private fun read(packets: List<Packet>, bulk: Boolean, injector: VideoFaultInjector? = null,
                     chunkSize: Int = 65536): Observation {
        val wire = packets.fold(byteArrayOf()) { bytes, packet -> bytes + packet.wire() }
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
                events += "data:${message.flags.toInt() and 255}"
                // Match the production asynchronous handoff's borrowed-buffer ownership rule.
                payloads += message.data.copyOfRange(0, message.size)
            }
        }
        val recovery: (Boolean) -> Unit = { events += "repair:$it" }
        val reader: AapRead = if (bulk)
            AapReadMultipleMessages(connection, ssl, handler, recovery, injector)
        else AapReadSingleMessage(connection, ssl, handler, recovery, injector)
        var calls = 0
        while (position < wire.size) {
            assertEquals("bulk=$bulk events=$events", 0, reader.read())
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
}
