package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * Turns framing findings into video recovery, rather than only describing corruption in a log.
 * A keyframe can repair video reference state; it cannot repair an audio or metadata message,
 * so findings on those channels must not trigger a video request.
 *
 * A historical hardware round injected 37 and 59 middle-fragment faults without a keyframe
 * request or escalation: the decoder's assembler cannot detect a hole between FIRST and LAST.
 * That is why the reader must report damage before dispatching the final fragment. In a separate
 * downstream-injection round, feeding holed units exhausted four decoder restarts in 33 seconds.
 * These observations explain the recovery path; they are not measurements of this implementation.
 *
 * The encrypted-length audit requested recovery only for DELTA_CHANGED because the video assembler
 * reported orphaned and truncated runs. The common reassembler can now consume those fragments,
 * so the reader reports all three. Transport/video recovery still owns request throttling and
 * escalation; log suppression must never suppress repair. Overlapping reports must not be treated
 * as independent physical faults when assessing recovery from a capture.
 *
 * The former 256-byte floor deliberately tolerated uncertainty in encrypted-overhead accounting,
 * and decoded excess-length or not-yet-established runs rather than risk discarding a keyframe.
 * With the declared-plaintext contract, either a short or overlong completed unit fails the length
 * check, including the first observed run. No learned baseline is needed. If a sender uses another
 * convention, investigate the captured declared/unwrap lengths before diagnosing payload loss.
 *
 * For recovery measurements, use bounded reader-stage middle-fragment injection, still unwrap
 * every TLS record, and observe the interval after the injection budget is spent. Assembler-stage
 * injection occurs after this audit and cannot measure its detection. Compare findings, keyframe
 * requests, escalation and actual rendering, not only the number of printed audit lines.
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
