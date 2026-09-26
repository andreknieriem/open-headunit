package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AacCodecConfigTest {
    private val stereo48k = byteArrayOf(0x11, 0x90.toByte())

    @Test fun `common phone configuration reuses the prewarmed AAC format`() {
        val config = AacCodecConfig.parse(stereo48k, 0, 2, 48000, 2)!!
        assertTrue(config.sameAs(AacCodecConfig.default(48000, 2)))
        val speech = byteArrayOf(0x14, 0x08)
        assertTrue(AacCodecConfig.parse(speech, 0, 2, 16000, 1)!!.sameAs(AacCodecConfig.default(16000, 1)))
    }

    @Test fun `transport buffer reuse and MediaCodec buffer writes cannot corrupt queued CSD`() {
        val borrowed = byteArrayOf(99, 0x11, 0x90.toByte(), 0, 0, 99)
        val config = AacCodecConfig.parse(borrowed, 1, 4, 48000, 2)!!
        borrowed.fill(0)
        config.copyBytes().fill(0)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte(), 0, 0), config.copyBytes())
        assertFalse(config.sameAs(AacCodecConfig.default(48000, 2)))
    }

    @Test fun `configuration cannot silently change sink sample rate or channel count`() {
        assertNull(AacCodecConfig.parse(stereo48k, 0, 2, 16000, 2))
        assertNull(AacCodecConfig.parse(stereo48k, 0, 2, 48000, 1))
    }

    @Test fun `unsupported profiles and non-1024-frame formats are rejected`() {
        for (bytes in listOf(byteArrayOf(0x29, 0x90.toByte()), // HE-AAC
            byteArrayOf(0x17, 0x90.toByte()), // explicit rate, not the negotiated format
            byteArrayOf(0x11, 0x94.toByte()), // 960-frame LC
            byteArrayOf(0x11, 0x92.toByte()), // depends on core coder
            byteArrayOf(0x11, 0x91.toByte()))) {
            assertNull(AacCodecConfig.parse(bytes, 0, bytes.size, 48000, 2))
        }
    }

    @Test fun `malformed and oversized CSD is bounded before allocating or decoding`() {
        for (size in listOf(-1, 0, 1, 65, Int.MAX_VALUE)) {
            assertNull(AacCodecConfig.parse(ByteArray(128), 0, size, 48000, 2))
        }
        for (offset in listOf(-1, 1, Int.MAX_VALUE)) {
            assertNull(AacCodecConfig.parse(stereo48k, offset, 2, 48000, 2))
        }
    }

    @Test fun `explicit absent SBR preserves the entire supported codec configuration`() {
        val config = stereo48k + byteArrayOf(0x56, 0xe5.toByte(), 0)
        assertArrayEquals(config, AacCodecConfig.parse(config, 0, config.size, 48000, 2)!!.copyBytes())
        // Optional PS signaling begins at bit 33, not at a byte boundary.
        val noPs = stereo48k + byteArrayOf(0x56, 0xe5.toByte(), 0x54, 0x80.toByte(), 0)
        assertNotNull(AacCodecConfig.parse(noPs, 0, noPs.size, 48000, 2))
    }

    @Test fun `LC prefix does not hide SBR PS or unknown trailing data`() {
        for (tail in listOf(
            byteArrayOf(0xff.toByte(), 0xff.toByte()),
            byteArrayOf(0x56, 0xe5.toByte(), 0x98.toByte()), // SBR enabled
            byteArrayOf(0x56, 0xe5.toByte(), 0x54, 0x88.toByte(), 0), // PS enabled
            byteArrayOf(0x56, 0xe6.toByte(), 0), // unknown extension AOT
            byteArrayOf(0x56, 0xe5.toByte(), 0, 1))) {
            val config = stereo48k + tail
            assertNull(AacCodecConfig.parse(config, 0, config.size, 48000, 2))
        }
    }

    @Test fun `truncated extension cannot borrow its missing flag from the transport tail`() {
        val borrowed = stereo48k + byteArrayOf(0x56, 0xe5.toByte(), 0, 0)
        assertNull(AacCodecConfig.parse(borrowed, 0, 3, 48000, 2))
        assertNull(AacCodecConfig.parse(borrowed, 0, 4, 48000, 2))
        assertNotNull(AacCodecConfig.parse(borrowed, 0, 5, 48000, 2))
    }
}
