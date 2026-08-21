package com.andrerinas.openheadunit.aap

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
 * Two things differ here. The caller passes the pinned type, not a preference. And nothing gates on
 * the answer: this only ever decides whether to stop *asking* for a keyframe, so a missed one costs
 * one extra request and a false positive costs one request not sent. Neither can stick, and frames
 * keep flowing either way.
 */
object VideoKeyframeScanner {

    /** H.264: coded slice of an IDR picture. */
    private const val AVC_NAL_IDR = 5

    /** H.265 IRAP range - BLA_W_LP (16) through CRA_NUT (21). Any of them is a decoder entry point. */
    private val HEVC_IRAP_RANGE = 16..21

    /**
     * How many NAL headers to look at before giving up.
     *
     * A keyframe access unit leads with its parameter sets - SPS/PPS then the IDR slice on H.264,
     * VPS/SPS/PPS then the slice on H.265 - so the interesting NAL is never deep. Bounding the walk
     * keeps this off the profile on a path that runs per frame at 60fps.
     */
    private const val MAX_NAL_HEADERS_SCANNED = 8

    /**
     * Whether the access unit in [data] between [offset] and [offset] + [size] contains a keyframe.
     *
     * @param isHevc the codec the decoder has **pinned** for this session, never a user preference.
     */
    fun containsKeyframe(data: ByteArray, offset: Int, size: Int, isHevc: Boolean): Boolean {
        if (size < 4) return false
        val end = (offset + size).coerceAtMost(data.size)
        var i = offset
        var headersSeen = 0

        while (i + 3 < end && headersSeen < MAX_NAL_HEADERS_SCANNED) {
            val headerPos = startCodeHeaderPos(data, i, end)
            if (headerPos < 0) {
                i++
                continue
            }
            headersSeen++
            val b = data[headerPos].toInt()
            val isKeyframe = if (isHevc) {
                ((b and 0x7E) shr 1) in HEVC_IRAP_RANGE
            } else {
                (b and 0x1F) == AVC_NAL_IDR
            }
            if (isKeyframe) return true
            i = headerPos + 1
        }
        return false
    }

    /**
     * Position of the NAL header byte if a 3- or 4-byte start code begins at [i], else -1.
     *
     * Same shape as VideoDecoder.isCodecConfigData's walk, kept separate because that one answers a
     * different question about the first NAL only.
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
