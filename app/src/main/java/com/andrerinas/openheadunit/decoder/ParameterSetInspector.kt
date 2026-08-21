package com.andrerinas.openheadunit.decoder

/**
 * Reads the fields of an H.264 or H.265 parameter set that decide how a decoder buffers frames.
 *
 * This exists to answer one question with a number instead of a guess. Moonlight rewrites the H.264
 * SPS on every Android 8.0+ device it runs on - forcing `bitstream_restriction` present with
 * `max_num_reorder_frames = 0` and `max_dec_frame_buffering = num_ref_frames` - because unmodified
 * NVENC output makes some hardware decoders allocate 16+ reference buffers and adds frames of
 * latency. We have never looked at what Android Auto actually sends, so we do not know whether that
 * lever exists for us at all. Log it before writing a bitstream writer for it.
 *
 * The reader does its own RBSP unescaping. The dimension-only parser this replaced did not, which was
 * survivable while it only reached the width and height near the front of the SPS - but every field
 * this class is here for sits behind the VUI or the profile-tier-level block, far enough in that a
 * single unremoved `0x03` silently shifts everything after it and turns the answer into noise. A
 * `0x03` in a High-profile scaling matrix reaches the dimensions too, which is one of the reasons the
 * old parser is gone rather than kept alongside.
 *
 * Everything here is a *reader*. Nothing in this file modifies a bitstream.
 */
object ParameterSetInspector {

    /**
     * Fields of an AVC/H.264 SPS. Nullable members are absent from the bitstream rather than zero,
     * which is the distinction that matters: `bitstreamRestrictionPresent == false` is exactly the
     * case Moonlight patches.
     */
    data class H264Sps(
        val profileIdc: Int,
        val levelIdc: Int,
        val picOrderCntType: Int,
        val maxNumRefFrames: Int,
        val width: Int,
        val height: Int,
        val vuiPresent: Boolean,
        val bitstreamRestrictionPresent: Boolean,
        val maxNumReorderFrames: Int?,
        val maxDecFrameBuffering: Int?,
    ) {
        override fun toString(): String =
            "profile=$profileIdc level=$levelIdc poc_type=$picOrderCntType " +
                "num_ref_frames=$maxNumRefFrames size=${width}x$height vui=$vuiPresent " +
                "bitstream_restriction=$bitstreamRestrictionPresent " +
                "num_reorder_frames=${maxNumReorderFrames ?: "absent"} " +
                "max_dec_frame_buffering=${maxDecFrameBuffering ?: "absent"}"
    }

    /**
     * Fields of an HEVC/H.265 SPS.
     *
     * Stops at the sub-layer ordering info. `sps_temporal_mvp_enabled_flag` sits behind the
     * short-term reference picture sets, whose parsing is long enough that getting it wrong is more
     * likely than the field is useful, and nothing planned reads it.
     */
    data class HevcSps(
        val generalProfileIdc: Int,
        val generalLevelIdc: Int,
        val chromaFormatIdc: Int,
        val bitDepthLuma: Int,
        val width: Int,
        val height: Int,
        val maxDecPicBuffering: Int,
        val maxNumReorderPics: Int,
    ) {
        override fun toString(): String =
            "profile=$generalProfileIdc level=$generalLevelIdc chroma_format=$chromaFormatIdc " +
                "bit_depth=$bitDepthLuma size=${width}x$height " +
                "max_dec_pic_buffering=$maxDecPicBuffering max_num_reorder_pics=$maxNumReorderPics"
    }

