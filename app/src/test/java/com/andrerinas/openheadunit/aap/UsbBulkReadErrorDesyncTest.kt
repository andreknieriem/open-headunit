package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// usbfs copies a bulk IN buffer to user space only on success, so a failed bulkTransfer() loses
// whatever part of the phone's stream had already reached that 16 KB request.
class UsbBulkReadErrorDesyncTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    @org.junit.Before fun stubClock() { clock = org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java) }
    @org.junit.After fun restoreClock() { clock.close() }

    private val bodyLen = 12

    /** One encrypted COMPLETE frame on the input channel: header, then a TLS-looking body. */
    private fun frame(seq: Int): ByteArray {
        val body = ByteArray(bodyLen) { 0xC5.toByte() }
        body[0] = 0x17; body[1] = 0x03; body[2] = 0x03; body[3] = seq.toByte(); body[bodyLen - 1] = seq.toByte()
        return byteArrayOf(3, 0x0b, 0, bodyLen.toByte()) + body
    }

    private class Run(val results: List<Int>, val delivered: List<Int>, val tlsFailures: Int)

    /**
     * Feeds [chunks] as successive bulk reads; a null chunk is a bulkTransfer() that returned -1.
     * The bytes in [lost] never reach the reader, which is what the kernel does on a failed read.
     */
    private fun run(chunks: List<ByteArray?>): Run {
        val delivered = mutableListOf<Int>()
        var tlsFailures = 0
        val ssl = mock<AapSsl>()
        whenever(ssl.decrypt(any(), any(), any())).doAnswer {
            val start = it.getArgument<Int>(0)
            val data = it.getArgument<ByteArray>(2)
            val len = it.getArgument<Int>(1)
            // A record must start with a TLS header and end with its own sequence byte, as a MAC would.
            if (data[start] != 0x17.toByte() || data[start + len - 1] != data[start + 3]) {
                tlsFailures++
                throw javax.net.ssl.SSLException("wrong version number")
            }
            // Plaintext: type 0x8001 then the sequence number.
            ByteArrayWithLimit(byteArrayOf(0x80.toByte(), 0x01, data[start + 3], 0), 3)
        }
        var index = 0
        val connection = mock<ProjectionConnection>()
        whenever(connection.isConnected).thenReturn(true)
        whenever(connection.recvBlocking(any(), any(), any(), any())).doAnswer {
            val buffer = it.getArgument<ByteArray>(0)
            val chunk = chunks.getOrNull(index++) ?: return@doAnswer if (index > chunks.size) 0 else -1
            chunk.copyInto(buffer)
            chunk.size
        }
        val handler = object : AapMessageHandler {
            override fun handle(message: AapMessage) { delivered.add(message.data[message.dataOffset].toInt()) }
        }
        val reader = AapReadMultipleMessages(connection, ssl, handler)
        val results = chunks.indices.map { reader.read() }
        return Run(results, delivered, tlsFailures)
    }

    private val stream = (1..8).map { frame(it) }.reduce { a, b -> a + b }
    private val frameLen = 4 + bodyLen

    /**
     * The gh-1204 shape: one failed read swallowed frames 3 and 4 and the start of frame 5.
     * The next header the reader parses is body bytes. Expected: the session ends at that read,
     * with nothing after the loss decoded. Observed on 3.5.0 and main: see the assertion message.
     */
    @Test fun lostBytesAfterAFailedReadEndTheSessionAtOnce() {
        val firstEnd = 2 * frameLen              // frames 1-2 whole
        val resume = 4 * frameLen + 9            // the read after the error starts 9 bytes into frame 5
        val r = run(listOf(stream.copyOfRange(0, firstEnd), null, stream.copyOfRange(resume, stream.size)))
        assertEquals(listOf(1, 2), r.delivered)
        assertEquals("the read after the loss must end the session (results=${r.results})", -1, r.results.last())
    }

    /** Positive control: a failed read that lost nothing must not end the session. */
    @Test fun aFailedReadThatLostNothingKeepsTheSession() {
        val cut = 2 * frameLen + 7
        val r = run(listOf(stream.copyOfRange(0, cut), null, stream.copyOfRange(cut, stream.size)))
        assertEquals((1..8).toList(), r.delivered)
        assertEquals(listOf(0, 0, 0), r.results)
    }

    /** The socket reader meets the same impossible header and must end the session unread. */
    @Test fun socketReaderEndsTheSessionOnAnImpossibleHeader() {
        val ssl = mock<AapSsl>()
        val wire = byteArrayOf(0x4f, 0xC7.toByte(), 0x12, 0x34)
        val connection = mock<ProjectionConnection>()
        whenever(connection.recvBlocking(any(), any(), any(), any())).doAnswer {
            wire.copyInto(it.getArgument<ByteArray>(0)); 4
        }
        val handler = object : AapMessageHandler { override fun handle(message: AapMessage) = Unit }
        assertEquals(-1, AapReadSingleMessage(connection, ssl, handler).read())
        org.mockito.kotlin.verifyNoInteractions(ssl)
    }
}
