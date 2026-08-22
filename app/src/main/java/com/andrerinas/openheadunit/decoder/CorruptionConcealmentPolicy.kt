package com.andrerinas.openheadunit.decoder

/**
 * Decides, per output buffer, whether the screen shows it - so a stream that just lost an access
 * unit freezes on the last good frame instead of melting through every P-frame that references a
 * picture the decoder never had.
 *
 * ### Why the gate is on the render and nowhere else
 *
 * Two earlier concealment mechanisms were removed for latching, and their post-mortems set the
 * constraints this satisfies. Gating the *feed* starved the codec outright; reading NAL types
 * against the user's codec preference misread every keyframe in reachable configurations; and an
 * unbounded hold froze a healthy session. So this object gates only [Outcome.renders]: the feed
 * is untouched, the codec keeps decoding and keeps its reference state current, and the worst
 * possible failure is the screen showing the wrong subset of frames for a bounded time. It never
 * reads a NAL itself - the repair signal comes in from [KeyframeRepairTracker], whose scan runs
 * against the decoder's *pinned* codec type and which carries its own escape hatch for components
 * with unusable output timestamps.
 *
 * ### Why the freeze is worth having at all
 *
 * The protocol has no keyframe request, so an unbounded freeze would run to the phone's own
 * roughly-a-minute GOP, which is why an earlier freeze proposal was rejected. What changed is the
 * escalation: a corrupt access unit now earns a focus release/regain cycle that produces a
 * keyframe in under three seconds. A freeze bounded just above that hides the melt for the whole
 * of a successful repair and costs at most [CONCEAL_MAX_MS] of still picture when the repair does
 * not come.
 *
 * ### The no-latch guarantees, each pinned by a test
 *
 * The window is measured from the *first* corruption report ([CONCEAL_MAX_MS] from
 * `windowOpenedMs`); later reports never extend it. A window that expires [Outcome.CLOSE_EXPIRED]
 * disarms this policy until a keyframe repairs the picture, so sustained loss buys exactly one
 * freeze and then the honest smear - never a strobing alternation of the two. A lost window stamp
 * (`windowOpenedMs == 0` while [State.CONCEALING]) reads as an expired window, so the failure
 * mode of broken bookkeeping is *resuming*, not freezing. And the caller is expected to consult
 * this once per output-loop tick as well as per buffer, so the cap holds even when the phone goes
 * idle mid-window and no further buffers arrive.
 *
 * The caller owns the state and must consume the corruption report it passes in *whatever* the
 * outcome is - a report arriving while [State.DISARMED] is answered with [Outcome.SHOW] and must
 * not be kept around to reopen a window after the next repair.
 */
object CorruptionConcealmentPolicy {

    /**
     * Longest the picture may be held still, measured from the first corruption report.
     *
     * Bounded from both sides. Below: it must outlast the slowest measured escalated repair
     * ([ESCALATED_REPAIR_OBSERVED_MS]), or the freeze ends in a smear in exactly the case it was
     * built for. Above: it must stay clear of
     * [com.andrerinas.openheadunit.aap.ProjectionWatchdogPolicy.DISPLAY_FREEZE_MS], because a
     * freeze that watchdog can see is answered with a projection-view rebuild, strictly worse than
     * the smear being hidden; that watchdog measures from the last *draw*, so the margin also
     * absorbs the age the last drawn frame had when the window opened. Tests pin both relations;
     * past 4000 the display-freeze margin is gone, so it widens no further.
     */
    const val CONCEAL_MAX_MS = 3_500L

    /**
     * Slowest escalated repair ever measured end to end, corruption to rendered keyframe. Nothing
     * reads this at runtime; it is the number [CONCEAL_MAX_MS] is chosen against, and a test holds
     * the two in order.
     */
    const val ESCALATED_REPAIR_OBSERVED_MS = 2_780L

    enum class State {
        /** Rendering, and a corruption report would open a window. */
        ARMED,

        /**
         * Rendering, and a corruption report would not: the previous window ran out without a
         * repair, so the stream is treated as smearing until a keyframe proves otherwise.
         */
        DISARMED,

        /** A window is open; output buffers are decoded and not shown. */
        CONCEALING,
    }

    /** What to do with the buffer in hand, and the state transition it carries. */
    enum class Outcome(val renders: Boolean) {
        /** Render. No state change. */
        SHOW(true),

        /** Render. State becomes [State.ARMED]: a keyframe repaired the picture in plain view. */
        REARM(true),

        /** Conceal. State becomes [State.CONCEALING]; the caller stamps the window open now. */
        OPEN(false),

        /** Conceal. No state change. */
        CONCEAL(false),

        /** Render - this buffer is the repaired picture. State becomes [State.ARMED]. */
        CLOSE_REPAIRED(true),

        /**
         * Render. State becomes [State.DISARMED]: the cap ran out, and a longer freeze is worse
         * than the smear it hides.
         */
        CLOSE_EXPIRED(true),
    }

    /**
     * One step. The caller applies the transition the outcome names and, on [Outcome.OPEN], stamps
     * `windowOpenedMs = nowMs`; on either close it may zero the stamp.
     *
     * @param windowOpenedMs when the current window opened; meaningful only in [State.CONCEALING].
     * @param corruptionReported a new corruption report has arrived since the last step.
     * @param keyframeRepaired [KeyframeRepairTracker] confirmed the repaired picture this step.
     * @param sessionHasRendered whether any frame has reached the screen this session - before the
     *   first one there is nothing on the surface worth holding, and the warm-start window belongs
     *   to [com.andrerinas.openheadunit.aap.WarmRelaunchKeyframePolicy], not to this.
     */
    fun next(
        nowMs: Long,
        state: State,
        windowOpenedMs: Long,
        corruptionReported: Boolean,
        keyframeRepaired: Boolean,
        sessionHasRendered: Boolean,
    ): Outcome {
        if (state == State.CONCEALING) {
            // The repair outranks the cap and outranks any new report: the buffer in hand when the
            // repair confirms is the repaired picture, and showing it is always right.
            if (keyframeRepaired) return Outcome.CLOSE_REPAIRED
            if (nowMs - windowOpenedMs >= CONCEAL_MAX_MS) return Outcome.CLOSE_EXPIRED
            return Outcome.CONCEAL
        }
        if (keyframeRepaired) return Outcome.REARM
        if (!sessionHasRendered) return Outcome.SHOW
        if (corruptionReported && state == State.ARMED) return Outcome.OPEN
        return Outcome.SHOW
    }
}
