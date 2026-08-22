package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy
import com.andrerinas.openheadunit.aap.ProjectionWatchdogPolicy
import com.andrerinas.openheadunit.decoder.CorruptionConcealmentPolicy.CONCEAL_MAX_MS
import com.andrerinas.openheadunit.decoder.CorruptionConcealmentPolicy.ESCALATED_REPAIR_OBSERVED_MS
import com.andrerinas.openheadunit.decoder.CorruptionConcealmentPolicy.Outcome
import com.andrerinas.openheadunit.decoder.CorruptionConcealmentPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two render-gating mechanisms were removed from this app for latching, so every guarantee here is
 * a test rather than a promise: the window has a hard cap, the cap is measured from the first
 * report, an expired window disarms rather than reopening, broken bookkeeping fails open, and the
 * cap stands in a provable relation to the escalation that is supposed to beat it and to the
 * display watchdog that must never see it.
 */
class CorruptionConcealmentPolicyTest {

    private fun next(
        nowMs: Long,
        state: State = State.ARMED,
        windowOpenedMs: Long = 0L,
        corruptionReported: Boolean = false,
        keyframeRepaired: Boolean = false,
        sessionHasRendered: Boolean = true,
    ) = CorruptionConcealmentPolicy.next(
        nowMs, state, windowOpenedMs, corruptionReported, keyframeRepaired, sessionHasRendered
    )

    // --- The window -------------------------------------------------------------------------

    @Test
    fun `a healthy stream renders every frame`() {
        assertEquals(Outcome.SHOW, next(nowMs = 1_000_000L))
    }

    @Test
    fun `a corruption report holds the picture`() {
        assertEquals(Outcome.OPEN, next(nowMs = 1_000_000L, corruptionReported = true))
    }

    @Test
    fun `nothing is concealed before the session has rendered a frame`() {
        // Before the first frame there is nothing on the surface worth holding, and the warm-start
        // window belongs to WarmRelaunchKeyframePolicy. This is the same gate the dropped-frame
        // and corruption escalations sit behind, and the boundary the #755 feed latch violated.
        assertEquals(
            Outcome.SHOW,
            next(nowMs = 1_000_000L, corruptionReported = true, sessionHasRendered = false)
        )
    }

    @Test
    fun `the frame that repairs the picture is the frame that is shown`() {
        val outcome = next(
            nowMs = 1_001_000L,
            state = State.CONCEALING,
            windowOpenedMs = 1_000_000L,
            keyframeRepaired = true,
        )
        assertEquals(Outcome.CLOSE_REPAIRED, outcome)
        assertTrue(outcome.renders)
    }

    @Test
    fun `the window ends itself at the cap and not before`() {
        val opened = 1_000_000L
        assertEquals(
            Outcome.CONCEAL,
            next(nowMs = opened + CONCEAL_MAX_MS - 1, state = State.CONCEALING, windowOpenedMs = opened)
        )
        assertEquals(
            Outcome.CLOSE_EXPIRED,
            next(nowMs = opened + CONCEAL_MAX_MS, state = State.CONCEALING, windowOpenedMs = opened)
        )
    }

    @Test
    fun `more corruption during a window never extends it`() {
        // The cap is measured from the first report of the window. A stream losing frames
        // continuously would otherwise hold the freeze forever, which is the latch this design
        // exists to rule out.
        val opened = 1_000_000L
        assertEquals(
            Outcome.CONCEAL,
            next(
                nowMs = opened + 1_000L,
                state = State.CONCEALING,
                windowOpenedMs = opened,
                corruptionReported = true,
            )
        )
        assertEquals(
            Outcome.CLOSE_EXPIRED,
            next(
                nowMs = opened + CONCEAL_MAX_MS,
                state = State.CONCEALING,
                windowOpenedMs = opened,
                corruptionReported = true,
            )
        )
    }

    // --- The disarm -------------------------------------------------------------------------

    @Test
    fun `a window that ran out does not open another one`() {
        // Sustained loss buys exactly one freeze and then the honest smear. Re-opening on the next
        // report would alternate freeze and smear at the cap's period - a strobing picture, worse
        // than either steady state.
        assertEquals(
            Outcome.SHOW,
            next(nowMs = 1_000_000L, state = State.DISARMED, corruptionReported = true)
        )
    }

    @Test
    fun `a keyframe re-arms the picture after a window ran out`() {
        assertEquals(
            Outcome.REARM,
            next(nowMs = 1_000_000L, state = State.DISARMED, keyframeRepaired = true)
        )
    }

    // --- Repair outranks everything ----------------------------------------------------------

