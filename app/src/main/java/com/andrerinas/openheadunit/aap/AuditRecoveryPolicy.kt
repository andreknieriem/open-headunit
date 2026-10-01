package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/** Plaintext length is exact: even a one-byte mismatch invalidates the completed video unit. */
object AuditRecoveryPolicy {
    fun shouldRequestKeyframe(outcome: FragmentedMessageAudit.Outcome, channel: Int): Boolean =
        channel == Channel.ID_VID && when (outcome) {
            FragmentedMessageAudit.Outcome.DELTA_CHANGED,
            FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT,
            FragmentedMessageAudit.Outcome.TRUNCATED_RUN -> true
        }

    fun shouldDiscardAssembledUnit(result: FragmentedMessageAudit.Result): Boolean =
        result.channel == Channel.ID_VID && result.outcome == FragmentedMessageAudit.Outcome.DELTA_CHANGED
}
