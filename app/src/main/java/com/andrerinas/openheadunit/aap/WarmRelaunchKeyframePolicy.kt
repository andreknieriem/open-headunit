package com.andrerinas.openheadunit.aap

/**
 * Decides how hard to ask the phone for a picture when the projection surface has been replaced on a
 * session that was already rendering.
 *
 * The projection activity is destroyed and recreated whenever the main screen comes forward - the
 * launcher icon, a deep link - so a live session routinely rebuilds its decoder against a fresh
 * surface. Whether the picture comes straight back then depends on something that is not obvious
 * from the head unit's side: whether the *phone* was told to restart the video sink.
 *
 * Measured across twelve returns on a UNISOC MT50 (video-black rounds 5 and 6):
 *
 *  - The SurfaceView backend has its surface destroyed by the cover, so the teardown releases video
 *    focus, the phone answers with a sink stop, and on return the focus gain makes it re-run sink
 *    setup. `Media Start Request VIDEO` landed after 4 of 4 returns, and the picture followed
 *    42-96 ms after the codec was configured.
 *  - The TextureView and GLES backends never destroy their surface, so focus is never released and
 *    the phone is never told anything. `Media Start Request VIDEO` landed after 0 of 10 returns, and
 *    the picture took 6.3-115.9 s after the codec was configured - 97-99% of the whole return.
 *
 * The relaunch itself is not the cost and never was: it ran in 384-507 ms on every backend, and
 * codec creation in 49-308 ms.
 *
 * What does *not* work is asking again with the same message. An unsolicited focus gain sent while
 * the phone still believes the head unit holds focus is not a state change, and dozens of them go
 * out per slow return - one every 1.5 s from the overlay watchdog, one per decoder restart from the
 * transport - without one of them ever being followed by a picture. Only a real release/regain
 * cycle, the one the SurfaceView teardown performs by accident, moves the phone.
 *
 * So this policy escalates rather than repeats: a warm relaunch gets the cycle, once, and the
 * gain-only nudge is what remains for everything else.
 *
 * Round 7 then measured it against a same-day build of its own parent on the same rig. The four GLES
 * holds went from 30.8 s, 74.8 s, one cycle that never rendered at all, and 49.3 s to a flat
 * 3.04-3.20 s; TextureView the same. The cycle fired and `Media Start Request VIDEO` followed it on
 * fourteen of fourteen returns, and the SurfaceView backend - whose teardown already does all of this
 * - fired the policy zero times, which is the regression guard this is meant to pass.
 *
 * ### Why the cycle is gated rather than always sent
 *
 * Releasing video focus across a healthy, rendering stream is exactly the regression that made a
 * single dropped frame turn into a permanent few-fps freeze on some head unit / phone combinations,
 * which is why AapTransport's own recovery deliberately sends a gain only. Every gate
 * here requires the opposite situation: a decoder rebuilt under a surface that has never shown a
 * frame, on a session that has already proven itself. There is no picture to lose at that point,
 * and the once-per-surface cap bounds the cost if the phone answers badly anyway.
 */
object WarmRelaunchKeyframePolicy {

    /**
     * How long a freshly claimed surface is given to produce a picture on its own before the cycle
     * is spent on it.
     *
     * This is measured against the healthy path rather than guessed. The slowest a surface has been
     * seen to render unaided, across every capture of rounds 5-7, is **404 ms** from `New surface
     * set:` - a SurfaceView return whose codec creation took 308 ms and whose first frame followed
     * 96 ms later. See [HEALTHY_FIRST_FRAME_CEILING_MS], which the tests hold this above.
     *
     * It started at 2000 ms, chosen before any of this code had run and with no measurement of what
     * it needed to clear. That cost every return 1.2 s it did not need: round 7 measured the whole
     * escalation at a flat 3.0-3.2 s, of which this constant was the largest single term, and found
     * no case across fourteen cycles where a surface would have rendered on its own inside it.
     *
     * 850 ms is twice the ceiling, with the 42 ms over a round 800 deliberate: doubling the measured
     * worst case is the rule, and a rounder number that quietly sits under it is how a margin stops
     * meaning anything. It is still a rounding error against the 6.3-115.9 s this exists to prevent.
     *
     * Widening it is cheap and safe; narrowing it is what needs evidence, because the cost of firing
     * on a surface that was about to render is a focus release across a live stream - see the class
     * comment on why that is the one thing this must not do.
     */
    const val ESCALATE_AFTER_SURFACE_MS = 850L