    @Test
    fun `a repair outranks the cap`() {
        val opened = 1_000_000L
        assertEquals(
            Outcome.CLOSE_REPAIRED,
            next(
                nowMs = opened + CONCEAL_MAX_MS + 10_000L,
                state = State.CONCEALING,
                windowOpenedMs = opened,
                keyframeRepaired = true,
            )
        )
    }

    @Test
    fun `a repair outranks a fresh report`() {
        // The buffer in hand when the repair confirms is the repaired picture; a report arriving
        // on the same step describes damage that keyframe has already replaced.
        assertEquals(
            Outcome.REARM,
            next(nowMs = 1_000_000L, corruptionReported = true, keyframeRepaired = true)
        )
    }

    // --- The no-latch guarantees -------------------------------------------------------------

    @Test
    fun `a lost window stamp resumes rather than freezing`() {
        // The structural answer to the removed latches: if the caller's bookkeeping ever breaks
        // and a window has no opening stamp, the window reads as already expired and the picture
        // comes back. The failure mode of a bug here is a smear, never a freeze.
        assertEquals(
            Outcome.CLOSE_EXPIRED,
            next(nowMs = 1_000_000L, state = State.CONCEALING, windowOpenedMs = 0L)
        )
    }

    @Test
    fun `every state returns to a rendered frame within the cap`() {
        // Property, not example: from any state, with no repair ever arriving and the clock
        // advancing, something must render inside CONCEAL_MAX_MS plus one step. A state this
        // cannot be shown for is an absorbing state, which is the one thing this policy must not
        // have.
        for (start in State.values()) {
            var state = start
            var windowOpened = if (start == State.CONCEALING) 1_000_000L else 0L
            var now = 1_000_000L
            var renderedAt = -1L
            while (now <= 1_000_000L + CONCEAL_MAX_MS + 10L) {
                val outcome = next(nowMs = now, state = state, windowOpenedMs = windowOpened)
                if (outcome.renders) {
                    renderedAt = now
                    break
                }
                when (outcome) {
                    Outcome.OPEN -> { state = State.CONCEALING; windowOpened = now }
                    Outcome.CLOSE_REPAIRED, Outcome.REARM -> state = State.ARMED
                    Outcome.CLOSE_EXPIRED -> state = State.DISARMED
                    else -> {}
                }
                now += 10L
            }
            assertTrue("state $start never rendered within the cap", renderedAt >= 0L)
        }
    }

    // --- The cap in relation to everything it races -------------------------------------------

    @Test
    fun `the cap outlasts the slowest escalated repair ever measured`() {
        // If this fails the freeze ends in a smear in exactly the case it was built for: a
        // successful escalation. And the escalation clock must itself sit inside the measured
        // repair, or the measurement no longer describes this code.
        assertTrue(
            "cap ${CONCEAL_MAX_MS}ms does not outlast the ${ESCALATED_REPAIR_OBSERVED_MS}ms repair",
            CONCEAL_MAX_MS > ESCALATED_REPAIR_OBSERVED_MS
        )
        assertTrue(
            ESCALATED_REPAIR_OBSERVED_MS > KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS
        )
    }

    @Test
    fun `the freeze always ends before the projection view is torn down`() {
        // The display-stall watchdog answers a long-undrawn surface with recreateProjectionView():
        // a black flash, an EGL disconnect and touch loss. It measures from the last draw, so the
        // margin also absorbs the age the last drawn frame had when the window opened - a frame
        // interval or two, generously covered by a full second.
        assertTrue(
            "cap ${CONCEAL_MAX_MS}ms leaves less than 1000ms of margin under the " +
                "${ProjectionWatchdogPolicy.DISPLAY_FREEZE_MS}ms display-freeze teardown",
            ProjectionWatchdogPolicy.DISPLAY_FREEZE_MS - CONCEAL_MAX_MS >= 1_000L
        )
    }

    @Test
    fun `the freeze never reaches the projection watchdog's frame gap`() {
        // FRAME_GAP_MS is where a stopped picture starts being treated as a possible lost
        // connection. A concealment window must never be able to raise that machinery.
        assertTrue(CONCEAL_MAX_MS < ProjectionWatchdogPolicy.FRAME_GAP_MS)
    }

    @Test
    fun `the freeze outlasts the decoder's own stall threshold, so the stall clock must not be the render clock`() {
        // SYNC_STALL_THRESHOLD_MS is 2000: a maximum-length window crosses it. This is why
        // lastOutputMs stamps on every dequeued buffer rather than on renders - if that split is
        // ever undone, a freeze classifies as STALLED and is answered with a codec rebuild that
        // resumes on a P-frame, recreating the wedge the concealment exists to hide. The literal
        // is pinned here because the threshold is private to VideoDecoder; if it gets a public
        // name, compare against it directly.
        assertTrue(CONCEAL_MAX_MS > 2_000L)
    }
}
