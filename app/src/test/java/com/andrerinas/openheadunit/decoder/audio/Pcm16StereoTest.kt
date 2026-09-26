package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class Pcm16StereoTest {
    @Test fun `transport offset signed extremes and stereo channel order survive the copy`() {
        val source = byteArrayOf(99, 98, 97, 0, -128, -1, 127, -1, -1, 0, 0, 96)
        val output = ShortArray(6) { 123 }
        Pcm16Stereo.decode(source, 3, 2, output)
        assertArrayEquals(shortArrayOf(-32768, 32767, -1, 0, 123, 123), output)
    }

    @Test fun `reusing the TLS payload and conversion scratch cannot change banked PCM`() {
        val packet = ByteArray(2048 * 4 + 10)
        for (frame in 0 until 2048) {
            val index = 10 + frame * 4
            packet[index] = 0x34; packet[index + 1] = 0x12
            packet[index + 2] = 0xcc.toByte(); packet[index + 3] = 0xed.toByte()
        }
        val scratch = ShortArray(4096)
        val buffer = AdaptivePcmBuffer()
        Pcm16Stereo.decode(packet, 10, 2048, scratch)
        buffer.noteArrival(0, 2048)
        buffer.write(scratch, scratch.size, 0)
        packet.fill(0)
        scratch.fill(0)
        buffer.finish()
        val out = ShortArray(960)
        assertTrue(buffer.render(out, 1))
        assertEquals(0x1234, out[958].toInt())
        assertEquals(-0x1234, out[959].toInt())
        assertEquals(1568, buffer.depthFrames())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial stereo frame cannot read beyond the transport payload`() {
        Pcm16Stereo.decode(ByteArray(13), 10, 1, ShortArray(2))
    }
}
