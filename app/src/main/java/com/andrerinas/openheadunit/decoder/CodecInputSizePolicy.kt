package com.andrerinas.openheadunit.decoder

/**
 * How large an input buffer to ask a video decoder for.
 *
 * `KEY_MAX_INPUT_SIZE` is not advice. A component sizes its whole input port from it, and on a
 * 1 GB MediaTek head unit a 2 MB request for a 1280x720 H.265 stream was answered with
 *
 *     ACodec: Allocating 8 buffers of size 2097152 ... on input port
 *
 * which is 16 MB of graphics memory for video input alone, on a device whose entire Java heap was
 * running at 12-20 MB. The decoder was keeping up the whole time; the size of the machinery was the
 * problem, not its speed.
 *
 * The fixed tiers this replaces - 1 MB or 2 MB for H.264 by API level, 2 MB or 8 MB for H.265 by
 * resolution - were chosen against real hardware defects and are kept, as *caps*. What changes is
 * that the request is now derived from the picture, so a small stream asks for a small buffer.
 * At 1280x720 the derived figure is about 675 KB rather than 2 MB.
 *
 * The derivation is ExoPlayer's, from `MediaCodecVideoRenderer.getCodecMaxInputSize`: round the
 * picture up to whole macroblocks, take the 4:2:0 sample count, and divide by the minimum
 * compression ratio a conformant H.264 or H.265 encoder can achieve. A frame larger than that would
 * be essentially uncompressed. It is also strictly safe against the alternative Moonlight takes,
 * which is not to set the key at all and let the component choose.
 *
 * A frame that does not fit is already handled: [VideoDecoder] re-reads the real capacity from the
 * dequeued buffer - components allocate more or less than asked - and drops an oversized frame whole
 * rather than truncating it, which asks the phone for a keyframe. So the failure mode of asking for
 * too little is a logged, repaired frame, not a corrupt picture.
 */
object CodecInputSizePolicy {

    /** Macroblocks are 16x16 in both codecs. */
    private const val MACROBLOCK = 16

    /**
     * Bytes per pixel of a 4:2:0 frame, as a fraction: 3/2. Luma plus two half-resolution chroma
     * planes.
     */
    private const val YUV420_NUMERATOR = 3
    private const val YUV420_DENOMINATOR = 2

    /**
     * The lowest compression ratio a conformant H.264 or H.265 stream can have, per ExoPlayer's
     * table. Dividing by it turns "the whole uncompressed frame" into "the largest legal coded
     * frame".
     */
    private const val MIN_COMPRESSION_RATIO = 2

    /**
     * Floor on the request.
     *
     * Chosen so it binds only *below* 800x480, which is the smallest picture Android Auto negotiates
     * - so it never decides the answer for a real session, and it still catches a mis-parsed or
     * absurd dimension. Deliberately not larger: a floor set for comfort rather than for a reason
     * would throw away most of the saving at exactly the resolutions the cheap units use.
     */
    const val MIN_INPUT_SIZE_BYTES = 256 * 1024

    /**
     * The buffer size to request for a [width] x [height] picture, never above [cap].
     *
     * [cap] carries the existing per-codec, per-device tier so this can only ever ask for less than
     * before. A non-positive dimension means we do not know the picture yet, and the answer is then
     * the cap unchanged - shrinking a buffer on a guess is how a working unit stops working.
     */
    fun maxInputSizeFor(width: Int, height: Int, cap: Int): Int {
        if (width <= 0 || height <= 0) return cap
        val macroblocks = ceilDivide(width, MACROBLOCK).toLong() * ceilDivide(height, MACROBLOCK).toLong()
        val samples = macroblocks * MACROBLOCK * MACROBLOCK
        val derived = samples * YUV420_NUMERATOR / (YUV420_DENOMINATOR.toLong() * MIN_COMPRESSION_RATIO)
        val floored = maxOf(derived, MIN_INPUT_SIZE_BYTES.toLong())
        return minOf(floored, cap.toLong()).toInt()
    }

    private fun ceilDivide(numerator: Int, denominator: Int): Int = (numerator + denominator - 1) / denominator
}
