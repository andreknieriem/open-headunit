package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one distinction this class exists for: a keyframe that was fed against one that decoded.
 *
 * The timestamps below are microseconds, the units the decoder's synthetic monotonic PTS is in.
 */
class KeyframeRepairTrackerTest {

    private val tracker = KeyframeRepairTracker()

    @Test
    fun `feeding a keyframe is not a repair`() {
        tracker.onKeyframeFed(1_000)
        assertTrue(tracker.awaitingOutput)
        assertFalse(tracker.keyframeDecoded)
    }

    @Test
    fun `a frame at or past the keyframe confirms it, once`() {
        tracker.onKeyframeFed(1_000)
        assertTrue(tracker.onFrameRendered(1_000))
        assertTrue(tracker.keyframeDecoded)
        assertFalse(tracker.awaitingOutput)
        // Every later frame is ordinary output, not another repair - the escalation clock is
        // already stopped and re-firing would only cost log lines.
        assertFalse(tracker.onFrameRendered(1_016))
    }

    @Test
    fun `output the codec already had queued does not confirm anything`() {
        // The reason this is a timestamp comparison and not "the next frame rendered". MediaCodec
        // holds frames ahead of the feed, so the renders right after a keyframe is queued are
        // usually frames from before it.
        tracker.onKeyframeFed(5_000)
        assertFalse(tracker.onFrameRendered(4_800))
        assertFalse(tracker.onFrameRendered(4_950))
        assertFalse(tracker.keyframeDecoded)
        assertTrue(tracker.onFrameRendered(5_000))
    }

    @Test
    fun `renders with nothing pending are not repairs`() {
        // After a shed reference frame the decoder renders continuously and the picture is wrong
        // the whole time, which is why a rendered frame on its own can never be the signal.
        assertFalse(tracker.onFrameRendered(2_000))
        assertFalse(tracker.keyframeDecoded)
    }

    @Test
    fun `a newer keyframe replaces an older pending one`() {
        // A holed keyframe stays pending because nothing decodes from it. Holding the older stamp
        // would let the next good keyframe's picture confirm the broken one - and confirming the
        // newest confirms everything before it anyway, output being monotonic.
        tracker.onKeyframeFed(1_000)
        tracker.onKeyframeFed(70_000)
        assertFalse(tracker.onFrameRendered(1_500))
        assertTrue(tracker.onFrameRendered(70_000))
    }

    @Test
    fun `a decoder whose output timestamps say nothing still reports a repair`() {
        // Some components hand every buffer back with the same stamp, or zero. Waiting for a stamp
        // that never comes would have such a decoder called starved every fifteen seconds and spend
        // focus cycles on a picture that is running perfectly - worse than the wedge this replaces.
        val bound = KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED
        tracker.onKeyframeFed(500_000)
        repeat(bound - 1) { assertFalse(tracker.onFrameRendered(0)) }
        assertFalse(tracker.timestampsUnusable)
        assertTrue(tracker.onFrameRendered(0))
        assertTrue(tracker.timestampsUnusable)
        assertTrue(tracker.keyframeDecoded)
    }

    @Test
    fun `the bound is far past any real reorder depth and far short of the starvation patience`() {
        // Half a second at 60fps. Below this it would fire on an ordinary pipeline; above it, the
        // rebuild path would pre-empt it and the guard would never be reached.
        val bound = KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED
        assertTrue(bound >= 10)
        assertTrue(bound / 60.0 * 1000 < DecoderStallCausePolicy.KEYFRAME_STARVATION_PATIENCE_MS)
    }

    @Test
    fun `a run of stale frames does not carry over to the next keyframe`() {
        val bound = KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED
        tracker.onKeyframeFed(1_000)
        repeat(bound - 1) { tracker.onFrameRendered(0) }
        // A new keyframe is a new question; the frames that missed the old one say nothing about it.
        tracker.onKeyframeFed(2_000)
        assertFalse(tracker.onFrameRendered(0))
        assertTrue(tracker.onFrameRendered(2_000))
        assertFalse(tracker.timestampsUnusable)
    }

    @Test
    fun `reset drops a pending keyframe and the decoded flag`() {
        // Presentation stamps restart near zero when the codec is reconfigured, so a stamp carried
        // across a rebuild would be confirmed by the new codec's very first frame.
        tracker.onKeyframeFed(90_000)
        tracker.onFrameRendered(90_000)
        tracker.reset()
        assertFalse(tracker.keyframeDecoded)
        assertFalse(tracker.awaitingOutput)
        assertFalse(tracker.onFrameRendered(0))
    }
}
