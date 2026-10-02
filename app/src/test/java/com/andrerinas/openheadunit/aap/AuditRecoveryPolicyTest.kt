package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class AuditRecoveryPolicyTest {
    @Test fun `a one byte plaintext mismatch discards the completed video unit`() {
        for (observed in listOf(99L, 101L)) {
            val result = FragmentedMessageAudit.Result(Channel.ID_VID,
                FragmentedMessageAudit.Outcome.DELTA_CHANGED, 100, observed, 2)
            assertTrue(AuditRecoveryPolicy.shouldDiscardAssembledUnit(result))
            assertTrue(AuditRecoveryPolicy.shouldRequestKeyframe(result.outcome, result.channel))
        }
    }

    @Test fun `a truncated previous run does not discard the new valid frame`() {
        val result = FragmentedMessageAudit.Result(Channel.ID_VID,
            FragmentedMessageAudit.Outcome.TRUNCATED_RUN, 100, 50, 1)
        assertTrue(AuditRecoveryPolicy.shouldRequestKeyframe(result.outcome, result.channel))
        assertFalse(AuditRecoveryPolicy.shouldDiscardAssembledUnit(result))
        assertFalse(AuditRecoveryPolicy.shouldRequestKeyframe(result.outcome, Channel.ID_AUD))
    }
}
