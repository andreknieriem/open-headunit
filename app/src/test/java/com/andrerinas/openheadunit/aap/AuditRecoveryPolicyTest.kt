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

    @Test fun `all audit faults ask for video recovery but no other channel does`() {
        for (outcome in FragmentedMessageAudit.Outcome.entries) {
            for (channel in 0..255) {
                assertEquals("$outcome on channel $channel", channel == Channel.ID_VID,
                    AuditRecoveryPolicy.shouldRequestKeyframe(outcome, channel))
            }
        }
    }

    @Test fun `only a completed video mismatch discards the current unit`() {
        for (outcome in FragmentedMessageAudit.Outcome.entries) {
            for (channel in 0..255) {
                val finding = FragmentedMessageAudit.Result(channel, outcome, 1000, 1, 2)
                assertEquals("$outcome on channel $channel",
                    channel == Channel.ID_VID && outcome == FragmentedMessageAudit.Outcome.DELTA_CHANGED,
                    AuditRecoveryPolicy.shouldDiscardAssembledUnit(finding))
            }
        }
    }

    @Test fun `plaintext mismatches on either side of the old threshold request discard`() {
        // The 256-byte threshold compensated encrypted-length uncertainty. It must not silently
        // accept a damaged plaintext run or require a healthy establishing run before detection.
        for (difference in listOf(-1000L, -256L, -255L, -1L, 1L, 255L, 256L, 1000L)) {
            val audit = FragmentedMessageAudit()
            audit.onMessage(Channel.ID_VID, 9, 2000, 4000)
            val finding = audit.onMessage(Channel.ID_VID, 10, (2000 - difference).toInt(), 0)!!
            assertTrue(AuditRecoveryPolicy.shouldRequestKeyframe(finding.outcome, finding.channel))
            assertTrue(AuditRecoveryPolicy.shouldDiscardAssembledUnit(finding))
        }
    }

}
