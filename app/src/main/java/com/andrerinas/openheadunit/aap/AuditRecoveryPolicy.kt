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
}
