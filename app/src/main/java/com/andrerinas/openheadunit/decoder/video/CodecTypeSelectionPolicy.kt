package com.andrerinas.openheadunit.decoder.video

/**
 * Selects the session codec from Annex B parameter-set evidence, falling back to the codec actually
 * announced to the phone. Detection is independent of hardware/software decoder availability.
 *
 * Slice, VPS and PPS headers alone are ambiguous across AVC and HEVC. They must not override the
 * announcement, especially when a decoder restart begins on an ordinary mid-stream slice.
 */
object CodecTypeSelectionPolicy {

    /**
     * Inspects only [offset, offset + size), without copying NAL units. Both Annex B start-code
     * lengths are accepted. Invalid windows, absent/unparseable parameter sets and conflicting
     * AVC/HEVC evidence return null. Later parameter sets are considered after ambiguous slices.
     *
     * This is a conservative codec probe, not a full bitstream validator: [ParameterSetInspector]
     * reads AVC through VUI and HEVC through sub-layer ordering information. A base-layer HEVC VPS
     * is checked through profile-tier-level so a separately delivered VPS can select HEVC before
     * the SPS arrives. PPS-only packets deliberately leave the negotiated codec unchanged.
     */
    fun detect(buffer: ByteArray, offset: Int, size: Int): VideoDecoder.CodecType? {
        if (offset < 0 || size <= 0 || offset > buffer.size || size > buffer.size - offset) return null
        val end = offset + size
        var start = findStartCode(buffer, offset, end)
        var detected: VideoDecoder.CodecType? = null
        while (start >= 0) {
            val header = start + if (buffer[start + 2] == 1.toByte()) 3 else 4
            val next = findStartCode(buffer, header, end)
            val length = (if (next >= 0) next else end) - header
            val candidate = detectParameterSet(buffer, header, length)
            if (candidate != null) {
                if (detected != null && detected != candidate) return null
                detected = candidate
            }
            start = next
        }
        return detected
    }

    fun select(
        detected: VideoDecoder.CodecType?,
        announced: VideoDecoder.CodecType,
    ): VideoDecoder.CodecType = detected ?: announced

    private fun findStartCode(buffer: ByteArray, offset: Int, end: Int): Int {
        var i = offset
        while (i <= end - 3) {
            if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() &&
                (buffer[i + 2] == 1.toByte() ||
                    (i < end - 3 && buffer[i + 2] == 0.toByte() && buffer[i + 3] == 1.toByte()))
            ) return i
            i++
        }
        return -1
    }

    private fun detectParameterSet(buffer: ByteArray, header: Int, length: Int): VideoDecoder.CodecType? {
        if (length < 4) return null
        val first = buffer[header].toInt() and 0xFF
        if (first and 0x80 != 0) return null // forbidden_zero_bit
        if (first == 0x40 && buffer[header + 1] == 1.toByte() &&
            isBaseLayerHevcVps(buffer, header + 2, length - 2)
        ) return VideoDecoder.CodecType.H265
        if (first and 0x1F == 7 && first and 0x60 != 0) {
            if (buffer[header + 2].toInt() and 3 != 0) return null // reserved_zero_2bits
            val sps = ParameterSetInspector.parseH264Sps(buffer, header, length) ?: return null
            val knownProfile = when (sps.profileIdc) {
                66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135 -> true
                else -> false
            }
            if (knownProfile && sps.levelIdc > 0 && sps.picOrderCntType in 0..2 &&
                sps.maxNumRefFrames in 0..16 && sps.width > 0 && sps.height > 0
            ) return VideoDecoder.CodecType.H264
        } else if (first and 0x7E == 0x42) {
            // An SPS has TemporalId 0 (nuh_temporal_id_plus1 = 1), and at most seven sub-layers.
            if (buffer[header + 1].toInt() and 7 != 1 ||
                buffer[header + 2].toInt() and 0x0E == 0x0E
            ) return null
            val sps = ParameterSetInspector.parseHevcSps(buffer, header, length) ?: return null
            if (sps.generalProfileIdc > 0 && sps.generalLevelIdc > 0 &&
                sps.chromaFormatIdc in 0..3 && sps.bitDepthLuma in 8..16 &&
                sps.width > 0 && sps.height > 0 && sps.maxDecPicBuffering > 0 &&
                sps.maxNumReorderPics in 0 until sps.maxDecPicBuffering
            ) return VideoDecoder.CodecType.H265
        }
        return null
    }

    private fun isBaseLayerHevcVps(buffer: ByteArray, offset: Int, length: Int): Boolean {
        val reader = VpsRbspReader(buffer, offset, length)
        val layers = reader.read()
        val temporal = reader.read()
        if (layers < 0 || temporal < 0) return false
        // Only the ordinary single-layer, internally supplied base layer is unambiguous here.
        if (layers and 0x0F != 0x0C || temporal and 0xF0 != 0) return false
        val maxSubLayersMinus1 = (temporal shr 1) and 7
        if (maxSubLayersMinus1 > 6 || (maxSubLayersMinus1 == 0 && temporal and 1 == 0)) return false
        if (reader.read() != 0xFF || reader.read() != 0xFF) return false // reserved_0xffff_16bits

        val profile = reader.read()
        if (profile < 0 || profile and 0x1F == 0) return false
        repeat(10) { if (reader.read() < 0) return false } // compatibility and constraint flags
        if (reader.read() <= 0) return false // general_level_idc
        if (maxSubLayersMinus1 > 0) {
            val highFlags = reader.read()
            val lowFlags = reader.read()
            if (highFlags < 0 || lowFlags < 0) return false
            val flags = (highFlags shl 8) or lowFlags
            val reservedMask = (1 shl (16 - 2 * maxSubLayersMinus1)) - 1
            if (flags and reservedMask != 0) return false
            for (layer in 0 until maxSubLayersMinus1) {
                if (flags and (1 shl (15 - 2 * layer)) != 0) {
                    repeat(11) { if (reader.read() < 0) return false }
                }
                if (flags and (1 shl (14 - 2 * layer)) != 0 && reader.read() < 0) return false
            }
        }
        // Ordering/layer-set syntax must follow; an isolated header or truncated PTL is not proof.
        return reader.read() >= 0
    }

    /** Byte-aligned VPS prefix reader; skips legal emulation-prevention bytes without copying. */
    private class VpsRbspReader(private val data: ByteArray, offset: Int, length: Int) {
        private var position = offset
        private val end = offset + length
        private var zeros = 0

        fun read(): Int {
            if (position >= end) return -1
            var value = data[position++].toInt() and 0xFF
            if (zeros >= 2) {
                if (value == 3) {
                    if (position >= end) return -1
                    value = data[position++].toInt() and 0xFF
                    if (value > 3) return -1
                    zeros = 0
                } else if (value <= 2) {
                    return -1
                }
            }
            zeros = if (value == 0) zeros + 1 else 0
            return value
        }
    }
}