    /**
     * Parses an H.264 SPS.
     *
     * [nalHeaderOffset] points at the NAL header byte, i.e. just past the Annex B start code - the
     * same convention `VideoDecoder.scanAndApplyConfig` already uses. Returns null if the bitstream
     * runs out or does not parse, never a partly-filled result.
     */
    fun parseH264Sps(data: ByteArray, nalHeaderOffset: Int, length: Int): H264Sps? {
        return try {
            // Skip nal_unit_header: 1 byte for AVC.
            val r = RbspReader(data, nalHeaderOffset + 1, length - 1)

            val profileIdc = r.u(8)
            r.skip(8)                       // constraint_set0..5 (6) + reserved_zero_2bits (2)
            val levelIdc = r.u(8)
            r.ue()                          // seq_parameter_set_id

            var chromaFormatIdc = 1
            if (profileIdc in HIGH_PROFILES) {
                chromaFormatIdc = r.ue()
                if (chromaFormatIdc == 3) r.skip(1)   // separate_colour_plane_flag
                r.ue()                                // bit_depth_luma_minus8
                r.ue()                                // bit_depth_chroma_minus8
                r.skip(1)                             // qpprime_y_zero_transform_bypass_flag
                if (r.u(1) == 1) {                    // seq_scaling_matrix_present_flag
                    val lists = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until lists) {
                        if (r.u(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                    }
                }
            }

            r.ue()                                    // log2_max_frame_num_minus4
            val picOrderCntType = r.ue()
            when (picOrderCntType) {
                0 -> r.ue()                           // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    // The case the dimension-only parser in VideoDecoder gets wrong. Rare in
                    // practice, but a desync here would put a plausible wrong number in the log,
                    // which is worse than no number.
                    r.skip(1)                         // delta_pic_order_always_zero_flag
                    r.se()                            // offset_for_non_ref_pic
                    r.se()                            // offset_for_top_to_bottom_field
                    val cycle = r.ue()                // num_ref_frames_in_pic_order_cnt_cycle
                    if (cycle > MAX_POC_CYCLE) return null
                    repeat(cycle) { r.se() }
                }
            }

            val maxNumRefFrames = r.ue()
            r.skip(1)                                 // gaps_in_frame_num_value_allowed_flag
            val widthInMbsMinus1 = r.ue()
            val heightInMapUnitsMinus1 = r.ue()
            val frameMbsOnly = r.u(1)
            if (frameMbsOnly == 0) r.skip(1)          // mb_adaptive_frame_field_flag
            r.skip(1)                                 // direct_8x8_inference_flag

            var width = (widthInMbsMinus1 + 1) * 16
            var height = (2 - frameMbsOnly) * (heightInMapUnitsMinus1 + 1) * 16
            if (r.u(1) == 1) {                        // frame_cropping_flag
                val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
                // Crop units are chroma samples; 2 horizontally and 2 * (2 - frame_mbs_only)
                // vertically for the 4:2:0 that Android Auto negotiates.
                val subWidth = if (chromaFormatIdc == 3) 1 else 2
                val subHeight = if (chromaFormatIdc == 1) 2 else 1
                width -= (left + right) * subWidth
                height -= (top + bottom) * subHeight * (2 - frameMbsOnly)
            }

            val vuiPresent = r.u(1) == 1
            var bitstreamRestrictionPresent = false
            var maxNumReorderFrames: Int? = null
            var maxDecFrameBuffering: Int? = null
            if (vuiPresent) {
                if (r.u(1) == 1) {                    // aspect_ratio_info_present_flag
                    if (r.u(8) == 255) r.skip(32)     // aspect_ratio_idc == Extended_SAR
                }
                if (r.u(1) == 1) r.skip(1)            // overscan_info_present -> overscan_appropriate
                if (r.u(1) == 1) {                    // video_signal_type_present_flag
                    r.skip(4)                         // video_format (3) + video_full_range_flag (1)
                    if (r.u(1) == 1) r.skip(24)       // colour_description -> primaries/transfer/matrix
                }
                if (r.u(1) == 1) { r.ue(); r.ue() }   // chroma_loc_info_present_flag
                if (r.u(1) == 1) {                    // timing_info_present_flag
                    r.skip(32)                        // num_units_in_tick
                    r.skip(32)                        // time_scale
                    r.skip(1)                         // fixed_frame_rate_flag
                }
                val nalHrd = r.u(1) == 1
                if (nalHrd && !skipHrdParameters(r)) return null
                val vclHrd = r.u(1) == 1
                if (vclHrd && !skipHrdParameters(r)) return null
                if (nalHrd || vclHrd) r.skip(1)       // low_delay_hrd_flag
                r.skip(1)                             // pic_struct_present_flag
                bitstreamRestrictionPresent = r.u(1) == 1
                if (bitstreamRestrictionPresent) {
                    r.skip(1)                         // motion_vectors_over_pic_boundaries_flag
                    r.ue()                            // max_bytes_per_pic_denom
                    r.ue()                            // max_bits_per_mb_denom
                    r.ue()                            // log2_max_mv_length_horizontal
                    r.ue()                            // log2_max_mv_length_vertical
                    maxNumReorderFrames = r.ue()
                    maxDecFrameBuffering = r.ue()
                }
            }

            if (r.overran) return null

            H264Sps(
                profileIdc = profileIdc,
                levelIdc = levelIdc,
                picOrderCntType = picOrderCntType,
                maxNumRefFrames = maxNumRefFrames,
                width = width,
                height = height,
                vuiPresent = vuiPresent,
                bitstreamRestrictionPresent = bitstreamRestrictionPresent,
                maxNumReorderFrames = maxNumReorderFrames,
                maxDecFrameBuffering = maxDecFrameBuffering,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses an HEVC SPS. [nalHeaderOffset] points at the first of the two NAL header bytes.
     */
    fun parseHevcSps(data: ByteArray, nalHeaderOffset: Int, length: Int): HevcSps? {
        return try {
            // Skip nal_unit_header: 2 bytes for HEVC.
            val r = RbspReader(data, nalHeaderOffset + 2, length - 2)

            r.skip(4)                                 // sps_video_parameter_set_id
            val maxSubLayersMinus1 = r.u(3)
            r.skip(1)                                 // sps_temporal_id_nesting_flag

            // profile_tier_level(profilePresentFlag = 1, maxSubLayersMinus1)
            r.skip(2)                                 // general_profile_space
            r.skip(1)                                 // general_tier_flag
            val generalProfileIdc = r.u(5)
            r.skip(32)                                // general_profile_compatibility_flag[32]
            r.skip(4)                                 // progressive/interlaced/non_packed/frame_only
            r.skip(43)                                // general_reserved_zero_43bits
            r.skip(1)                                 // general_inbld_flag / reserved_zero_bit
            val generalLevelIdc = r.u(8)

            val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
            val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
            for (i in 0 until maxSubLayersMinus1) {
                subLayerProfilePresent[i] = r.u(1) == 1
                subLayerLevelPresent[i] = r.u(1) == 1
            }
            if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
            for (i in 0 until maxSubLayersMinus1) {
                if (subLayerProfilePresent[i]) r.skip(88)
                if (subLayerLevelPresent[i]) r.skip(8)
            }

            r.ue()                                    // sps_seq_parameter_set_id
            val chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) r.skip(1)       // separate_colour_plane_flag
            var width = r.ue()                        // pic_width_in_luma_samples
            var height = r.ue()                       // pic_height_in_luma_samples
            if (r.u(1) == 1) {                        // conformance_window_flag
                val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
                val subWidth = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
                val subHeight = if (chromaFormatIdc == 1) 2 else 1
                width -= (left + right) * subWidth
                height -= (top + bottom) * subHeight
            }
            val bitDepthLuma = r.ue() + 8             // bit_depth_luma_minus8
            r.ue()                                    // bit_depth_chroma_minus8
            r.ue()                                    // log2_max_pic_order_cnt_lsb_minus4

            val subLayerOrderingInfoPresent = r.u(1) == 1
            var maxDecPicBuffering = 0
            var maxNumReorderPics = 0
            val firstLayer = if (subLayerOrderingInfoPresent) 0 else maxSubLayersMinus1
            for (i in firstLayer..maxSubLayersMinus1) {
                maxDecPicBuffering = r.ue() + 1       // sps_max_dec_pic_buffering_minus1
                maxNumReorderPics = r.ue()            // sps_max_num_reorder_pics
                r.ue()                                // sps_max_latency_increase_plus1
            }

            if (r.overran) return null

            HevcSps(
                generalProfileIdc = generalProfileIdc,
                generalLevelIdc = generalLevelIdc,
                chromaFormatIdc = chromaFormatIdc,
                bitDepthLuma = bitDepthLuma,
                width = width,
                height = height,
                maxDecPicBuffering = maxDecPicBuffering,
                maxNumReorderPics = maxNumReorderPics,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun skipScalingList(r: RbspReader, sizeOfList: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until sizeOfList) {
            if (nextScale != 0) {
                val delta = r.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    /** Returns false if the bitstream declares an implausible CPB count, i.e. we have desynced. */
    private fun skipHrdParameters(r: RbspReader): Boolean {
        val cpbCntMinus1 = r.ue()
        if (cpbCntMinus1 > MAX_CPB_CNT_MINUS1) return false
        r.skip(4)                                     // bit_rate_scale
        r.skip(4)                                     // cpb_size_scale
        for (i in 0..cpbCntMinus1) {
            r.ue()                                    // bit_rate_value_minus1
            r.ue()                                    // cpb_size_value_minus1
            r.skip(1)                                 // cbr_flag
        }
        r.skip(20)                                    // the four *_length_minus1 / time_offset_length
        return !r.overran
    }

    /** Profiles whose SPS carries the chroma/scaling-matrix block (H.264 7.3.2.1.1). */
    private val HIGH_PROFILES = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Spec maximum is 255; anything larger means the reader has lost its place. */
    private const val MAX_CPB_CNT_MINUS1 = 31

    /** Spec maximum is 255; a larger cycle means the reader has lost its place. */
    private const val MAX_POC_CYCLE = 255

    /**
     * Bit reader over an RBSP, i.e. one that removes emulation-prevention bytes as it goes.
     *
     * A `0x03` following two zero bytes is inserted by the encoder so a payload can never contain a
     * start code, and it is not part of the syntax. Reading it as if it were shifts every field
     * after it.
     *
     * [overran] latches instead of throwing, so a caller can parse to the end and then decide the
     * whole result is untrustworthy - a half-read field is worse than no field.
     */
    private class RbspReader(private val buf: ByteArray, offset: Int, length: Int) {
        private val end = offset + length
        private var bytePos = offset
        private var bitInByte = 0
        private var zeroRun = 0

        var overran: Boolean = false
            private set

        private fun currentByte(): Int {
            if (bytePos >= end || bytePos >= buf.size) {
                overran = true
                return 0
            }
            return buf[bytePos].toInt() and 0xFF
        }

        private fun advanceByte() {
            val consumed = currentByte()
            bytePos++
            zeroRun = if (consumed == 0) zeroRun + 1 else 0
            // Two zero bytes then 0x03: the 0x03 is not payload. Skip it and restart the run, so
            // 00 00 03 00 00 03 is handled as well as a single occurrence.
            if (zeroRun >= 2 && bytePos < end && bytePos < buf.size &&
                (buf[bytePos].toInt() and 0xFF) == 0x03
            ) {
                bytePos++
                zeroRun = 0
            }
        }

        fun u(count: Int): Int {
            var result = 0
            repeat(count) { result = (result shl 1) or bit() }
            return result
        }

        fun skip(count: Int) {
            repeat(count) { bit() }
        }

        /** Unsigned Exp-Golomb, ue(v). */
        fun ue(): Int {
            var leadingZeros = 0
            while (bit() == 0) {
                leadingZeros++
                if (overran || leadingZeros > 32) {
                    overran = true
                    return 0
                }
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + u(leadingZeros)
        }

        /** Signed Exp-Golomb, se(v). */
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }

        private fun bit(): Int {
            val b = currentByte()
            val value = (b shr (7 - bitInByte)) and 1
            bitInByte++
            if (bitInByte == 8) {
                bitInByte = 0
                advanceByte()
            }
            return value
        }
    }
}
