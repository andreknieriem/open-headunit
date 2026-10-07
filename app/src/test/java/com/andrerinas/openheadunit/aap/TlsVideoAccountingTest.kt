package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
import javax.net.ssl.SSLEngineResult.Status.OK

/** Real TLS pump, both readers and video assembly; only cipher operations and device decode are doubled. */
class TlsVideoAccountingTest {
    private fun record(bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(29 + bytes.size).putShort(bytes.size.toShort()).put(ByteArray(27)).put(bytes).array()

    private fun packet(flags: Int, bytes: ByteArray, records: Int, total: Int = 0): ByteArray {
        val body = (0 until records).flatMap { part ->
            record(bytes.copyOfRange(bytes.size * part / records, bytes.size * (part + 1) / records)).toList()
        }.toByteArray()
        return byteArrayOf(Channel.ID_VID.toByte(), flags.toByte(), (body.size ushr 8).toByte(), body.size.toByte()) +
            (if (flags == 9) ByteBuffer.allocate(4).putInt(total).array() else byteArrayOf()) + body
    }

    @Test fun `TLS packing changes preserve video while a missing plaintext byte still discards its frame`() =
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                val first = ByteArray(512).apply { this[13] = 1; this[14] = 0x65 }
                val last = ByteArray(512)
                val wire = (listOf(10, 1, 17).map { count ->
                    packet(9, first, count, 1024) + packet(10, last, count)
                } + listOf(
                    packet(9, first, 1, 1024) + packet(10, ByteArray(511), 1),
                    packet(9, first, 3, 1024) + packet(11, byteArrayOf(), 2) +
                        packet(10, last, 5)
                )).fold(byteArrayOf()) { a, b -> a + b }
                var position = 0
                val connection = mock<ProjectionConnection>()
                whenever(connection.recvBlocking(any(), any(), any(), any())).thenAnswer { call ->
                    val destination = call.getArgument<ByteArray>(0)
                    val n = minOf(call.getArgument<Int>(1), wire.size - position,
                        if (call.getArgument<Boolean>(3)) Int.MAX_VALUE else 7)
                    wire.copyInto(destination, 0, position, position + n); position += n; n
                }
                val engine = mock<SSLEngine>()
                val session = mock<SSLSession>()
                whenever(session.packetBufferSize).thenReturn(65536)
                whenever(engine.session).thenReturn(session)
                whenever(engine.handshakeStatus).thenReturn(NOT_HANDSHAKING)
                whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer { call ->
                    val src = call.getArgument<ByteBuffer>(0)
                    val dst = call.getArgument<ByteBuffer>(1)
                    val length = src.short.toInt() and 65535
                    src.position(src.position() + 27)
                    repeat(length) { dst.put(src.get()) }
                    SSLEngineResult(OK, NOT_HANDSHAKING, length + 29, length)
                }
                val pump = TlsApplicationPump(engine)
                val ssl = mock<AapSsl>()
                val plaintext = ByteBuffer.allocate(65536)
                whenever(ssl.decrypt(any(), any(), any())).thenAnswer { call ->
                    plaintext.clear()
                    pump.unwrap(ByteBuffer.wrap(call.getArgument(2), call.getArgument(0), call.getArgument(1)),
                        plaintext) { fail("no outbound control expected") }
                    ByteArrayWithLimit(plaintext.array(), plaintext.position())
                }
                val settings = mock<Settings>()
                whenever(settings.videoCodec).thenReturn("h264")
                whenever(settings.debugVideoFaultInjection).thenReturn(VideoFaultInjector.Mode.OFF)
                val decoder = mock<VideoDecoder>()
                val video = AapVideo(decoder, settings) {}
                val repairs = mutableListOf<Boolean>()
                val repair: (Boolean) -> Unit = { discard -> repairs += discard; video.onFragmentRunHoled(discard) }
                val handler = object : AapMessageHandler {
                    override fun handle(message: AapMessage) { video.process(message) }
                }
                val reader: AapRead = if (bulk) AapReadMultipleMessages(connection, ssl, handler, repair)
                    else AapReadSingleMessage(connection, ssl, handler, repair)
                while (position < wire.size) assertEquals(0, reader.read())
                assertEquals(listOf(true), repairs)
                verify(decoder, times(4)).decode(any(), any(), eq(1014), any(), any())
            }
        }
}
