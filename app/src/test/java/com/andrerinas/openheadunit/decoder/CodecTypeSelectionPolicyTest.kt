package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.decoder.VideoDecoder.CodecType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four inputs are named after what produces them on a device, so a case here maps onto a unit
 * you could hold: `hevcDetectable` is hardware HEVC, `hevcUsable` adds the explicitly selected
 * software decoder, and `detected` is what the first packet's parameter sets said.
 */
class CodecTypeSelectionPolicyTest {

    private fun select(
        detected: CodecType?,
        requested: CodecType,
        hevcDetectable: Boolean,
        hevcUsable: Boolean,
    ) = CodecTypeSelectionPolicy.select(detected, requested, hevcDetectable, hevcUsable)

    // --- the defect this policy exists for -------------------------------------------------

    @Test
    fun `an H264 stream gets an H264 decoder even when the setting says H265`() {
        // A unit with real hardware HEVC, set to H.265, on a session the phone opened as H.264.
        // Read off a reporter capture: 52 codec rebuilds and 48 ACodec errors in 275s.
        assertEquals(
            CodecType.H264,
            select(CodecType.H264, CodecType.H265, hevcDetectable = true, hevcUsable = true)
        )
    }

    @Test
    fun `a unit that cannot decode HEVC at all ignores an H265 setting`() {
        // The announcement already downgraded this unit to H.264, so H.264 is what is arriving.
        // Both with and without a usable sniff.
        assertEquals(
            CodecType.H264,
            select(CodecType.H264, CodecType.H265, hevcDetectable = false, hevcUsable = false)
        )
        assertEquals(
            CodecType.H264,
            select(null, CodecType.H265, hevcDetectable = false, hevcUsable = false)
        )
    }

    // --- what must not regress -------------------------------------------------------------

    @Test
    fun `software HEVC keeps the setting, because the sniff cannot see HEVC there`() {
        // No hardware HEVC, so detectCodecType walks past the VPS/SPS/PPS and can misread an HEVC
        // IDR_N_LP header as an H.264 PPS. Its answer is not evidence; the setting is.
        assertEquals(
            CodecType.H265,
            select(CodecType.H264, CodecType.H265, hevcDetectable = false, hevcUsable = true)
        )
        assertEquals(
            CodecType.H265,
            select(null, CodecType.H265, hevcDetectable = false, hevcUsable = true)
        )
    }

    @Test
    fun `a packet with no parameter set falls back to the setting`() {
        assertEquals(
            CodecType.H265,
            select(null, CodecType.H265, hevcDetectable = true, hevcUsable = true)
        )
        assertEquals(
            CodecType.H264,
            select(null, CodecType.H264, hevcDetectable = true, hevcUsable = true)
        )
    }

    @Test
    fun `an H265 stream still gets an H265 decoder when the setting says H264`() {
        // Pre-existing behaviour: the sniff already won this direction and must keep winning.
        assertEquals(
            CodecType.H265,
            select(CodecType.H265, CodecType.H264, hevcDetectable = true, hevcUsable = true)
        )
    }

    @Test
    fun `the ordinary agreeing cases are unchanged`() {
        assertEquals(
            CodecType.H264,
            select(CodecType.H264, CodecType.H264, hevcDetectable = true, hevcUsable = true)
        )
        assertEquals(
            CodecType.H265,
            select(CodecType.H265, CodecType.H265, hevcDetectable = true, hevcUsable = true)
        )
    }

    @Test
    fun `H264 is never overridden into H265 by the setting alone when the stream disagrees`() {
        // The whole failure mode in one assertion: on any unit where the sniff can tell the two
        // apart, a positively-identified H.264 stream is never handed an HEVC decoder.
        for (usable in listOf(true, false)) {
            assertEquals(
                "hevcUsable=$usable",
                CodecType.H264,
                select(CodecType.H264, CodecType.H265, hevcDetectable = true, hevcUsable = usable)
            )
        }
    }
}
