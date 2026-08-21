package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.FragmentedMessageAudit.Outcome
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing audit was built as an instrument and wired like one: it computed its finding, the
 * reader printed it, and nothing acted on it. This is the rule that decides which of its findings
 * are worth more than a log line.
 *
 * The cost of getting it wrong runs both ways. Too narrow and the one corruption mode nothing else
 * can see goes on being seen and ignored - a hardware round measured 37 and 59 injected
 * middle-fragment faults producing zero keyframe requests. Too broad and a fault that already has a
 * reporter gets asked about twice, which also stamps the corruption clock twice and makes one lost
 * fragment look like a wire that is still breaking.
 */
class AuditRecoveryPolicyTest {

    @Test
    fun `a hole in a video run is asked about, because nothing else will`() {
        // DELTA_CHANGED means the run's byte total cannot be explained by its fragment count, i.e.
        // a fragment is missing. When it is a middle fragment, VideoFragmentAssembler still sees a
        // first, some middles and a last in order and assembles the frame around the hole. This is
        // the only outcome with no second reporter.
        assertTrue(AuditRecoveryPolicy.shouldRequestKeyframe(Outcome.DELTA_CHANGED, Channel.ID_VID))
    }

    @Test
    fun `the outcomes the reassembler already reports are left to it`() {
        // TRUNCATED_RUN surfaces downstream as Anomaly.TRUNCATED_PREVIOUS and ORPHANED_FRAGMENT as
        // Anomaly.ORPHANED_FRAGMENT, both of which already call requestKeyframe. Asking here too
        // would spend two requests on one fault and, worse, stamp lastWireCorruptionMs twice - so a
        // single lost fragment would read as a wire still losing them and hold off the focus cycle.
        assertFalse(AuditRecoveryPolicy.shouldRequestKeyframe(Outcome.TRUNCATED_RUN, Channel.ID_VID))
        assertFalse(AuditRecoveryPolicy.shouldRequestKeyframe(Outcome.ORPHANED_FRAGMENT, Channel.ID_VID))
    }

    @Test
    fun `the first run on a channel is a baseline, not a fault`() {
        // FIRST_OBSERVATION is how the audit learns the channel's convention. It fires once per
        // channel per session and on a perfectly healthy stream.
        assertFalse(AuditRecoveryPolicy.shouldRequestKeyframe(Outcome.FIRST_OBSERVATION, Channel.ID_VID))
    }

    @Test
    fun `a hole in another channel's run is real but not repairable this way`() {
        // The audit runs per channel because runs interleave, and MUSIC_PLAYBACK fragments enough to
        // have its own accounting - two hardware rounds established a convention on it alongside
        // VIDEO. A keyframe repairs video and nothing else, and the protocol has no equivalent ask.
        for (channel in listOf(Channel.ID_CTR, Channel.ID_SEN, Channel.ID_INP, Channel.ID_AUD, Channel.ID_MPB, Channel.ID_MIC)) {
            assertFalse(
                "${Channel.name(channel)} has no keyframe to ask for",
                AuditRecoveryPolicy.shouldRequestKeyframe(Outcome.DELTA_CHANGED, channel)
            )
        }
    }

    @Test
    fun `no outcome on a non-video channel ever asks`() {
        for (outcome in Outcome.entries) {
            assertFalse(
                "$outcome on MUSIC_PLAYBACK must not ask for a video keyframe",
                AuditRecoveryPolicy.shouldRequestKeyframe(outcome, Channel.ID_MPB)
            )
        }
    }
}
