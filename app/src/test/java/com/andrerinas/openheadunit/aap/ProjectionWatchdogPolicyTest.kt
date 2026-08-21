package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.CommManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full state table is spelled out because the watchdog's original inline check accepted only
 * HandshakeComplete — a state the session passes through once, briefly — and returned without
 * re-posting, so it died on the first tick of every session. TransportStarted being live is the
 * regression this table pins.
 *
 * The overlay and recovery tables pin the other half of that history. Reviving the watchdog made its
 * frame-only criterion reachable for the first time, and it promptly called an idle Android Auto
 * screen a lost connection (issue #852). The two decisions were split rather than retuned, so the
 * cases that matter are the asymmetric ones: a stopped picture on a live link must produce recovery
 * and no overlay, and neither signal alone is enough for the overlay.
 */
class ProjectionWatchdogPolicyTest {

    @Test
    fun `the session is live through the handshake window and the whole projection`() {
        assertTrue(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.HandshakeComplete))
        assertTrue(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.TransportStarted))
    }

    @Test
    fun `every state outside a live session stops the watchdog`() {
        assertFalse(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.Disconnected()))
        assertFalse(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.Connecting))
        assertFalse(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.Connected))
        assertFalse(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.StartingTransport))
        assertFalse(ProjectionWatchdogPolicy.isSessionLive(CommManager.ConnectionState.Error("boom")))
    }

    @Test
    fun `no request while the picture is still arriving`() {
        // The gate is the frame gap, not the overlay: a picture that is still coming needs nothing,
        // and a focus request would only disturb a healthy stream.
        assertFalse(ProjectionWatchdogPolicy.shouldRequestVideoFocus(false, nowMs = 100_000, lastRequestMs = 0))
    }

    @Test
    fun `recovery still fires on a stopped picture with a live link`() {
        // The #852 regression guard, in the other direction. The overlay now needs the link to have
        // gone quiet too, and tying recovery to the overlay after that split would leave a genuinely
        // stalled stream with nothing asking for video back - which is what the watchdog exists for.
        val nowMs = VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS + 1
        assertTrue(ProjectionWatchdogPolicy.shouldRequestVideoFocus(true, nowMs = nowMs, lastRequestMs = 0))
        assertFalse(
            ProjectionWatchdogPolicy.shouldShowReconnecting(
                frameGapMs = ProjectionWatchdogPolicy.FRAME_GAP_MS + 1,
                linkQuietMs = 0
            )
        )
    }

    @Test
    fun `a picture that stops while the link keeps talking is not a lost connection`() {
        // Issue #852: Android Auto sends no video at all while nothing on screen animates, so a
        // paused full-screen music player produces an arbitrarily long frame gap on a healthy link.
        assertFalse(
            ProjectionWatchdogPolicy.shouldShowReconnecting(
                frameGapMs = 10L * 60 * 1000,
                linkQuietMs = ProjectionWatchdogPolicy.LINK_QUIET_MS
            )
        )
    }

    @Test
    fun `a stopped picture on a silent link is a lost connection`() {
        assertTrue(
            ProjectionWatchdogPolicy.shouldShowReconnecting(
                frameGapMs = ProjectionWatchdogPolicy.FRAME_GAP_MS + 1,
                linkQuietMs = ProjectionWatchdogPolicy.LINK_QUIET_MS + 1
            )
        )
    }

    @Test
    fun `a silent link alone is not enough while frames are arriving`() {
        assertFalse(
            ProjectionWatchdogPolicy.shouldShowReconnecting(
                frameGapMs = 0,
                linkQuietMs = ProjectionWatchdogPolicy.LINK_QUIET_MS + 1
            )
        )
    }

    @Test
    fun `both thresholds are exclusive at the boundary`() {
        assertFalse(
            ProjectionWatchdogPolicy.shouldShowReconnecting(
                frameGapMs = ProjectionWatchdogPolicy.FRAME_GAP_MS,
                linkQuietMs = ProjectionWatchdogPolicy.LINK_QUIET_MS
            )
        )
    }

    @Test
    fun `never having heard from the phone reads as silent, and still needs a frame gap`() {
        // AapProjectionActivity maps "no message yet" to Long.MAX_VALUE rather than letting
        // `now - 0` stand in for it, which would have read as a *silent* link on a session that had
        // merely not started. Pinned here because the activity's own frame-gap guard makes the value
        // unreachable in practice - a frame can only have rendered if a message arrived first - so
        // nothing else would catch it if that guard were ever relaxed.
        assertTrue(
            ProjectionWatchdogPolicy.shouldShowReconnecting(
                frameGapMs = ProjectionWatchdogPolicy.FRAME_GAP_MS + 1,
                linkQuietMs = Long.MAX_VALUE
            )
        )
        assertFalse(
            ProjectionWatchdogPolicy.shouldShowReconnecting(frameGapMs = 0, linkQuietMs = Long.MAX_VALUE)
        )
    }

    @Test
    fun `requests are paced by the keyframe throttle`() {
        val throttle = VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS
        // Inside the throttle window: suppressed, boundary included (canRequestKeyframe is strict).
        assertFalse(ProjectionWatchdogPolicy.shouldRequestVideoFocus(true, nowMs = throttle, lastRequestMs = 0))
        // Past it: allowed.
        assertTrue(ProjectionWatchdogPolicy.shouldRequestVideoFocus(true, nowMs = throttle + 1, lastRequestMs = 0))
    }


    // --- The first-frame nudge loop ---------------------------------------------------------

    private fun nudge(
        sessionLive: Boolean = true,
        surfaceSet: Boolean = true,
        renderedSinceSurfaceSet: Boolean = false,
        warmRelaunchCycleSpent: Boolean = false,
    ) = ProjectionWatchdogPolicy.shouldNudgeForFirstFrame(
        sessionLive, surfaceSet, renderedSinceSurfaceSet, warmRelaunchCycleSpent
    )

    @Test
    fun `a surface that has never shown a picture is nudged for`() {
        assertTrue(nudge())
    }

    @Test
    fun `a picture on the current surface ends the loop`() {
        assertFalse(nudge(renderedSinceSurfaceSet = true))
    }

    @Test
    fun `nothing is asked for before there is a surface to ask about`() {
        assertFalse(nudge(surfaceSet = false))
    }

    @Test
    fun `a dead session is not nudged`() {
        assertFalse(nudge(sessionLive = false))
    }

    @Test
    fun `the escalation taking over ends the loop`() {
        // Without a bound this would nudge every window forever on a screen that simply has nothing
        // to draw. The overlay used to supply that bound incidentally, being up only before a
        // session's first frame; once the cycle is spent, WarmRelaunchKeyframePolicy's own throttled
        // nudge is what continues, and two unthrottled askers on one clock is what this avoids.
        assertFalse(nudge(warmRelaunchCycleSpent = true))
    }

    @Test
    fun `the loop is decided by the picture, never by what is drawn over it`() {
        // The whole defect this rule replaces. The nudge loop was gated on the loading overlay being
        // visible, and onCreate hides that overlay whenever the *previous* activity instance had
        // rendered - a stamp the surface handoff zeroes moments later. The runnable does not re-post
        // when it declines, so one relaunch in two lost the loop on its first tick and sat black
        // with nothing asking for video. Measured on a reporter's capture: four relaunches,
        // alternating, two of them silent for 11.8s and 23s.
        //
        // There is deliberately no overlay parameter to pass. If one is ever added, this fails to
        // compile, which is the point.
        assertTrue(
            "the rule must depend only on session, surface, picture and the escalation's claim",
            nudge(sessionLive = true, surfaceSet = true, renderedSinceSurfaceSet = false)
        )
    }
}
