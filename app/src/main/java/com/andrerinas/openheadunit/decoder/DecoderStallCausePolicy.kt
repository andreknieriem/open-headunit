package com.andrerinas.openheadunit.decoder

/**
 * Separates a decoder that is broken from one that has simply not been given a keyframe yet.
 *
 * The output-side watchdog fires on "input is arriving and nothing is coming out", and its only
 * answer is to rebuild the MediaCodec. That answer is wrong for one common case, and wrong in a way
 * that feeds itself.
 *
 * A codec rebuilt mid-session resumes on whatever access unit arrives next, which is almost always a
 * P-frame. It cannot produce a picture from that, and it will not produce one until an IDR arrives.
 * Android Auto sends those on a fixed ~69s cadence that `VideoConfiguration` gives us no way to
 * shorten, and the protocol has no keyframe-request message at all - the release/regain focus cycle
 * is the only lever that exists. So the decoder is silent for up to a full GOP through no fault of
 * its own, the watchdog reads that silence as a fault, rebuilds again, and the rebuild resets the
 * wait. Each pass also runs the transport's decoder-error path, which used to cancel the very clock
 * that would have escalated to the cycle.
 *
 * Measured on a UNISOC MT50 under injected mid-stream corruption: four rebuilds in 33s, the restart
 * budget gone, then stalls at 10, 20, 30, 40, 50 and 60 seconds with `rendered=0` throughout while
 * input flowed at ~50fps. Android Auto's own client eventually asked whether its screen was visible.
 *
 * ### A keyframe that decodes, not one that was fed
 *
 * [keyframeDecodedSinceStart] is measured at the codec's output for a reason. An access unit that
 * lost a fragment in the middle still carries its parameter sets and IDR slice at the head, so it
 * scans as a keyframe, is fed like one, and produces no picture. Counting that as "the codec has had
 * what it needs" put this straight back on the rebuild path - and at one middle fragment lost in
 * three, a later round measured the whole pre-fix signature returning: two exhausted restart ladders
 * and 90+ seconds of `rendered=0`. See [KeyframeRepairTracker].
 *
 * Naming the starved case costs nothing on the healthy path and turns the loop into a wait.
 *
 * Pure: no clock, no logging. The caller supplies the elapsed times it already has.
 */
object DecoderStallCausePolicy {

    /**
     * How long a keyframe-starved decoder is left alone before the ordinary rebuild path resumes.
     *
     * A bound rather than an open wait, because "no keyframe has reached the codec" is also what a
     * decoder looks like when the fault is somewhere this cannot see. Everything meant to resolve the
     * starvation is far quicker than this: the escalated focus cycle produces a keyframe 0.52-0.78s
     * after the release, and the warm-relaunch path escalates after 850ms. Fifteen seconds is well
     * clear of both and well short of the ~69s GOP, so a decoder that is genuinely dead still reaches
     * the rebuild - and the codec-type fallback behind it - inside the same session.
     */
    const val KEYFRAME_STARVATION_PATIENCE_MS = 15_000L

    enum class Cause {
        /** The phone has stopped sending. Not a decoder fault and never was; the caller stays silent. */
        PHONE_IDLE,

        /**
         * Input is arriving, but no keyframe has decoded on this codec since it started, so there
         * is nothing it could have rendered. Ask for a keyframe; do not rebuild.
         */
        STARVED_OF_KEYFRAME,

        /** Input is arriving, the codec has had what it needs, and nothing is coming out. */
        STALLED,
    }

    /**
     * @param stallGapMs time since the last rendered frame, or since this codec started if none.
     * @param inputIdleGapMs time since the last bytes arrived from the phone.
     * @param inputIdleThresholdMs how long that gap has to be before the phone counts as idle.
     * @param keyframeDecodedSinceStart whether a keyframe has produced output on this codec
     *   instance. Not whether one was fed - a holed keyframe is fed and decodes to nothing.
     * @param sessionHasRendered whether any frame at all has rendered this session. A cold start that
     *   has never rendered stays on the ordinary path deliberately: there the codec type is still
     *   unproven, and the rebuild is what reaches the one-time fallback to the other codec.
     */
    fun classify(
        stallGapMs: Long,
        inputIdleGapMs: Long,
        inputIdleThresholdMs: Long,
        keyframeDecodedSinceStart: Boolean,
        sessionHasRendered: Boolean,
    ): Cause {
        if (inputIdleGapMs > inputIdleThresholdMs) return Cause.PHONE_IDLE
        if (keyframeDecodedSinceStart) return Cause.STALLED
        if (!sessionHasRendered) return Cause.STALLED
        if (stallGapMs >= KEYFRAME_STARVATION_PATIENCE_MS) return Cause.STALLED
        return Cause.STARVED_OF_KEYFRAME
    }
}
