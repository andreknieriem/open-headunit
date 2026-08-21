package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue between the transport and the codec has to hold more video than the feed thread in front
 * of it is willing to wait. Issue #830 is what happens when it does not: the queue sheds reference
 * frames the feed thread has not given up on, and the picture drifts until the phone's next keyframe
 * some ~69s later.
 */
class VideoFeedQueuePolicyTest {

    /** The rates Android Auto actually negotiates, and the highest of them. */
    private val realWorldRates = listOf(30, 60)
    private val highestNegotiatedRate = 60

    @Test
    fun `the queue always holds more video than the feed thread waits for`() {
        // The invariant the whole class exists for. Checked across every rate the setting can
        // plausibly carry, not just the two below - a cap the user drags to an odd value must not
        // quietly reopen #830.
        for (fps in 15..120) {
            assertTrue(
                "at ${fps}fps the queue holds ${VideoFeedQueuePolicy.heldMsAt(fps)}ms, which does not " +
                    "cover the ${VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS}ms the feed thread waits",
                VideoFeedQueuePolicy.heldMsAt(fps) >= VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS
            )
        }
    }

    @Test
    fun `the queue depth is the configured window at the rates Android Auto negotiates`() {
        for (fps in realWorldRates) {
            assertEquals(
                "unexpected depth at ${fps}fps",
                (fps * VideoFeedQueuePolicy.FRAME_QUEUE_MS / 1000),
                VideoFeedQueuePolicy.capacityFor(fps)
            )
        }
        // Spelled out, so a change to FRAME_QUEUE_MS shows up as a number a reader recognises.
        assertEquals(15, VideoFeedQueuePolicy.capacityFor(30))
        assertEquals(30, VideoFeedQueuePolicy.capacityFor(60))
    }

    @Test
    fun `a low frame rate cap cannot produce a queue below the floor`() {
        // fpsLimit is the user's cap, not the rate the phone negotiated, so it can understate what
        // is really arriving. The floor is what keeps that from shrinking the queue under the
        // patience it has to cover.
        assertEquals(VideoFeedQueuePolicy.MIN_CAPACITY, VideoFeedQueuePolicy.capacityFor(1))
        assertEquals(VideoFeedQueuePolicy.MIN_CAPACITY, VideoFeedQueuePolicy.capacityFor(20))
    }

    @Test
    fun `a high frame rate cap cannot grow the queue past the memory ceiling`() {
        // Every slot holds a pooled buffer that grows to the largest frame it has carried, so the
        // ceiling is a memory bound, not a latency one.
        assertEquals(VideoFeedQueuePolicy.MAX_CAPACITY, VideoFeedQueuePolicy.capacityFor(120))
        assertEquals(VideoFeedQueuePolicy.MAX_CAPACITY, VideoFeedQueuePolicy.capacityFor(240))
    }

    @Test
    fun `a nonsense frame rate degrades instead of dividing by zero`() {
        // This is read on the feed thread's start path, where an arithmetic exception takes the
        // decoder down rather than degrading it, and fpsLimit comes from stored settings.
        for (fps in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals(VideoFeedQueuePolicy.MIN_CAPACITY, VideoFeedQueuePolicy.capacityFor(fps))
            assertTrue(VideoFeedQueuePolicy.heldMsAt(fps) > 0)
        }
    }

    @Test
    fun `the memory ceiling is where the patience guarantee runs out`() {
        // Documented rather than asserted away: past this rate MAX_CAPACITY decides the depth and
        // the queue no longer covers the patience. It sits far above anything Android Auto asks
        // for, and this test is here so that stops being true loudly rather than silently.
        val lastSafeRate = (VideoFeedQueuePolicy.MAX_CAPACITY * 1000L /
            VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS).toInt()
        assertTrue(
            "the patience guarantee now runs out at ${lastSafeRate}fps, at or below a rate Android " +
                "Auto negotiates (${highestNegotiatedRate}fps)",
            lastSafeRate > highestNegotiatedRate
        )
        assertEquals(133, lastSafeRate)
    }
}
