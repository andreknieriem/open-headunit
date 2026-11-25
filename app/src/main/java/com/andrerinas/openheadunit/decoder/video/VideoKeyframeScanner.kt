package com.andrerinas.openheadunit.decoder.video

/**
 * Answers one question about an access unit: does it carry a keyframe the decoder can start from?
 *
 * Recovery from a shed reference frame is only over when a keyframe actually reaches the codec, and
 * nothing else in the app can tell. Frame size is the closest thing - the hardware rounds identified
 * keyframes as assembled frames above a threshold - and it does not hold up: round 4 measured a
 * healthy stream whose true keyframes were 67-78KB against a 1.5KB median, with a scatter of 5-18KB
 * single-message frames in between that no threshold separates. Only the fragmentation pattern told
 * those apart, and only because that capture happened to have one. Reading the NAL type is exact.
 *
 * Round 4 checked this against the size method over an undisturbed session and the two agreed on all
 * three keyframes: same count, within 21ms on timing, within 10 bytes on size.
 *
 * ### Why this is safe when the last attempt was not
 *
 * Commit 66e59b2c removed a `waitingForKeyframe` latch that read NAL headers and held **every
 * subsequent P-frame back** until it was satisfied. It read them against the user's codec
 * *preference* rather than the type the decoder actually pinned, so on a mismatched stream it never
 * saw its keyframe, latched for the session and froze the picture with the connection healthy.
 *
 * Recovery requests still use [containsKeyframe] as a signal, not a latch. Its caller passes the
 * pinned type, not a preference, and a missed detection never prevents later frames flowing.
 * Timed software queue saturation uses the narrower, separate [isIdrAccessUnit] check.
 */
object VideoKeyframeScanner {

    /** H.264: coded slice of an IDR picture. */
    private const val AVC_NAL_IDR = 5

    /** H.265 IRAP range. The decoder still determines reference and leading-picture validity. */
    private val HEVC_IRAP_RANGE = 16..21

    /**
     * Whether the access unit in [data] between [offset] and [offset] + [size] contains a keyframe.
     * The view is clipped to the array end; an invalid offset or nonpositive size has no keyframe.
     *
     * @param isHevc the codec the decoder has **pinned** for this session, never a user preference.
     */
    fun containsKeyframe(data: ByteArray, offset: Int, size: Int, isHevc: Boolean): Boolean =
        anyNalHeader(data, offset, size, isHevc) { headerPos ->
            val b = data[headerPos].toInt()
            if (isHevc) {
                (b and 0x80) == 0 && ((b and 0x7E) shr 1) in HEVC_IRAP_RANGE &&
                    (data[headerPos + 1].toInt() and 0x07) != 0
            } else {
                (b and 0x80) == 0 && (b and 0x60) != 0 && (b and 0x1F) == AVC_NAL_IDR
            }
        }

    /** A closed IDR access unit may cut a queued reference. HEVC CRA can have dependent leading pictures. */
    internal fun isIdrAccessUnit(data: ByteArray, size: Int, isHevc: Boolean): Boolean {
        var hasIdr = false
        val hasOtherNal = anyNalHeader(data, 0, size, isHevc) { headerPos ->
            val b = data[headerPos].toInt()
            if (isHevc) {
                val kind = (b and 0x7E) shr 1
                if (!isBaseLayerHevcHeader(data, headerPos)) true
                else when (kind) {
                    19, 20 -> { hasIdr = true; false }
                    in 32..35, in 38..40 -> false // configuration, AUD, filler and SEI
                    else -> true
                }
            } else {
                if ((b and 0x80) != 0) true
                else when (b and 0x1F) {
                    AVC_NAL_IDR -> {
                        if ((b and 0x60) == 0) true
                        else { hasIdr = true; false }
                    }
                    6, 7, 8, 9, 12, 13, 15 -> false // SEI, parameter sets, AUD and filler
                    else -> true
                }
            }
        }
        return hasIdr && !hasOtherNal
    }

    /** Unknown, configuration-bearing, and random-access units must not be evicted. */
    internal fun containsOnlyNonIdrSlices(data: ByteArray, size: Int, isHevc: Boolean): Boolean {
        var hasSlice = false
        val hasOtherNal = anyNalHeader(data, 0, size, isHevc) { headerPos ->
            val b = data[headerPos].toInt()
            val isSlice = if (isHevc) {
                isBaseLayerHevcHeader(data, headerPos) && ((b and 0x7E) shr 1) in 0..9
            } else {
                (b and 0x80) == 0 && (b and 0x1F) == 1
            }
            if (isSlice) hasSlice = true
            !isSlice
        }
        return hasSlice && !hasOtherNal
    }

    private fun isBaseLayerHevcHeader(data: ByteArray, headerPos: Int): Boolean =
        (data[headerPos].toInt() and 0x81) == 0 &&
            (data[headerPos + 1].toInt() and 0xFF) in 1..7

    private inline fun anyNalHeader(
        data: ByteArray, offset: Int, size: Int, isHevc: Boolean, matches: (Int) -> Boolean,
    ): Boolean {
        if (offset < 0 || offset >= data.size || size <= 0) return false
        val end = offset + minOf(size, data.size - offset)
        val headerSize = if (isHevc) 2 else 1
        val lastStart = end - headerSize - 3
        var i = offset

        while (i <= lastStart) {
            // Search for 00 00 01, which also covers the suffix of a four-byte start code.
            // A nonzero third byte rules out prefixes at i+1 and i+2, so skip all three.
            val third = data[i + 2].toInt()
            if (third == 0) {
                i++
                continue
            }
            if (third == 1 && data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                matches(i + 3)) return true
            i += 3
        }
        return false
    }

}
