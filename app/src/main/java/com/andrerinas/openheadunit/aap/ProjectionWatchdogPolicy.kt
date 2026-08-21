package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.CommManager

/**
 * Decides when the projection activity's recovery watchdog keeps running, when a stopped picture is
 * worth telling the user about, and when it is worth asking the phone for video again.
 *
 * The last two used to be one decision, and separating them is the point of this object: a picture
 * that has stopped is always worth a recovery request and only sometimes worth an overlay.
 *
 * Extracted because the previous inline check cost the watchdog its life on the first tick of
 * every session: it accepted only [CommManager.ConnectionState.HandshakeComplete], but that state
 * is a brief window before video starts - the steady state for the whole drive is
 * [CommManager.ConnectionState.TransportStarted] - and the runnable returned without re-posting
 * itself. Everything hanging off it (the reconnecting overlay, display-stall recovery, the
 * renderer-confirm offer) was unreachable in a normal session, so a video stream that died
 * mid-session stayed black with nothing ever asking for it back.
 */
object ProjectionWatchdogPolicy {

    /**
     * True while the projection session is live: [CommManager.ConnectionState.HandshakeComplete]
     * is the brief window before video starts, [CommManager.ConnectionState.TransportStarted] the
     * steady state after. The watchdog must keep ticking through both.
     */
    fun isSessionLive(state: CommManager.ConnectionState): Boolean =
        state is CommManager.ConnectionState.HandshakeComplete ||
            state is CommManager.ConnectionState.TransportStarted

    /** How long without a rendered frame before the picture stopping is worth reacting to. */
    const val FRAME_GAP_MS = 10_000L

    /**
     * How long the whole AAP link must *also* have been silent before a stopped picture is called a
     * lost connection.
     *
     * Android Auto's idle cadence has never been recorded - the phone's `PingRequest` is answered in
     * `AapControl` without logging anything - so this number is a judgement rather than a
     * measurement. It is a safe one because the gate is monotonic: if the real cadence is faster
     * than this the false positive disappears, and if it is slower the behaviour is exactly what
     * shipped before the gate existed. It can remove a false positive; it cannot add one. The
     * watchdog logs both gaps so a reporter's next capture replaces the judgement with a number.
     */
    const val LINK_QUIET_MS = 10_000L

    /**
     * Whether a stopped picture should be shown to the user as a lost connection.
     *
     * The overlay says "Connection lost", so the connection is what has to be measured. Frames alone
     * cannot carry that claim: Android Auto stops sending video whenever nothing on screen animates,
     * which made a paused full-screen music player identical to a dead socket and covered the
     * projection every 15-30s (issue #852). A real disconnect does not depend on this path at all -
     * `AapProjectionActivity` shows the same overlay immediately from
     * [CommManager.ConnectionState.Disconnected] - so what is left here is the narrow case of a
     * socket that is still up while the phone has gone silent.
     *
     * [linkQuietMs] is how long ago the phone last sent anything on any channel; pass
     * [Long.MAX_VALUE] when it has not sent anything yet, so a session that never started cannot
     * read as one that stopped.
     */
    fun shouldShowReconnecting(frameGapMs: Long, linkQuietMs: Long): Boolean =
        frameGapMs > FRAME_GAP_MS && linkQuietMs > LINK_QUIET_MS

    /**
     * Whether a black mid-session picture warrants asking the phone for video focus again.
     *
     * Gated on the picture having stopped, **not** on the overlay being up. The two were the same
     * condition until the overlay learned to check the link as well, and tying recovery to the
     * overlay after that would have made an idle-looking stream unrecoverable - which is the exact
     * failure the watchdog was revived to fix. Asking for video that had simply paused costs one
     * throttled message and nothing else; not asking for video that really stopped costs the rest of
     * the session. Paced by [VideoRecoveryPolicy] so a stuck session asks about once per throttle
     * window rather than on every tick.
     */
    fun shouldRequestVideoFocus(
        pictureStopped: Boolean,
        nowMs: Long,
        lastRequestMs: Long
    ): Boolean = pictureStopped && VideoRecoveryPolicy.canRequestKeyframe(nowMs, lastRequestMs)

    /**
     * Whether the surface the decoder was just handed still has no picture, and is worth nudging for.
     *
     * The nudge loop this drives used to be gated on the loading overlay being visible, which made a
     * cosmetic state decide whether recovery ran at all - and one relaunch in two hid that overlay
     * before the loop's first tick, because the overlay decision reads a rendered-frame stamp that
     * the surface handoff zeroes a moment later. The loop does not re-post when it declines, so a
     * single silent tick ended it for that instance. Measured on a reporter's capture: four
     * relaunches, alternating, two of them silent for 11.8s and 23s with a black screen and nothing
     * asking for video.
     *
     * The same reasoning as [shouldRequestVideoFocus], which was moved off the overlay for the same
     * reason. What decides recovery is the picture, not what is drawn over it.
     *
     * [warmRelaunchCycleSpent] is the bound. The overlay used to supply one incidentally, being up
     * only before a session's first frame; without it this would nudge every window forever on a
     * screen that simply has nothing to draw. Once the escalation has spent its cycle,
     * [WarmRelaunchKeyframePolicy]'s own throttled nudge takes over and this steps back.
     *
     * @param sessionLive see [isSessionLive].
     * @param surfaceSet whether the decoder has been handed a surface at all yet.
     * @param renderedSinceSurfaceSet whether any frame has reached the screen on that surface.
     */
    fun shouldNudgeForFirstFrame(
        sessionLive: Boolean,
        surfaceSet: Boolean,
        renderedSinceSurfaceSet: Boolean,
        warmRelaunchCycleSpent: Boolean,
    ): Boolean = sessionLive && surfaceSet && !renderedSinceSurfaceSet && !warmRelaunchCycleSpent
}
