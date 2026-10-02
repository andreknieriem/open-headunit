package com.andrerinas.openheadunit.decoder.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmConverterTest {
    private fun pcm(frames: Int, channels: Int): ByteArray = ByteBuffer.allocate(frames * channels * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(channels) { ch -> putShort(((frame * 137 + ch * 8000) % 50000 - 25000).toShort()) } }
        }.array()

    @Test fun `resampling sounds identical regardless of packet boundaries`() {
        for (rate in listOf(16000, 44100, 48000, 96000)) for (channels in 1..2) {
            val format = PcmInputFormat(rate, channels)
            val source = pcm(12347, channels)
            val whole = PcmConverter()
            val size = whole.convert(source, 0, source.size, format)
            val expected = whole.samples.copyOf(size)
            val chunks = PcmConverter()
            val actual = ArrayList<Short>()
            var offset = 0
            var packetFrames = 1
            while (offset < source.size) {
                val bytes = minOf(packetFrames * format.bytesPerFrame, source.size - offset)
                val count = chunks.convert(source, offset, bytes, format)
                repeat(count) { actual.add(chunks.samples[it]) }
                offset += bytes
                packetFrames = (packetFrames * 7) % 541 + 1
            }
            assertArrayEquals("$rate Hz / $channels channels", expected, actual.toShortArray())
            assertEquals(12347L * 48000 / rate * 2, size.toLong())
        }
    }

    @Test fun `normal media PCM is bit exact including clipping limits`() {
        val source = pcm(2048, 2)
        val converter = PcmConverter()
        assertEquals(4096, converter.convert(source, 0, source.size, PcmInputFormat(48000, 2)))
        val expected = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until 4096) assertEquals(expected.get(i), converter.samples[i])
    }

    @Test fun `float speech is normalized duplicated and clipped without NaN noise`() {
        val values = floatArrayOf(-2f, -1f, -.5f, 0f, .5f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        val source = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }.array()
        val converter = PcmConverter()
        val count = converter.convert(source, 0, source.size, PcmInputFormat(48000, 1, PcmInputFormat.FLOAT))
        val expected = shortArrayOf(-32768, -32768, -16384, 0, 16384, 32767, 32767, 0, 32767)
        assertEquals(expected.size * 2, count)
        expected.forEachIndexed { i, value ->
            assertEquals(value, converter.samples[i * 2]); assertEquals(value, converter.samples[i * 2 + 1])
        }
    }

    @Test fun `format switch does not interpolate old speech into new stream`() {
        val converter = PcmConverter()
        converter.convert(pcm(100, 1), 0, 200, PcmInputFormat(16000, 1))
        val silence = ByteArray(80)
        val count = converter.convert(silence, 0, silence.size, PcmInputFormat(44100, 2))
        assertTrue(converter.samples.take(count).all { it == 0.toShort() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `incomplete stereo frame is rejected instead of silently losing its bytes`() {
        PcmConverter().convert(ByteArray(7), 0, 7, PcmInputFormat(48000, 2))
    }

    @Test fun `codec replacement resets history even with the same PCM format`() {
        val converter = PcmConverter()
        val format = PcmInputFormat(16000, 1)
        converter.convert(byteArrayOf(48, 117), 0, 2, format) // 30000
        converter.reset()
        val count = converter.convert(ByteArray(2), 0, 2, format)
        assertTrue(converter.samples.take(count).all { it == 0.toShort() })
    }
}
