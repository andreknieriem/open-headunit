package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * Which [FragmentedMessageAudit] findings are worth a keyframe request rather than only a log line.
 *
 * The audit was written as an instrument and wired like one: it computes its outcome, `AapRead`
 * prints it, and nothing else happens. That left the app able to *see* the one corruption mode
 * nothing else can see, and unable to *do* anything about it. A hardware round measured the cost -
 * 37 and 59 injected middle-fragment faults, zero keyframe requests, zero escalation activity, and a
 * recovery time indistinguishable from the round before the recovery work landed.
 *
 * ### Why only DELTA_CHANGED
 *
 * A run whose fragment count cannot explain its own byte delta is missing a fragment, and a *middle*
 * fragment going missing is invisible everywhere else: [VideoFragmentAssembler] still sees a first,
 * some middles and a last in order, so it assembles the frame and hands the decoder a hole. This is
 * the only outcome with no second reporter.
 *
 * The other three are deliberately excluded:
 *
 * - [FragmentedMessageAudit.Outcome.TRUNCATED_RUN] and
 *   [FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT] are already seen downstream as
 *   [VideoFragmentAssembler.Anomaly.TRUNCATED_PREVIOUS] and
 *   [VideoFragmentAssembler.Anomaly.ORPHANED_FRAGMENT], which already ask. Asking here too would
 *   double-ask for one fault - and, since the ask is also what stamps the corruption clock
 *   [KeyframeCycleEscalationPolicy] reads, would make one lost fragment look like a wire that is
 *   still breaking.
 * - [FragmentedMessageAudit.Outcome.FIRST_OBSERVATION] is the baseline the channel's convention is
 *   learned from. It is not a fault and fires once per channel per session.
 *
 * ### Why only video
 *
 * The audit runs per channel, and a holed run on `MUSIC_PLAYBACK` is real but a video keyframe
 * cannot repair it. There is no equivalent ask for audio in the protocol.
 *
 * Pure: no clock, no logging. Throttling the ask stays with [VideoRecoveryPolicy], which every other
 * keyframe request in the app is already held to.
 */
object AuditRecoveryPolicy {

    /**
     * @param outcome what [FragmentedMessageAudit.onMessage] returned.
     * @param channel the AAP channel the run was on.
     */
    fun shouldRequestKeyframe(outcome: FragmentedMessageAudit.Outcome, channel: Int): Boolean =
        outcome == FragmentedMessageAudit.Outcome.DELTA_CHANGED && channel == Channel.ID_VID

    /**
     * Bytes a run must be short by before the assembled unit is thrown away rather than only
     * reported.
     *
     * The audit's history is false positives, not false negatives, and a discarded keyframe costs
     * a whole GOP. A genuinely missing fragment shifts the delta by that fragment's own length -
     * hundreds of bytes at the very smallest - where the framing convention itself moves by tens
     * of bytes per fragment. A floor an order of magnitude above the convention separates "a
     * fragment is gone" from "the convention shifted", and errs toward decoding.
     */
    const val MISSING_FRAGMENT_FLOOR_BYTES = 256

    /**
     * Whether the access unit this run assembled should be discarded instead of decoded.
     *
     * [shouldRequestKeyframe] already decided the finding is worth a repair; this decides the
     * stronger claim that the unit in hand is damaged enough that decoding it smears the picture.
     * Until now the holed unit was the one damaged access unit still fed - every anomaly the
     * reassembler can see already discards - and feeding it has been measured wedging a decoder
     * through its whole restart budget. With render-side concealment covering the gap a discard
     * leaves, discarding is now strictly the better trade.
     *
     * The delta is `declaredTotal - observed`, so a missing fragment *raises* it above the
     * channel's expectation. A run that came in longer than expected is never a hole and is never
     * discarded, and a channel that has not settled its convention ([expectedDelta] null) never
     * discards either - both directions of doubt decode.
     */
    fun shouldDiscardAssembledUnit(result: FragmentedMessageAudit.Result): Boolean {
        if (!shouldRequestKeyframe(result.outcome, result.channel)) return false
        val expected = result.expectedDelta ?: return false
        return result.delta - expected >= MISSING_FRAGMENT_FLOOR_BYTES
    }
}
