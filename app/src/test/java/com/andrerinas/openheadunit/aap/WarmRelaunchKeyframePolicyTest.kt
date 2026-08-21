package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.WarmRelaunchKeyframePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gates are spelled out one at a time because each of them is what keeps a video-focus release
 * away from a stream that is working. A release across a healthy stream is a known way to drop one
 * to a few fps permanently, so the escalation is only ever correct in the one situation these
 * arguments describe together: a decoder rebuilt under a surface that has never shown a frame, on a
 * session that already proved itself, with the phone still on the link.
 */
class WarmRelaunchKeyframePolicyTest {

    /** A relaunch that has gone 5s without a picture while the phone is on the link: the whole point. */
    private fun warmRelaunch(
        sessionHasRendered: Boolean = true,
        renderedSinceSurfaceSet: Boolean = false,
        transportStarted: Boolean = true,
        msSinceSurfaceSet: Long = 5_000,
        msSinceLinkActivity: Long = 100,
        linkQuietThresholdMs: Long = ProjectionWatchdogPolicy.LINK_QUIET_MS,
        cycleAlreadySpent: Boolean = false,
        msSinceLastRequest: Long = 60_000
    ) = WarmRelaunchKeyframePolicy.decide(
        sessionHasRendered = sessionHasRendered,
        renderedSinceSurfaceSet = renderedSinceSurfaceSet,
        transportStarted = transportStarted,
        msSinceSurfaceSet = msSinceSurfaceSet,
        msSinceLinkActivity = msSinceLinkActivity,
        linkQuietThresholdMs = linkQuietThresholdMs,
        cycleAlreadySpent = cycleAlreadySpent,
        msSinceLastRequest = msSinceLastRequest
    )

    @Test
    fun `a warm relaunch with no picture gets the focus cycle`() {
        assertEquals(Action.CYCLE_FOCUS, warmRelaunch())
    }

    @Test
    fun `the cycle is spent once per surface and degrades to the nudge`() {
        assertEquals(Action.NUDGE, warmRelaunch(cycleAlreadySpent = true))
    }

    @Test
    fun `the nudge respects the shared keyframe throttle`() {
        assertEquals(
            Action.NONE,
            warmRelaunch(
                cycleAlreadySpent = true,
                msSinceLastRequest = WarmRelaunchKeyframePolicy.NUDGE_THROTTLE_MS - 1
            )
        )
        assertEquals(
            Action.NUDGE,
            warmRelaunch(
                cycleAlreadySpent = true,
                msSinceLastRequest = WarmRelaunchKeyframePolicy.NUDGE_THROTTLE_MS
            )
        )
    }

    @Test
    fun `a picture on screen is never disturbed`() {
        // The regression this exists to avoid: asking again is what costs a working stream.
        assertEquals(Action.NONE, warmRelaunch(renderedSinceSurfaceSet = true))
        assertEquals(
            Action.NONE,
            warmRelaunch(renderedSinceSurfaceSet = true, cycleAlreadySpent = true)
        )
    }

    @Test
    fun `a cold start is left to the phone's own sink setup`() {
        // Nothing has ever rendered, so there is no proven stream to bring back and the phone is
        // already running setup of its own accord.
        assertEquals(Action.NONE, warmRelaunch(sessionHasRendered = false))
    }

    @Test
    fun `nothing is sent unless the transport is in its steady state`() {
        assertEquals(Action.NONE, warmRelaunch(transportStarted = false))
    }

    @Test
    fun `a phone that has left the link is a disconnect, not a stall`() {
        assertEquals(
            Action.NONE,
            warmRelaunch(msSinceLinkActivity = ProjectionWatchdogPolicy.LINK_QUIET_MS + 1)
        )
        assertEquals(
            Action.CYCLE_FOCUS,
            warmRelaunch(msSinceLinkActivity = ProjectionWatchdogPolicy.LINK_QUIET_MS)
        )
    }

    @Test
    fun `a phone that is silent on video but present on the link still gets the cycle`() {
        // This gate used to read the age of the last *video* bytes against a 1.5s window. Android
        // Auto sends no video at all while nothing on screen animates, so on a paused full-screen
        // player it shut within seconds and stayed shut - and took the only escalation with it. A
        // reporter's capture then has the cycle repairing that exact stream: paused upstream for
        // minutes, keyframe 0.68s after the release. Video silence is not evidence of anything.
        assertEquals(Action.CYCLE_FOCUS, warmRelaunch(msSinceLinkActivity = 2_000))
    }

    @Test
    fun `a session with no link activity at all is never escalated`() {
        // The caller reports "nothing has ever arrived" as Long.MAX_VALUE, and that must read as
        // gone rather than as zero.
        assertEquals(Action.NONE, warmRelaunch(msSinceLinkActivity = Long.MAX_VALUE))
    }

    @Test
    fun `a fresh surface is given the healthy path's own worst case first`() {
        // A surface younger than the escalation window may simply be about to render.
        assertEquals(
            Action.NONE,
            warmRelaunch(msSinceSurfaceSet = WarmRelaunchKeyframePolicy.ESCALATE_AFTER_SURFACE_MS - 1)
        )
        assertEquals(
            Action.CYCLE_FOCUS,
            warmRelaunch(msSinceSurfaceSet = WarmRelaunchKeyframePolicy.ESCALATE_AFTER_SURFACE_MS)
        )
    }

    @Test
    fun `the escalation window stays clear of the slowest unaided first frame`() {
        // The test above is relative to the constant, so it stays green whatever the constant
        // becomes. This one pins the constant itself.
        //
        // Firing early is not a harmless false positive: it releases video focus across a stream
        // that was about to render, which is the one thing this policy is built not to do. The
        // window therefore has to sit clear of the slowest a surface has ever been measured taking
        // to render on its own - 404ms, a SurfaceView return whose codec creation took 308ms and
        // whose first frame followed 96ms later. Twice that is the rule the window is set by, and
        // it is the reason the constant is 850 rather than a rounder 800, which would sit under it.
        // If a future measurement raises the ceiling, raise the window with it rather than relaxing
        // this assertion.
        assertTrue(
            "escalation window ${WarmRelaunchKeyframePolicy.ESCALATE_AFTER_SURFACE_MS}ms leaves too " +
                "little margin over the ${WarmRelaunchKeyframePolicy.HEALTHY_FIRST_FRAME_CEILING_MS}ms " +
                "slowest unaided first frame",
            WarmRelaunchKeyframePolicy.ESCALATE_AFTER_SURFACE_MS >=
                WarmRelaunchKeyframePolicy.HEALTHY_FIRST_FRAME_CEILING_MS * 2
        )
    }
}
