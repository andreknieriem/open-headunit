package com.andrerinas.openheadunit.decoder.video

/**
 * Whether frames reaching the screen amount to a picture, or only to output.
 *
 * A codec rebuilt against a new surface keeps its cached parameter sets, so it configures and starts
 * on a P-frame and some components emit output from it - gray, but rendered. Every recovery gate that
 * asks "has a frame rendered on this surface" then reads that as a working picture and stands down,
 * and the screen stays gray until the phone's next natural keyframe, up to a full GOP away.
 *
 * The question those gates need answered is whether a keyframe accounts for what is on screen.
 * [KeyframeRepairTracker] already tracks that per codec instance; this turns it into the one boolean
 * the gates read.
 */
object PictureCredibilityPolicy {

    /**
     * How long a fed keyframe may still be working its way to the output before the picture stops
     * counting as credible.
     *
     * Twice [KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED] frames' worth at the lowest
     * plausible rate, so a component whose timestamps cannot be used is never called incredible on a
     * stream that is fine.
     */
    const val PENDING_KEYFRAME_GRACE_MS = 2_000L

    /**
     * @param renderedSinceCodecStart whether any frame has reached the surface since this codec
     *   started.
     * @param keyframeAccountingAvailable whether keyframes are tracked at all on this path. The
     *   bundled software video decoder does not feed [KeyframeRepairTracker], so a rendered frame is
     *   the only evidence there is and asking for more would starve it forever.
     * @param keyframeDecodedSinceCodecStart whether a keyframe has produced output.
     * @param msSincePendingKeyframeFed age of the keyframe still waiting at the output, or
     *   [Long.MAX_VALUE] when none is pending.
     */
    fun hasCrediblePicture(
        renderedSinceCodecStart: Boolean,
        keyframeAccountingAvailable: Boolean,
        keyframeDecodedSinceCodecStart: Boolean,
        msSincePendingKeyframeFed: Long,
    ): Boolean {
        if (!renderedSinceCodecStart) return false
        if (!keyframeAccountingAvailable) return true
        if (keyframeDecodedSinceCodecStart) return true
        // A keyframe is in the pipe and the repair is already under way. Past the grace it has
        // demonstrably produced nothing, which is a holed keyframe, not a picture.
        return msSincePendingKeyframeFed < PENDING_KEYFRAME_GRACE_MS
    }
}
