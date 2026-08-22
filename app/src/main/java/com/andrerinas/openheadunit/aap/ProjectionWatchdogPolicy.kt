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
     * How long the display consumer may draw nothing, with video still arriving, before the
     * projection view is torn down and rebuilt (issue #650's recovery).
     *
     * Named here rather than inside the activity so other code can be pinned against it:
     * rebuilding the view costs a black flash, an EGL disconnect and touch loss, so anything that
     * deliberately holds frames off the screen must provably finish before this watchdog can see
     * it as a stall. The relation lives in CorruptionConcealmentPolicy's tests.
     */
    const val DISPLAY_FREEZE_MS = 5_000L

    /**
     * How often the projection watchdog looks. Detection runs at this granularity, so a condition
     * is seen up to one tick *after* its threshold is crossed - never before. What the tick cannot
     * do is shorten a deadline: anything racing [DISPLAY_FREEZE_MS] needs its margin under the
     * threshold itself, plus whatever age the last drawn frame already had when the race started,
     * since the watchdog measures from the draw.
     */
    const val WATCHDOG_TICK_MS = 2_000L

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

    /** How much recent time the display-stall watchdog's long-frame window is meant to cover. */
    const val LONG_FRAME_WINDOW_MS = 10_000L

    /**
     * How many long frames this tick may contribute, from a counter that runs for the whole session.
     *
     * The view's counter is cumulative and the previous reading is the baseline, so the difference is
     * only this tick's work if there *was* a previous tick recently enough to subtract. Two cases
     * break that, and both are the normal state of this watchdog rather than corner cases.
     *
     * The first tick of a session has no baseline at all. The check only survives its gates when the
     * picture has been still for over two seconds *and* video arrived within the last one and a half,
     * which is a narrow coincidence: on a link that stalls periodically it can be minutes before it
     * happens once. When it finally does, subtracting a baseline of zero charges every long frame of
     * the session so far to one tick. Measured on a reporter's capture, that fired exactly once in a
     * two-minute session and charged fourteen, tripping a floor of ten on its first evaluation and
     * rebuilding the projection view and the decoder - about 1.7 s more black screen plus a forced
     * focus cycle, for a fault no rebuild can address.
     *
     * The second is any gap longer than the window. Frames that piled up while this check was
     * returning early are not evidence about the last ten seconds, whoever caused them.
     *
     * Both answer zero and re-baseline. A consumer that is genuinely collapsing keeps ticking,
     * because video keeps arriving, and loses only its first tick.
     */
    fun longFramesThisTick(
        longFrameEvents: Long,
        previousEvents: Long,
        previousTickMs: Long,
        nowMs: Long,
        windowMs: Long = LONG_FRAME_WINDOW_MS
    ): Long {
        if (previousTickMs <= 0L) return 0L
        if (nowMs - previousTickMs > windowMs) return 0L
        return (longFrameEvents - previousEvents).coerceAtLeast(0L)
    }

    /**
     * How many long frames the display consumer produced inside the last [windowMs].
     *
     * The window it replaces counted slots rather than time, and its own comment called that
     * "~10s at the 2s watchdog cadence" - true only if the watchdog ticks every two seconds. It does
     * not. The display-stall check returns early whenever the phone is not currently sending video,
     * so on a link that stops the media periodically the ticks that run are the ones after each
     * outage, and five of those can span two minutes. The window silently became an accumulator, and
     * a counter meant to say "the consumer has collapsed in the last ten seconds" ended up saying
     * "an outage happened fourteen times this drive".
     *
     * Measured on a reporter's capture: a link that went dead for four to eight seconds every ten
     * added one long frame per outage, reached the floor of ten after about two minutes, and
     * rebuilt the projection view and the decoder for it - roughly 1.7 s more black screen plus a
     * forced focus cycle, in response to a fault no rebuild can fix.
     *
     * Slots whose tick fell outside the window are ignored rather than cleared, so a genuinely
     * collapsed consumer - which ticks steadily, because video is flowing throughout - still fills
     * the window and still trips the floor.
     *
     * [tickTimesMs] holds the clock reading for each slot of [counts], zero for a slot never
     * written.
     */
    fun longFramesInWindow(
        counts: LongArray,
        tickTimesMs: LongArray,
        nowMs: Long,
        windowMs: Long = LONG_FRAME_WINDOW_MS
    ): Long {
        var total = 0L
        for (i in counts.indices) {
            val tickMs = tickTimesMs.getOrElse(i) { 0L }
            if (tickMs <= 0L) continue
            if (nowMs - tickMs > windowMs) continue
            total += counts[i]
        }
        return total
    }

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