    /**
     * The slowest unaided `New surface set:` → first frame ever measured, in milliseconds.
     *
     * Not used at runtime. It is the number [ESCALATE_AFTER_SURFACE_MS] is chosen against, recorded
     * here so a future change to that constant fails a test that explains itself rather than silently
     * moving under the healthy path.
     */
    const val HEALTHY_FIRST_FRAME_CEILING_MS = 404L

    /** Minimum spacing between two nudges, shared with the other keyframe requesters. */
    const val NUDGE_THROTTLE_MS = VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS

    /**
     * Gap between the release and the gain that make up the cycle.
     *
     * The pair has to read as two events to the phone, not one. On the SurfaceView backend, where
     * this sequence happens by accident, the two are seconds apart - the cover and the return - and
     * the phone acts on each. Sent back to back they would very likely be coalesced: the phone would
     * see focus land back on PROJECTED before it had acted on the loss, and do nothing, which is the
     * failure this whole policy exists to avoid. 400 ms is far above the send path's own latency,
     * short enough that the two halves stay inside one watchdog tick, and a rounding error against
     * the 6.3-115.9 s wait it replaces.
     */
    const val FOCUS_CYCLE_GAP_MS = 400L

    enum class Action {
        /** Ask for nothing; something else already has this covered. */
        NONE,

        /** Send the unsolicited focus gain, the existing behaviour. */
        NUDGE,

        /** Release video focus and take it back, so the phone re-runs sink setup. */
        CYCLE_FOCUS,
    }

    /**
     * @param sessionHasRendered whether any frame of this session ever reached the screen. False on
     *   a cold start, where the phone is already running sink setup of its own accord and a focus
     *   cycle would interrupt it.
     * @param renderedSinceSurfaceSet whether the current surface has shown a frame yet.
     * @param transportStarted whether the AAP session is in its steady state. Nothing can be sent
     *   otherwise, and a handshake in progress will produce sink setup on its own.
     * @param msSinceSurfaceSet age of the current surface.
     * @param msSinceLinkActivity age of the last AAP message of any kind from the phone - the whole
     *   link, not the video channel. See the gate below for why the distinction decides this policy.
     * @param linkQuietThresholdMs how long that may be before the phone counts as gone.
     * @param cycleAlreadySpent whether this surface has already had its one focus cycle.
     * @param msSinceLastRequest age of the last keyframe request of any kind.
     */
    fun decide(
        sessionHasRendered: Boolean,
        renderedSinceSurfaceSet: Boolean,
        transportStarted: Boolean,
        msSinceSurfaceSet: Long,
        msSinceLinkActivity: Long,
        linkQuietThresholdMs: Long,
        cycleAlreadySpent: Boolean,
        msSinceLastRequest: Long
    ): Action {
        if (!transportStarted) return Action.NONE
        // A picture is on screen. Nothing to ask for, and asking is what costs a stream.
        if (renderedSinceSurfaceSet) return Action.NONE
        // A cold start has never rendered, so there is no "was working a moment ago" to restore.
        if (!sessionHasRendered) return Action.NONE
        // The phone is gone. Nothing to ask, and the reconnecting overlay owns that case.
        //
        // This used to read the age of the last *video* bytes, against a 1.5s window, on the
        // reasoning that a focus cycle cannot fix a stream paused upstream. Both halves were wrong.
        // Android Auto sends no video at all while nothing on screen animates - the substance of the
        // idle-screen work elsewhere in this package - so on a paused full-screen player the gate
        // shuts within seconds and stays shut, taking the escalation with it. And a reporter's
        // capture has the cycle repairing exactly that stream: paused upstream for minutes, keyframe
        // 0.68s after the release, picture 0.70s.
        //
        // What the gate is for is a link that has gone away, and the app has that signal directly.
        if (msSinceLinkActivity > linkQuietThresholdMs) return Action.NONE
        // Give the surface the healthy path's own worst case before escalating.
        if (msSinceSurfaceSet < ESCALATE_AFTER_SURFACE_MS) return Action.NONE

        if (!cycleAlreadySpent) return Action.CYCLE_FOCUS
        if (msSinceLastRequest >= NUDGE_THROTTLE_MS) return Action.NUDGE
        return Action.NONE
    }
}
