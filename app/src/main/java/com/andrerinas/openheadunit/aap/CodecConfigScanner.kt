package com.andrerinas.openheadunit.aap

/**
 * Answers what an access unit is made of, so `BUFFER_FLAG_CODEC_CONFIG` can be set on the ones that
 * are only configuration and never on the ones that also carry a picture.
 *
 * `BUFFER_FLAG_CODEC_CONFIG` tells MediaCodec the buffer is codec-specific data and nothing else, so
 * a component is entitled to consume it and produce no output. Setting it on an access unit that also
 * contains a coded slice therefore risks the slice being discarded - and if that slice is the IDR, the
 * picture never repairs.
 *
 * The check this replaces read only the **first** NAL of the access unit and answered for the whole
 * thing. Two ways for that to be wrong, in opposite directions:
 *
 * - A unit led by an access-unit delimiter or an SEI was reported as carrying no parameter sets even
 *   when SPS and PPS followed, so decoders that require the flag - Moonlight's errata names TI OMAP4
 *   as crashing on the IDR without it - never got it.
 * - A unit led by SPS and continuing into PPS and an IDR slice was reported as configuration, so on
 *   the three chipset families that flag every config packet (Rockchip, Allwinner, Telechips) the
 *   whole keyframe was handed over marked as configuration.
 *
 * Reading every NAL header in the unit, bounded, answers both.
 */
object CodecConfigScanner {

    /** What an access unit contains, as far as the config flag is concerned. */
    enum class Content {
        /** Parameter sets and nothing that decodes to a picture. Safe to flag as codec config. */
        PARAMETER_SETS_ONLY,

        /**
         * Parameter sets *and* a coded picture, which is how a keyframe usually arrives.
         *
         * Must not be flagged as codec config. Decoders read in-band parameter sets from an ordinary
         * buffer perfectly well; what they cannot do is give back a picture from a buffer they were
         * told contains no picture.
         */
        PARAMETER_SETS_WITH_PICTURE,

        /** An ordinary frame. */
        NO_PARAMETER_SETS,
    }

    /** H.264 parameter-set NAL types: SPS, PPS, and the two SPS extensions. */
    private val AVC_PARAMETER_SETS = intArrayOf(7, 8, 13, 15)

    /** H.264 VCL NAL types - anything that codes picture data. */
    private val AVC_PICTURE_RANGE = 1..5

    /** H.265 parameter-set NAL types: VPS, SPS, PPS. */
    private val HEVC_PARAMETER_SETS = intArrayOf(32, 33, 34)

    /** H.265 VCL NAL types. Everything from 32 up is non-VCL. */
    private val HEVC_PICTURE_RANGE = 0..31

    /**
     * How many NAL headers to look at before giving up.
     *
     * A parameter-set unit is a handful of small NALs; a keyframe leads with them and then has its
     * slices, so everything this needs to see is at the front.
     */
    const val MAX_NAL_HEADERS_SCANNED = 16

    /**
     * How far into the unit to look before giving up.
     *
     * This runs on the feed thread once per frame, so an unbounded walk is 60 scans a second over
     * frames that reach hundreds of kilobytes - and a P-frame contains no start code after its first,
     * so an unbounded walk really does read all of it. Everything that decides the answer is in the
     * leading run of delimiters, SEIs, parameter sets and the first slice header, which a few hundred
     * bytes covers with room to spare.
     *
     * Bounding it is only safe because hitting the bound is treated as *not knowing*, and not knowing
     * resolves to [Content.PARAMETER_SETS_WITH_PICTURE] - the answer that withholds the flag. A
     * config-only unit is fifty-odd bytes and finishes long before the bound, so it is never the one
     * that gets cut off.
     */
    const val MAX_BYTES_SCANNED = 512

    /**
     * Classifies the access unit in [data] between [offset] and [offset] + [size].
     *
     * @param isHevc the codec the decoder has **pinned** for this session, never a user preference -
     *   the NAL type encoding differs between the two and reading one as the other is how a previous
     *   keyframe latch froze sessions.
     */
    fun classify(data: ByteArray, offset: Int, size: Int, isHevc: Boolean): Content {
        if (size < 5) return Content.NO_PARAMETER_SETS
        val end = (offset + size).coerceAtMost(data.size)
        val scanEnd = minOf(end, offset + MAX_BYTES_SCANNED)
        val parameterSets = if (isHevc) HEVC_PARAMETER_SETS else AVC_PARAMETER_SETS
        val pictureRange = if (isHevc) HEVC_PICTURE_RANGE else AVC_PICTURE_RANGE

        var i = offset
        var headersSeen = 0
        var sawParameterSet = false
        var sawPicture = false
        // True when the walk stopped at a bound rather than at the end of the unit, i.e. there may be
        // more in the unit than we looked at.
        var incomplete = false

        while (i + 3 < scanEnd) {
            if (headersSeen >= MAX_NAL_HEADERS_SCANNED) {
                incomplete = true
                break
            }
            val headerPos = startCodeHeaderPos(data, i, end)
            if (headerPos < 0) {
                i++
                continue
            }
            headersSeen++
            val b = data[headerPos].toInt()
            val nalType = if (isHevc) (b and 0x7E) shr 1 else b and 0x1F

            if (parameterSets.contains(nalType)) sawParameterSet = true
            if (nalType in pictureRange) sawPicture = true

            // Both answers are settled; the rest of the unit cannot change them.
            if (sawParameterSet && sawPicture) return Content.PARAMETER_SETS_WITH_PICTURE

            i = headerPos + 1
        }
        if (scanEnd < end) incomplete = true

        return when {
            // Not knowing resolves to the answer that withholds the flag, never to the one that
            // risks a picture being consumed as configuration.
            sawParameterSet && incomplete -> Content.PARAMETER_SETS_WITH_PICTURE
            sawParameterSet -> Content.PARAMETER_SETS_ONLY
            else -> Content.NO_PARAMETER_SETS
        }
    }

    /**
     * Position of the NAL header byte if a 3- or 4-byte start code begins at [i], else -1.
     *
     * Same walk as [VideoKeyframeScanner]'s, deliberately duplicated rather than shared: the two
     * answer different questions and the temptation to fuse them into one pass over the frame is how
     * a change for one silently alters the other.
     */
    private fun startCodeHeaderPos(data: ByteArray, i: Int, end: Int): Int {
        if (data[i].toInt() != 0 || data[i + 1].toInt() != 0) return -1
        val headerPos = when {
            data[i + 2].toInt() == 1 -> i + 3
            data[i + 2].toInt() == 0 && i + 3 < end && data[i + 3].toInt() == 1 -> i + 4
            else -> return -1
        }
        return if (headerPos < end) headerPos else -1
    }
}
