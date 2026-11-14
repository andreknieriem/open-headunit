package com.andrerinas.openheadunit.connection.carplaybox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBoxFrameCodecTest {

    private val types = listOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x17, 0x19, 0x99, 0xAA, 0xCC,
    )

    private fun messages(decoded: List<CarPlayBoxFrameCodec.Decoded>) =
        decoded.filterIsInstance<CarPlayBoxFrameCodec.Decoded.Message>().map { it.frame }

    @Test
    fun `every message type round trips`() {
        for (type in types) {
            val payload = ByteArray(type % 7) { (it + type).toByte() }
            val frames = messages(CarPlayBoxFrameCodec.Decoder().feed(CarPlayBoxFrameCodec.encode(type, payload)))
            assertEquals(1, frames.size)
            assertEquals(type, frames[0].type)
            assertArrayEquals(payload, frames[0].payload)
        }
    }

    @Test
    fun `decoder resyncs after five bad bytes`() {
        val good = CarPlayBoxFrameCodec.encode(0x06, byteArrayOf(1, 2, 3))
        val junk = byteArrayOf(0x11, 0x22, 0x55, 0x33, 0x44)
        val frames = messages(CarPlayBoxFrameCodec.Decoder().feed(junk + good))
        assertEquals(1, frames.size)
        assertEquals(0x06, frames[0].type)
    }

    @Test
    fun `a check mismatch drops that frame and keeps the next`() {
        val bad = CarPlayBoxFrameCodec.encode(0x06, byteArrayOf(9, 9))
        bad[12] = 0 // break the type check
        val good = CarPlayBoxFrameCodec.encode(0x07, byteArrayOf(5))
        val frames = messages(CarPlayBoxFrameCodec.Decoder().feed(bad + good))
        assertEquals(listOf(0x07), frames.map { it.type })
    }

    @Test
    fun `one frame split over three reads`() {
        val frame = CarPlayBoxFrameCodec.encode(0x06, ByteArray(100) { it.toByte() })
        val decoder = CarPlayBoxFrameCodec.Decoder()
        assertTrue(decoder.feed(frame, 0, 10).isEmpty())
        assertTrue(decoder.feed(frame, 10, 50).isEmpty())
        val frames = messages(decoder.feed(frame, 60, frame.size - 60))
        assertEquals(1, frames.size)
        assertEquals(100, frames[0].payload.size)
    }

    @Test
    fun `two frames in one read`() {
        val both = CarPlayBoxFrameCodec.encode(0x01) + CarPlayBoxFrameCodec.encode(0xAA)
        assertEquals(listOf(0x01, 0xAA), messages(CarPlayBoxFrameCodec.Decoder().feed(both)).map { it.type })
    }

    @Test
    fun `a declared length above the cap is refused from the header alone`() {
        val header = CarPlayBoxFrameCodec.encode(0x06).copyOf(16)
        header[4] = 0x01 // 1048577 = 0x00100001
        header[5] = 0x00
        header[6] = 0x10
        header[7] = 0x00
        val out = CarPlayBoxFrameCodec.Decoder().feed(header)
        assertEquals(1, out.size)
        val overlong = out[0] as CarPlayBoxFrameCodec.Decoded.Overlong
        assertEquals(1048577L, overlong.declared)
    }

    @Test
    fun `an encrypted frame is reported and not decoded`() {
        val frame = CarPlayBoxFrameCodec.encode(0x06, byteArrayOf(1, 2, 3, 4))
        frame[0] = 0xBB.toByte()
        frame[2] = 0xBB.toByte()
        val out = CarPlayBoxFrameCodec.Decoder().feed(frame + CarPlayBoxFrameCodec.encode(0xAA))
        assertTrue(out[0] is CarPlayBoxFrameCodec.Decoded.Encrypted)
        assertEquals(0x06, (out[0] as CarPlayBoxFrameCodec.Decoded.Encrypted).type)
        assertEquals(0xAA, (out[1] as CarPlayBoxFrameCodec.Decoded.Message).frame.type)
    }

    @Test
    fun `a 40000 byte file write goes out in chunks of 16384`() {
        val frame = CarPlayBoxMessages.sendFile("/tmp/x", ByteArray(40000 - 16 - 4 - 7 - 4))
        assertEquals(40000, frame.size)
        val chunks = CarPlayBoxFrameCodec.chunks(frame)
        assertEquals(listOf(16384, 16384, 7232), chunks.map { it.size })
        assertArrayEquals(frame, chunks.reduce { a, b -> a + b })
    }
}
