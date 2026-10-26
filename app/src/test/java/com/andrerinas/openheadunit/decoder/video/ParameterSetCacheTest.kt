package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.*
import org.junit.Test

class ParameterSetCacheTest {
    private fun ue(value: Int): String {
        val bits = (value + 1).toString(2)
        return "0".repeat(bits.length - 1) + bits
    }

    private fun payload(bits: String): ByteArray {
        val padded = bits.padEnd((bits.length + 7) / 8 * 8, '0')
        val escaped = ArrayList<Byte>()
        var zeros = 0
        for (chunk in padded.chunked(8)) {
            val value = chunk.toInt(2)
            if (zeros >= 2 && value <= 3) { escaped.add(3); zeros = 0 }
            escaped.add(value.toByte())
            zeros = if (value == 0) zeros + 1 else 0
        }
        return escaped.toByteArray()
    }

    private fun nal(hevc: Boolean, kind: Int, id: Int, marker: String = "1"): ByteArray {
        val header = if (hevc) byteArrayOf((kind shl 1).toByte(), 1) else byteArrayOf((0x60 or kind).toByte())
        val fields = when (kind) {
            7 -> "010000100000000000011111" + ue(id)
            32 -> id.toString(2).padStart(4, '0')
            33 -> "00000001" + "0".repeat(96) + ue(id)
            else -> ue(id)
        }
        return header + payload(fields + marker)
    }

    private fun normalized(data: ByteArray) = byteArrayOf(0, 0, 0, 1) + data

    @Test fun `different IDs survive replacement of one active parameter set`() {
        val cache = ParameterSetCache()
        val sps0 = nal(false, 7, 0)
        val sps1 = nal(false, 7, 1)
        val pps0 = nal(false, 8, 0)
        val pps1 = nal(false, 8, 1)
        for ((kind, data) in listOf(7 to sps1, 8 to pps1, 7 to sps0, 8 to pps0)) {
            cache.store(data, 0, data.size, false, kind)
        }
        val replacement = nal(false, 8, 1, "0101")
        cache.store(replacement, 0, replacement.size, false, 8)
        assertArrayEquals(normalized(sps0) + normalized(sps1), cache.codecSpecificData(false, 0))
        assertArrayEquals(normalized(pps0) + normalized(replacement), cache.codecSpecificData(false, 1))
    }

    @Test fun `a new session cannot inherit parameter sets from the preceding codec`() {
        val cache = ParameterSetCache()
        val avc = nal(false, 8, 255)
        cache.store(avc, 0, avc.size, false, 8)
        cache.clear()
        assertNull(cache.codecSpecificData(false, 1))
        val vps = nal(true, 32, 15)
        val sps = nal(true, 33, 15)
        val pps = nal(true, 34, 63)
        for ((kind, data) in listOf(34 to pps, 33 to sps, 32 to vps)) cache.store(data, 0, data.size, true, kind)
        assertArrayEquals(normalized(vps) + normalized(sps) + normalized(pps), cache.codecSpecificData(true, 0))
    }

    @Test fun `IDs respect AVC and HEVC syntax bounds without integer wraparound`() {
        for ((hevc, kind, maximum) in listOf(Triple(false, 7, 31), Triple(false, 8, 255), Triple(true, 33, 15), Triple(true, 34, 63))) {
            for (id in listOf(0, maximum)) {
                val data = nal(hevc, kind, id)
                assertEquals(id, ParameterSetInspector.parameterSetId(data, 0, data.size, hevc))
            }
            val tooLarge = nal(hevc, kind, maximum + 1)
            assertNull(ParameterSetInspector.parameterSetId(tooLarge, 0, tooLarge.size, hevc))
        }
        val overflowingPps = byteArrayOf(0x68) + payload("0".repeat(32) + "1" + "0".repeat(32))
        assertNull(ParameterSetInspector.parameterSetId(overflowingPps, 0, overflowingPps.size, false))
        val truncatedPps = byteArrayOf(0x68, 0)
        assertNull(ParameterSetInspector.parameterSetId(truncatedPps, 0, truncatedPps.size, false))
    }

    @Test fun `HEVC SPS ID follows escaped general and sublayer profile fields`() {
        // Two sublayers: profile only for the first, level only for the second.
        val bits = "00000101" + "0".repeat(96) + "1001" + "0".repeat(12) + "0".repeat(88 + 8) + ue(11) + "1"
        val data = byteArrayOf(0x42, 1) + payload(bits)
        assertEquals(11, ParameterSetInspector.parameterSetId(data, 0, data.size, true))
        for (length in 0..3) assertNull(ParameterSetInspector.parameterSetId(data, 0, length, true))
        assertNull(ParameterSetInspector.parameterSetId(data, -1, data.size, true))
        assertNull(ParameterSetInspector.parameterSetId(data, 1, Int.MAX_VALUE, true))
    }
}
