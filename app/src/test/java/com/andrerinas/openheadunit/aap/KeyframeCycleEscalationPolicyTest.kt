package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.Action
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.CORRUPTION_QUIET_MS
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.CYCLE_COOLDOWN_MS
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.CYCLE_REFUND_AFTER_QUIET_MS
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_DRIVE
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.DEFER_FOR_QUIET_LIMIT_MS
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS
import com.andrerinas.openheadunit.aap.WarmRelaunchKeyframePolicy.ESCALATE_AFTER_SURFACE_MS
import com.andrerinas.openheadunit.aap.WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_SESSION
import com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy.NATURAL_CADENCE_MIN_OBSERVED_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A focus release across a stream that is rendering is the precondition of issue #755, so escalation
 * past the gain-only nudge has to be earned: the picture has to have stayed broken past the point
 * where the phone would plausibly have repaired it, and the cycles are a decaying budget rather than
 * something a bad minute can spend all at once.
 */
class KeyframeCycleEscalationPolicyTest {

    private fun decide(
        nowMs: Long,
        unrepairedSinceMs: Long,
        cyclesUsedThisSession: Int = 0,
        lastCycleMs: Long = 0L,
        lastWireCorruptionMs: Long = 0L,
    ) = KeyframeCycleEscalationPolicy.decide(
        nowMs, unrepairedSinceMs, cyclesUsedThisSession, lastCycleMs, lastWireCorruptionMs
    )

    // --- The trigger ------------------------------------------------------------------------

    @Test
    fun `a repaired picture never escalates`() {
        // The clock is only set while something is broken; zero means there is nothing to repair,
        // however long the session has been running or however many cycles are left.
        assertEquals(Action.NUDGE, decide(nowMs = 1_000_000L, unrepairedSinceMs = 0L))
    }

    @Test
    fun `the drop that starts the clock only gets the nudge`() {
        assertEquals(Action.NUDGE, decide(nowMs = 12_345L, unrepairedSinceMs = 12_345L))
    }

