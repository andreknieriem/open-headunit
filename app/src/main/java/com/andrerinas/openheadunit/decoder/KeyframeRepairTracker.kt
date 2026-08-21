package com.andrerinas.openheadunit.decoder

/**
 * Separates a keyframe that was handed to the codec from one that produced a picture.
 *
 * Everything upstream of the codec answers the first question. [com.andrerinas.openheadunit.aap.VideoKeyframeScanner]
 * reads the first few NAL headers at the *head* of an access unit, so an access unit whose middle
 * arrived damaged - a fragment lost on the link, the exact shape of the melting-picture reports -
 * still scans as a keyframe. It is fed, it is logged, and it decodes to nothing.
 *
 * That mattered because "a keyframe was fed" was wired to two decisions that both mean "the picture
 * is fine again":
 *
 *  - [DecoderStallCausePolicy] stopped calling the codec starved, so the output watchdog went back
 *    to rebuilding it - and each rebuild resumes on a P-frame, which is the loop the starvation case
 *    exists to break.
 *  - `AapTransport`'s escalation clock was cancelled, so the focus cycle that would have fetched a
 *    *real* keyframe was pushed back behind the next drop.
 *
 * Measured on a UNISOC MT50 under one-in-three middle-fragment loss: roughly a third of keyframes
 * arrive holed, both resets fire repeatedly, and the picture sits at `rendered=0` for 90+ seconds
 * while the restart budget is spent twice over.
 *
 * So the repair signal is moved to the far side of the codec. A keyframe is pending until a frame
 * with at least its presentation timestamp comes out.
 *
 * ### Why the timestamp and not "the next frame rendered"
 *
 * Two reasons, and the first is the one that bites. MediaCodec keeps frames queued ahead of the
 * feed, so the render that follows a keyframe feed is usually a frame from *before* it - "something
 * rendered after we fed it" would confirm on output the keyframe had no part in. The second is why
 * a rendered frame is not the signal on its own: after a shed reference frame the decoder renders
 * continuously and the picture is wrong the whole time, which is the case the keyframe clock was
 * written for.
 *
 * Presentation timestamps are monotonic within one codec instance and restart near zero when the
 * codec is reconfigured, so [reset] on every restart is not optional - a pending stamp from the old
 * codec would be confirmed by the new one's first frame.
 *
 * ### The decoder that does not carry timestamps through
 *
 * Some components hand every output buffer back with the same presentation timestamp, or zero. On
 * one of those, a keyframe would stay pending while the picture ran perfectly - the decoder would be
 * called starved every fifteen seconds and the transport would spend focus cycles on a stream that
 * never needed them, which is a worse thing to ship than the wedge this replaces. So a keyframe that
 * is still pending after [RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED] frames have come out is confirmed
 * anyway, and [timestampsUnusable] says why. The bound is far past any real reorder depth and far
 * short of the fifteen seconds the starvation path waits, so a working decoder never reaches it.
 *
 * Pure and thread-safe: written from the feed thread, read and cleared from the output thread.
 */
class KeyframeRepairTracker {

    /** Presentation timestamp of the newest keyframe fed and not yet seen at the output. */
    private var pendingPtsUs = NONE

    private var decoded = false

    /** Frames rendered since the pending keyframe was fed, none of which reached its timestamp. */
    private var rendersWhilePending = 0

    private var disbelievedTimestamps = false

    /** Whether any keyframe has produced output since the last [reset]. */
    val keyframeDecoded: Boolean
        @Synchronized get() = decoded

    /** Whether a keyframe is fed and still waiting to be seen at the output. Diagnostic. */
    val awaitingOutput: Boolean
        @Synchronized get() = pendingPtsUs != NONE

    /**
     * Whether a keyframe has been confirmed by frame count rather than by timestamp.
     *
     * True means this component's output timestamps cannot be used, so every repair from here is
     * being read from the fact that frames are coming out at all. Worth saying once in a log: it is
     * the difference between "the picture came back" and "the picture came back and we cannot see
     * which frame brought it".
     */
    val timestampsUnusable: Boolean
        @Synchronized get() = disbelievedTimestamps

    /**
     * A keyframe was queued to the codec at [ptsUs].
     *
     * The newest one replaces any older pending stamp: confirming the newest confirms every keyframe
     * before it, since output is monotonic, and holding the oldest would let one holed keyframe be
     * confirmed by a later good one's picture.
     */
    @Synchronized
    fun onKeyframeFed(ptsUs: Long) {
        pendingPtsUs = ptsUs
        rendersWhilePending = 0
    }

    /**
     * A frame with [ptsUs] reached the surface.
     *
     * @return true exactly once per pending keyframe, on the first frame at or past its timestamp -
     *   the moment the picture is genuinely repaired.
     */
    @Synchronized
    fun onFrameRendered(ptsUs: Long): Boolean {
        if (pendingPtsUs == NONE) return false
        if (ptsUs < pendingPtsUs) {
            rendersWhilePending++
            if (rendersWhilePending < RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED) return false
            // Frames are reaching the screen and none of them ever reports the keyframe's stamp.
            // The picture is demonstrably back; only the attribution is lost.
            disbelievedTimestamps = true
        }
        pendingPtsUs = NONE
        rendersWhilePending = 0
        decoded = true
        return true
    }

    /** Drops all state. Call wherever the codec is rebuilt - see the class comment on timestamps. */
    @Synchronized
    fun reset() {
        pendingPtsUs = NONE
        rendersWhilePending = 0
        decoded = false
        // Deliberately not cleared: a component that mangles timestamps still does after a rebuild,
        // and re-learning it would mean another burst of frames confirmed the slow way each time.
    }

    companion object {
        /**
         * How many rendered frames may pass a pending keyframe's timestamp by before the timestamps
         * are treated as meaningless.
         *
         * Half a second at 60fps, which is orders of magnitude past any reorder depth a projected
         * stream carries and well short of [DecoderStallCausePolicy.KEYFRAME_STARVATION_PATIENCE_MS],
         * so the only decoder that reaches it is one whose output stamps say nothing.
         */
        const val RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED = 30

        /** No keyframe is waiting. Not a valid timestamp: presentation stamps here are never negative. */
        private const val NONE = -1L
    }
}
