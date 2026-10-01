package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * Turns framing findings into video recovery, rather than only describing corruption in a log.
 * A keyframe can repair video reference state; it cannot repair an audio or metadata message,
 * so findings on those channels must not trigger a video request.
 *
 * Orphaned and truncated runs can now be consumed by the common reassembler before the video
 * assembler sees them. Request recovery here for all three outcomes; the old DELTA_CHANGED-only
 * rule depended on downstream reporting that is no longer guaranteed. Request throttling still
 * belongs to the transport/video recovery path, independently of diagnostic print throttling.
 *
 * The audit counts plaintext exactly. Its former encrypted-overhead baseline and 256-byte
 * discard threshold are no longer appropriate: a short or overlong completed unit is damaged
 * even if the difference is one byte. Feeding it can smear the picture or wedge the decoder.
 */
object AuditRecoveryPolicy {
    fun shouldRequestKeyframe(outcome: FragmentedMessageAudit.Outcome, channel: Int): Boolean =
        channel == Channel.ID_VID && when (outcome) {
            FragmentedMessageAudit.Outcome.DELTA_CHANGED,
            FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT,
            FragmentedMessageAudit.Outcome.TRUNCATED_RUN -> true
        }

    // DELTA_CHANGED describes the unit about to complete. An orphan has no live unit, and a
    // truncated-run finding refers to the previous unit, not the replacement FIRST being read.
    fun shouldDiscardAssembledUnit(result: FragmentedMessageAudit.Result): Boolean =
        result.channel == Channel.ID_VID && result.outcome == FragmentedMessageAudit.Outcome.DELTA_CHANGED
}