    @Test
    fun `the cycle is earned the moment the window elapses, not before`() {
        val broken = 10_000L
        assertEquals(
            Action.NUDGE,
            decide(nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS - 1, unrepairedSinceMs = broken)
        )
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS, unrepairedSinceMs = broken)
        )
    }

    // --- The budget -------------------------------------------------------------------------

    @Test
    fun `a spent budget degrades to the nudge`() {
        val broken = 10_000L
        assertEquals(
            Action.NUDGE,
            decide(
                nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION,
            )
        )
    }

    @Test
    fun `every cycle up to the cap is available`() {
        val broken = 1_000_000L
        for (spent in 0 until MAX_CYCLES_PER_SESSION) {
            assertEquals(
                "cycle ${spent + 1} of $MAX_CYCLES_PER_SESSION should still be available",
                Action.CYCLE_FOCUS,
                decide(
                    nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                    unrepairedSinceMs = broken,
                    cyclesUsedThisSession = spent,
                    // Far enough back that the cooldown is not what is being tested here.
                    lastCycleMs = broken - CYCLE_COOLDOWN_MS,
                )
            )
        }
    }

    // --- The decay --------------------------------------------------------------------------

    @Test
    fun `a second cycle waits out the cooldown even with budget left`() {
        val firstCycle = 100_000L
        val broken = firstCycle + 5_000L
        assertEquals(
            Action.NUDGE,
            decide(
                nowMs = firstCycle + CYCLE_COOLDOWN_MS - 1,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 1,
                lastCycleMs = firstCycle,
            )
        )
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = firstCycle + CYCLE_COOLDOWN_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 1,
                lastCycleMs = firstCycle,
            )
        )
    }

    @Test
    fun `the first cycle of a session is not held back by an unset cooldown stamp`() {
        // lastCycleMs is 0 before anything has fired, and must not read as "a cycle at time zero" -
        // that would suppress the first escalation of every session for the first minute of uptime.
        val broken = 10_000L
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 0,
                lastCycleMs = 0L,
            )
        )
    }

    // --- Constants held against what was measured -------------------------------------------

    @Test
    fun `the escalation window clears two throttle windows`() {
        // Escalating before a nudge has demonstrably gone unanswered would spend a focus release on
        // a request the phone had not had a chance to answer yet.
        assertTrue(
            "escalation window ${ESCALATE_AFTER_UNREPAIRED_MS}ms does not clear two " +
                "${VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS}ms throttle windows",
            ESCALATE_AFTER_UNREPAIRED_MS >= VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS * 2
        )
    }

    @Test
    fun `the escalation window stays well under the fastest unaided repair ever measured`() {
        // Past a third of the fastest natural keyframe gap on record, the phone's own cadence would
        // often beat the escalation and the disturbance buys nothing.
        assertTrue(
            "escalation window ${ESCALATE_AFTER_UNREPAIRED_MS}ms is not clearly under a third of the " +
                "${NATURAL_CADENCE_MIN_OBSERVED_MS}ms fastest natural keyframe gap",
            ESCALATE_AFTER_UNREPAIRED_MS <= NATURAL_CADENCE_MIN_OBSERVED_MS / 3
        )
    }

    @Test
    fun `the cooldown keeps two focus cycles from ever being in flight together`() {
        // AapTransport carries one shared regain runnable and replaces it rather than tracking one
        // per cycle. That is only sound while a second release cannot land inside the first's regain
        // gap. Shortening the cooldown towards the gap needs that bookkeeping first.
        assertTrue(
            "cooldown ${CYCLE_COOLDOWN_MS}ms is not comfortably clear of the ${FOCUS_CYCLE_GAP_MS}ms regain gap",
            CYCLE_COOLDOWN_MS >= FOCUS_CYCLE_GAP_MS * 10
        )
    }

    @Test
    fun `the surface escalation always gets to the lever first`() {
        // Both policies can want a cycle at once - a decoder rebuilt under a surface that has never
        // shown a frame satisfies each of them - and only one release may go out. The lever refuses
        // the second claim, so nothing breaks either way, but the ordering decides which policy's
        // budget pays: the surface path is the one with the measured 3.0-3.2s recovery behind it and
        // should win, so it must stay the shorter window.
        assertTrue(
            "surface escalation ${ESCALATE_AFTER_SURFACE_MS}ms no longer precedes the unrepaired " +
                "escalation ${ESCALATE_AFTER_UNREPAIRED_MS}ms",
            ESCALATE_AFTER_SURFACE_MS < ESCALATE_AFTER_UNREPAIRED_MS
        )
    }

    @Test
    fun `a refused claim is re-checked only after the winning cycle has had its chance`() {
        // When the lever is already held, AapTransport re-arms its check at this window instead of
        // returning and leaving the clock latched. That is only correct while the window outlasts the
        // whole of the other cycle: FOCUS_CYCLE_GAP_MS to send the regain, plus the phone's own
        // turnaround - measured at 544ms and 557ms from the release line to the keyframe reaching the
        // codec, on the rig, on the two cycles a corrupt-access-unit run produced. 400 + 557 is
        // comfortably inside 2000; shortening either constant has to argue with those numbers.
        assertTrue(
            "re-check window ${ESCALATE_AFTER_UNREPAIRED_MS}ms no longer outlasts the regain gap " +
                "${FOCUS_CYCLE_GAP_MS}ms plus the measured 557ms keyframe turnaround",
            ESCALATE_AFTER_UNREPAIRED_MS > FOCUS_CYCLE_GAP_MS + 557
        )
    }

    // --- The wire's own quiet ---------------------------------------------------------------

    @Test
    fun `an isolated dropped frame still cycles at exactly the same instant it always has`() {
        // The fault this escalation was written for is one lost frame on an otherwise clean wire.
        // There the corruption stamp and the unrepaired stamp are the same moment, so nothing is
        // deferred and the cycle goes out on the first check. This is the guard on that: if it ever
        // fails, the deferral has started taxing the common case to pay for the sustained-loss one.
        //
        // Both orderings, because which stamp lands first is not fixed: AapVideo reports the corrupt
        // access unit and the decoder sheds the reference frame, and the gap between them is however
        // long the decoder takes to fail on it. The quiet window outlasts the escalation window now,
        // so an ordering-sensitive gate here would defer this case for thirteen seconds.
        val broken = 10_000L
        for (corruptionAt in listOf(broken - 250L, broken, broken + 1L)) {
            assertEquals(
                "isolated drop deferred with the corruption stamped at ${corruptionAt - broken}ms",
                Action.CYCLE_FOCUS,
                decide(
                    nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                    unrepairedSinceMs = broken,
                    lastWireCorruptionMs = corruptionAt,
                )
            )
        }
    }

    @Test
    fun `the quiet window outlasts a fault interval without outlasting the ceiling`() {
        // Sized against how far apart faults land, not against the report throttle. The first
        // version was two seconds - twice the throttle - and asked its question two seconds after
        // each arming, so on a wire losing a frame every ~12s it could only ever catch corruption
        // by coincidence: one hold in six checks, and the budget gone 207s before the loss stopped.
        //
        // The ceiling is the other side. A quiet window at or past it would mean a wire that never
        // settles never reaches the lever, which is the failure DEFER_FOR_QUIET_LIMIT_MS exists to
        // prevent.
        assertTrue(
            "quiet window ${CORRUPTION_QUIET_MS}ms does not outlast the escalation window " +
                "${ESCALATE_AFTER_UNREPAIRED_MS}ms it is checked on",
            CORRUPTION_QUIET_MS > ESCALATE_AFTER_UNREPAIRED_MS
        )
        assertTrue(
            "quiet window ${CORRUPTION_QUIET_MS}ms reaches the ${DEFER_FOR_QUIET_LIMIT_MS}ms " +
                "ceiling, so a wire that never settles would never reach the lever",
            CORRUPTION_QUIET_MS < DEFER_FOR_QUIET_LIMIT_MS
        )
    }

    @Test
    fun `the quiet window clears two throttle windows`() {
        // Corrupt-frame reports are paced by VideoRecoveryPolicy, so one missing report is only
        // silence. Two in a row is the shortest gap that means the wire has actually settled.
        assertTrue(
            "quiet window ${CORRUPTION_QUIET_MS}ms does not clear two " +
                "${VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS}ms throttle windows",
            CORRUPTION_QUIET_MS >= VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS * 2
        )
    }

    @Test
    fun `a wire that is still losing frames holds the cycle instead of spending it`() {
        val broken = 10_000L
        val now = broken + 3_000L
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                lastWireCorruptionMs = now - 500L,
            )
        )
    }

    @Test
    fun `the cycle goes out on the first check after the wire settles`() {
        // The same broken picture, the same budget, one quiet window later. This is the measured
        // case: the run that stopped its injected corruption recovered 0.96s after the first cycle
        // fired on a clean wire, having wasted the previous one 60s earlier on a noisy one.
        val broken = 10_000L
        val lastCorruption = broken + 3_000L
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = lastCorruption + CORRUPTION_QUIET_MS - 1,
                unrepairedSinceMs = broken,
                lastWireCorruptionMs = lastCorruption,
            )
        )
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = lastCorruption + CORRUPTION_QUIET_MS,
                unrepairedSinceMs = broken,
                lastWireCorruptionMs = lastCorruption,
            )
        )
    }

    @Test
    fun `a wire that never settles still reaches the lever`() {
        // Light continuous loss may still be repairable by a keyframe, and a deferral with no
        // ceiling would put the only lever that exists permanently out of reach.
        val broken = 10_000L
        val now = broken + DEFER_FOR_QUIET_LIMIT_MS
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                lastWireCorruptionMs = now - 1L,
            )
        )
    }

    @Test
    fun `the deferral ceiling is never cheaper to abandon than to keep`() {
        // A cycle spent on a wire that is still breaking costs a whole cooldown: the keyframe it
        // buys arrives broken, and the next cycle - the one that would have worked - is a minute
        // away. So giving up on the wait sooner than that can lose more time than the wait itself,
        // which is the exact fault this gate exists to stop. Measured: a run whose loss stopped ten
        // seconds into an unrepaired stretch recovered in ~5s; the same run with the loss stopping
        // twenty seconds in would have burned its cycle first and waited out the full sixty.
        assertTrue(
            "deferral ceiling ${DEFER_FOR_QUIET_LIMIT_MS}ms is shorter than the " +
                "${CYCLE_COOLDOWN_MS}ms a wasted cycle costs",
            DEFER_FOR_QUIET_LIMIT_MS >= CYCLE_COOLDOWN_MS
        )
    }

    // --- The last cycle is kept back ---------------------------------------------------------

    @Test
    fun `the last cycle is held on a wire that the earlier ones would have been spent on`() {
        // The same wire, the same broken picture, the same instant - and a different answer purely
        // because of how much budget is left. Cycles 1 and 2 are worth spending speculatively on a
        // stream that may never settle; the last one is worth only what it buys at the moment the
        // loss stops, so it waits for that moment.
        val broken = 10_000L
        val now = broken + DEFER_FOR_QUIET_LIMIT_MS + 30_000L
        val stillLosing = now - 1_000L
        assertEquals(
            "an earlier cycle should still reach the lever on a wire that never settles",
            Action.CYCLE_FOCUS,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 0,
                lastWireCorruptionMs = stillLosing,
            )
        )
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - 1,
                lastWireCorruptionMs = stillLosing,
            )
        )
    }

    @Test
    fun `the deferral ceiling does not apply to the last cycle`() {
        // The ceiling trades a possibly-wasted cycle for the chance that a lightly-broken wire is
        // repairable anyway. With one cycle left that trade is the wrong way round: the measured
        // cost of taking it was a picture dead for 207 seconds with nothing left to spend when the
        // wire finally went quiet.
        val broken = 10_000L
        val now = broken + DEFER_FOR_QUIET_LIMIT_MS
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - 1,
                lastWireCorruptionMs = now - 1L,
            )
        )
    }

    @Test
    fun `the last cycle goes out on the first check after the wire settles`() {
        // What the hold is for. A cycle fired on a quiet wire has been measured repairing the
        // picture in 0.96s and in 1.5-1.6s; the whole point of keeping one back is that it is
        // available at the instant this becomes true.
        val broken = 10_000L
        val lastCorruption = broken + 120_000L
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = lastCorruption + CORRUPTION_QUIET_MS - 1,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - 1,
                lastWireCorruptionMs = lastCorruption,
            )
        )
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = lastCorruption + CORRUPTION_QUIET_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - 1,
                lastWireCorruptionMs = lastCorruption,
            )
        )
    }

    @Test
    fun `a hold that lasts for minutes stays a bounded number of log lines`() {
        // A held last cycle is re-checked every ESCALATE_AFTER_UNREPAIRED_MS, which has to stay
        // short so the cycle lands promptly once the wire settles. Unthrottled that is one line
        // every two seconds for as long as the loss runs - about a hundred across the injection run
        // that motivated the reserve, all saying the same thing. AapTransport prints it through the
        // same budget the framing audit uses, so the first ones say so in full and the rest are
        // counted into a summary.
        var printed = 0
        var lastPrintMs = 0L
        val holdMs = 5 * 60_000L
        var now = 0L
        while (now < holdMs) {
            if (AuditReportPolicy.shouldReport(printed, lastPrintMs, now)) {
                printed++
                lastPrintMs = now
            }
            now += ESCALATE_AFTER_UNREPAIRED_MS
        }
        assertTrue(
            "a five-minute hold printed $printed lines, which is not bounded by the budget",
            printed <= AuditReportPolicy.BURST_REPORTS + holdMs / AuditReportPolicy.SUMMARY_INTERVAL_MS
        )
        assertTrue(
            "a five-minute hold printed $printed lines, so the first ones are not getting through",
            printed >= AuditReportPolicy.BURST_REPORTS
        )
    }

    @Test
    fun `a spent budget and a running cooldown answer before the quiet check does`() {
        // Both are reasons to stop asking for a minute; the quiet check clears in two seconds, and
        // the caller re-arms on a correspondingly shorter clock. Returning WAIT_FOR_QUIET for either
        // of these would put that fast re-check behind a gate that cannot move.
        val broken = 100_000L
        val now = broken + 3_000L
        assertEquals(
            Action.NUDGE,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION,
                lastWireCorruptionMs = now - 1L,
            )
        )
        assertEquals(
            Action.NUDGE,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 1,
                lastCycleMs = now - 1L,
                lastWireCorruptionMs = now - 1L,
            )
        )
    }

    @Test
    fun `a wire truncation later in a drive reaches the cycle`() {
        // The shape a reporter's 2h21m session actually had, and the case this policy could not see
        // until AapVideo's corruption path became escalatable: an isolated truncated access unit
        // sixteen minutes after the session's first cycle. Both stamps land together, one cycle is
        // spent, the cooldown expired long ago. Measured on that unit, the three events that never
        // reached here stayed broken for 27.5s, 11.2s and 19.2s against the escalated one's 2.78s.
        val firstCycle = 5_000_000L
        val broken = firstCycle + 16 * 60_000L
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 1,
                lastCycleMs = firstCycle,
                lastWireCorruptionMs = broken,
            )
        )
    }

    @Test
    fun `a truncation on a wire that is still losing frames is still held`() {
        // The other half of the same wiring: making the video path escalatable must not cost the
        // deferral, because it is that path's stamp the deferral reads. A second fault landing a
        // full escalation window after the arm is sustained loss, not an isolated event, and a cycle
        // spent on it buys a keyframe that arrives broken too.
        val broken = 200_000L
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 1,
                lastWireCorruptionMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
            )
        )
    }

    @Test
    fun `an isolated truncation reaches the last cycle at the same instant as the first`() {
        // The reporter's fourth event. A 141-minute session with three cycles already spent on
        // earlier faults meets one more isolated truncation: both stamps land together, the
        // cooldown expired long ago, and one cycle remains. The first version of the reordered
        // gate checked the last-cycle hold before the isolated-fault exemption, so this exact
        // shape waited out a full quiet window - repaired at ~15.5s instead of ~2.4s, on the one
        // event of the drive where the reserve existing was the whole point.
        val lastCycle = 5_000_000L
        val broken = lastCycle + 16 * 60_000L
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - 1,
                lastCycleMs = lastCycle,
                lastWireCorruptionMs = broken,
            )
        )
    }

    @Test
    fun `the isolated-drop exemption holds at every budget position`() {
        // The budget decides how much speculation a noisy wire is worth, and must decide nothing
        // about a quiet one. Both stamp orderings, same as the isolated-drop test above, because
        // which of the two lands first depends on the decoder.
        val broken = 10_000L
        for (spent in 0 until MAX_CYCLES_PER_SESSION) {
            for (corruptionAt in listOf(broken - 250L, broken, broken + 1L)) {
                assertEquals(
                    "isolated drop deferred with $spent cycles spent and the corruption " +
                        "stamped at ${corruptionAt - broken}ms",
                    Action.CYCLE_FOCUS,
                    decide(
                        nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                        unrepairedSinceMs = broken,
                        cyclesUsedThisSession = spent,
                        lastCycleMs = if (spent == 0) 0L else broken - CYCLE_COOLDOWN_MS,
                        lastWireCorruptionMs = corruptionAt,
                    )
                )
            }
        }
    }

    @Test
    fun `sustained loss is still held at every budget position`() {
        // The exemption is for the fault that armed the clock and nothing after it. A second fault
        // a full escalation window later is sustained loss, and moving the exemption ahead of the
        // last-cycle hold must not have widened it to cover that.
        val broken = 10_000L
        for (spent in 0 until MAX_CYCLES_PER_SESSION) {
            assertEquals(
                "sustained loss reached the lever with $spent cycles spent",
                Action.WAIT_FOR_QUIET,
                decide(
                    nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                    unrepairedSinceMs = broken,
                    cyclesUsedThisSession = spent,
                    lastCycleMs = if (spent == 0) 0L else broken - CYCLE_COOLDOWN_MS,
                    lastWireCorruptionMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                )
            )
        }
    }

    @Test
    fun `the ceiling asymmetry survives the gate reorder`() {
        // At the deferral ceiling, under sustained loss: an earlier cycle is released to a wire
        // that never settles, the last one is still held for real quiet. The two halves used to be
        // separate branches; after the reorder they share one, so one test pins them side by side.
        val broken = 10_000L
        val now = broken + DEFER_FOR_QUIET_LIMIT_MS
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = 0,
                lastWireCorruptionMs = now - 1L,
            )
        )
        assertEquals(
            Action.WAIT_FOR_QUIET,
            decide(
                nowMs = now,
                unrepairedSinceMs = broken,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - 1,
                lastWireCorruptionMs = now - 1L,
            )
        )
    }

    // --- The refund -------------------------------------------------------------------------

    private fun refund(
        nowMs: Long,
        cyclesUsedThisSession: Int = MAX_CYCLES_PER_SESSION,
        cyclesSpentThisSession: Int = MAX_CYCLES_PER_SESSION,
        lastBudgetChangeMs: Long = 1_000L,
        lastWireCorruptionMs: Long = 0L,
        pictureUnrepaired: Boolean = false,
    ) = KeyframeCycleEscalationPolicy.cyclesToRefund(
        nowMs, cyclesUsedThisSession, cyclesSpentThisSession,
        lastBudgetChangeMs, lastWireCorruptionMs, pictureUnrepaired
    )

    @Test
    fun `the refund window outlasts every other clock this policy runs`() {
        // A refund on a shorter clock than the cooldown or the deferral ceiling would hand a cycle
        // back inside the very episode that spent it. And it must outlast the phone's own keyframe
        // interval - measured at ~69.4s, eight gaps, spread under 2s - so quiet always contains at
        // least one whole natural repair before anything is earned back.
        assertTrue(CYCLE_REFUND_AFTER_QUIET_MS > CORRUPTION_QUIET_MS)
        assertTrue(CYCLE_REFUND_AFTER_QUIET_MS >= CYCLE_COOLDOWN_MS)
        assertTrue(CYCLE_REFUND_AFTER_QUIET_MS >= DEFER_FOR_QUIET_LIMIT_MS)
        assertTrue(CYCLE_REFUND_AFTER_QUIET_MS > 69_400L)
    }

    @Test
    fun `a refund needs quiet on both clocks`() {
        // Quiet is measured from whichever moved last: a fault restarts the window even when the
        // budget has been still for an hour, and a fresh spend restarts it even on a clean wire.
        val now = 10_000_000L
        assertEquals(
            0,
            refund(now, lastBudgetChangeMs = 1_000L, lastWireCorruptionMs = now - 12_000L)
        )
        assertEquals(
            0,
            refund(now, lastBudgetChangeMs = now - 12_000L, lastWireCorruptionMs = 1_000L)
        )
        assertEquals(
            1,
            refund(
                now,
                cyclesUsedThisSession = 1,
                lastBudgetChangeMs = now - CYCLE_REFUND_AFTER_QUIET_MS,
                lastWireCorruptionMs = now - CYCLE_REFUND_AFTER_QUIET_MS,
            )
        )
    }

    @Test
    fun `an injection cadence can never earn a refund`() {
        // The sustained-loss round landed a fault every ~12s for 450s. Five unbroken minutes of
        // quiet cannot occur inside that, so a build carrying the refund measures identically to
        // one without it under any injection profile - which is what lets it ship into a rig round
        // sized for the other commits.
        var now = 1_000L
        repeat(40) {
            now += 12_000L
            assertEquals(0, refund(now, lastWireCorruptionMs = now))
        }
    }

    @Test
    fun `nothing comes back while the picture is unrepaired`() {
        // A held last cycle stays the last cycle: the reserve's guarantee is being there when the
        // loss stops, and a refund arriving mid-hold would dilute exactly that.
        val now = 10_000_000L
        assertEquals(
            0,
            refund(now, lastBudgetChangeMs = 1_000L, pictureUnrepaired = true)
        )
    }

    @Test
    fun `refunds accrue one per quiet window`() {
        val start = 1_000L
        assertEquals(0, refund(start + CYCLE_REFUND_AFTER_QUIET_MS - 1, lastBudgetChangeMs = start))
        assertEquals(1, refund(start + CYCLE_REFUND_AFTER_QUIET_MS, lastBudgetChangeMs = start))
        assertEquals(2, refund(start + 2 * CYCLE_REFUND_AFTER_QUIET_MS, lastBudgetChangeMs = start))
        assertEquals(
            "a long quiet stretch cannot refund more than was spent",
            MAX_CYCLES_PER_SESSION,
            refund(start + 100 * CYCLE_REFUND_AFTER_QUIET_MS, lastBudgetChangeMs = start)
        )
    }

    @Test
    fun `an untouched budget has nothing to refund`() {
        val now = 10_000_000L
        assertEquals(0, refund(now, cyclesUsedThisSession = 0, cyclesSpentThisSession = 0))
        assertEquals(0, refund(now, lastBudgetChangeMs = 0L))
    }

    @Test
    fun `the drive ceiling counts what the budget could still spend`() {
        // Capping refunds on spends alone double counts unspent headroom: at seven spends with one
        // cycle still unspent, earning even one back allows a ninth spend. The cap is on potential
        // - spends so far plus everything the refreshed budget could still spend - so that exact
        // shape earns back nothing.
        val now = 10_000_000L
        assertEquals(
            0,
            refund(now, cyclesUsedThisSession = 2, cyclesSpentThisSession = 7)
        )
        assertEquals(
            2,
            refund(now, cyclesUsedThisSession = 3, cyclesSpentThisSession = 6)
        )
    }

    @Test
    fun `no sequence of spends and refunds exceeds the drive ceiling`() {
        // Greedy adversary: spend everything available, wait an arbitrarily long quiet stretch,
        // take every refund offered, repeat. The lifetime total must converge on the ceiling
        // exactly - never past it, and not short of it either, or the ceiling is mis-set.
        var used = 0
        var spent = 0
        var lastChange = 0L
        var now = 1_000L
        repeat(50) {
            while (used < MAX_CYCLES_PER_SESSION && spent < MAX_CYCLES_PER_DRIVE + 5) {
                used++
                spent++
                now += CYCLE_COOLDOWN_MS
                lastChange = now
            }
            now += 100 * CYCLE_REFUND_AFTER_QUIET_MS
            val r = refund(
                now,
                cyclesUsedThisSession = used,
                cyclesSpentThisSession = spent,
                lastBudgetChangeMs = lastChange,
            )
            used -= r
            if (r > 0) lastChange = now
        }
        assertEquals(MAX_CYCLES_PER_DRIVE, spent)
    }

    @Test
    fun `the reporter's drive shape reaches a cycle on its fourth event`() {
        // Four isolated truncations across 141 minutes, tens of minutes apart. The first three
        // spend the whole session budget; pre-refund, the fourth met an empty one and waited the
        // phone's cadence out. Forty quiet minutes earn at least one cycle back, and the reordered
        // gate then prices the event at the usual ~2.4s.
        val thirdSpend = 80 * 60_000L
        val fourthEvent = 120 * 60_000L
        val earnedBack = refund(
            fourthEvent,
            cyclesUsedThisSession = MAX_CYCLES_PER_SESSION,
            cyclesSpentThisSession = MAX_CYCLES_PER_SESSION,
            lastBudgetChangeMs = thirdSpend,
            lastWireCorruptionMs = thirdSpend,
        )
        assertTrue("forty quiet minutes earned nothing back", earnedBack >= 1)
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = fourthEvent + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = fourthEvent,
                cyclesUsedThisSession = MAX_CYCLES_PER_SESSION - earnedBack,
                lastCycleMs = thirdSpend,
                lastWireCorruptionMs = fourthEvent,
            )
        )
    }

    @Test
    fun `a session that has never seen wire corruption never defers`() {
        // Zero means "none this session", not "one at time zero" - the same reading lastCycleMs
        // gets, and the same bug if it were read the other way.
        val broken = 10_000L
        assertEquals(
            Action.CYCLE_FOCUS,
            decide(
                nowMs = broken + ESCALATE_AFTER_UNREPAIRED_MS,
                unrepairedSinceMs = broken,
                lastWireCorruptionMs = 0L,
            )
        )
    }
}
