package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.ScreenPacketReader.Event
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases follow LIVI `livi-screen-stream` tests and DiPlay `ScreenStreamPeerTest`, with frames sealed by the test. */
class ScreenPacketTest {

    private val key = ByteArray(32) { 7 }

    private fun header(opcode: Int, bodySize: Int) = ByteArray(ScreenPacketReader.HEADER_LEN).also {
        it[0] = bodySize.toByte()
        it[1] = (bodySize shr 8).toByte()
        it[2] = (bodySize shr 16).toByte()
        it[3] = (bodySize shr 24).toByte()
        it[4] = opcode.toByte()
    }

    private fun message(opcode: Int, body: ByteArray) = header(opcode, body.size) + body

    private fun sealed(counter: Long, plain: ByteArray): ByteArray {
        val head = header(0, plain.size + 16)
        return head + CarPlayCrypto.chachaSeal(key, Nonces.counter(counter), plain, head)
    }

    private fun frames(events: List<Event>) = events.filterIsInstance<Event.Frame>().map { it.payload.toList() }

    @Test
    fun aConfigNamesTheCodecAndTheRecordBehindTheFourcc() {
        val reader = ScreenPacketReader(key)
        val hvcc = reader.push(message(1, ByteArray(4) + "hvcC".toByteArray() + byteArrayOf(1, 2, 3)))
        val avcc = reader.push(message(1, ByteArray(4) + "avcC".toByteArray() + byteArrayOf(4, 5)))
        assertEquals(ScreenCodec.H265, (hvcc.single() as Event.Config).codec)
        assertArrayEquals(byteArrayOf(1, 2, 3), (hvcc.single() as Event.Config).record)
        assertEquals(ScreenCodec.H264, (avcc.single() as Event.Config).codec)
        assertArrayEquals(byteArrayOf(4, 5), (avcc.single() as Event.Config).record)
    }

    @Test
    fun aConfigWithNoFourccIsH264OnlyWhenItLooksLikeAvcc() {
        val avcc = byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, 4, 0x67, 0x64, 0, 0x1f)
        val reader = ScreenPacketReader(key)
        val first = reader.push(message(1, avcc)).single() as Event.Config
        assertEquals(ScreenCodec.H264, first.codec)
        assertArrayEquals(avcc, first.record)
        val other = reader.push(message(1, ByteArray(20) { 3 })).single() as Event.Config
        assertEquals(ScreenCodec.H265, other.codec)
    }

    @Test
    fun sealedFramesArriveDecryptedInOrder() {
        val reader = ScreenPacketReader(key)
        val events = reader.push(sealed(0, "first".toByteArray()) + sealed(1, "second".toByteArray()))
        assertEquals(listOf("first".toByteArray().toList(), "second".toByteArray().toList()), frames(events))
    }

    @Test
    fun aFrameWithABadTagIsDroppedAndTheCounterStays() {
        val reader = ScreenPacketReader(key)
        val bad = sealed(0, "first".toByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xff).toByte() }
        val dropped = reader.push(bad)
        assertTrue((dropped.single() as Event.AuthFailed).first)
        assertEquals(listOf("again".toByteArray().toList()), frames(reader.push(sealed(0, "again".toByteArray()))))
    }

    @Test
    fun aBadTagIsReportedAsFirstOnlyOncePerReader() {
        val reader = ScreenPacketReader(key)
        val bad = sealed(0, "x".toByteArray()).also { it[ScreenPacketReader.HEADER_LEN] = (it[ScreenPacketReader.HEADER_LEN].toInt() xor 1).toByte() }
        assertTrue((reader.push(bad).single() as Event.AuthFailed).first)
        assertFalse((reader.push(bad).single() as Event.AuthFailed).first)
        reader.reset()
        assertFalse((reader.push(bad).single() as Event.AuthFailed).first)
    }

    @Test
    fun aFrameSealedUnderAnotherHeaderFails() {
        val reader = ScreenPacketReader(key)
        val msg = sealed(0, "x".toByteArray())
        msg[10] = 1
        assertTrue(reader.push(msg).single() is Event.AuthFailed)
    }

    @Test
    fun aBodyTooShortForATagNeverReachesTheDecoder() {
        val reader = ScreenPacketReader(key)
        assertEquals(emptyList<Event>(), reader.push(message(0, byteArrayOf(0, 0, 0, 4, 1, 2, 3, 4))))
        assertEquals(emptyList<Event>(), reader.push(message(0, ByteArray(0))))
        assertEquals(listOf("ok".toByteArray().toList()), frames(reader.push(sealed(0, "ok".toByteArray()))))
    }

    @Test
    fun aMessageSplitAcrossChunksIsReassembled() {
        val reader = ScreenPacketReader(key)
        val msg = sealed(0, "split me".toByteArray())
        val all = msg.toList().chunked(7).flatMap { reader.push(it.toByteArray()) }
        assertEquals(listOf("split me".toByteArray().toList()), frames(all))
    }

    @Test
    fun aLargeFrameAcrossManyChunksComesOutWhole() {
        val reader = ScreenPacketReader(key)
        val plain = ByteArray(300_000) { (it * 31).toByte() }
        val msg = sealed(0, plain)
        val out = ArrayList<Event>()
        var at = 0
        while (at < msg.size) {
            val n = minOf(4096, msg.size - at)
            out += reader.push(msg.copyOfRange(at, at + n))
            at += n
        }
        assertArrayEquals(plain, (out.single() as Event.Frame).payload)
    }

    @Test
    fun anImplausibleBodySizeIsRefused() {
        assertThrows(ScreenStreamException::class.java) {
            ScreenPacketReader(key).push(header(0, ScreenPacketReader.MAX_BODY + 1))
        }
        assertThrows(ScreenStreamException::class.java) {
            ScreenPacketReader(key).push(header(0, -1))
        }
        ScreenPacketReader(key).push(header(0, ScreenPacketReader.MAX_BODY))
    }

    @Test
    fun anUnknownOpcodeIsIgnored() {
        assertEquals(emptyList<Event>(), ScreenPacketReader(key).push(message(9, byteArrayOf(1, 2, 3))))
    }

    @Test
    fun resetForgetsAHalfMessageAndTheCounter() {
        val reader = ScreenPacketReader(key)
        reader.push(sealed(0, "a".toByteArray()))
        val msg = sealed(1, "dropped".toByteArray())
        reader.push(msg.copyOf(40))
        reader.reset()
        assertEquals(listOf("fresh".toByteArray().toList()), frames(reader.push(sealed(0, "fresh".toByteArray()))))
    }
}
