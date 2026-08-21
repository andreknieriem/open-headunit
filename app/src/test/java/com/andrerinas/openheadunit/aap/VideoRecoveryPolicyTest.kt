package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecoveryPolicyTest {

    @Test
    fun `a request is allowed once the throttle window has fully elapsed`() {
        val last = 10_000L
        assertFalse(VideoRecoveryPolicy.canRequestKeyframe(last, last))
        assertFalse(
            VideoRecoveryPolicy.canRequestKeyframe(last + KEYFRAME_REQUEST_THROTTLE_MS, last)
        )
        assertTrue(
            VideoRecoveryPolicy.canRequestKeyframe(last + KEYFRAME_REQUEST_THROTTLE_MS + 1, last)
        )
    }

    @Test
    fun `the first request of a session is always allowed`() {
        assertTrue(VideoRecoveryPolicy.canRequestKeyframe(nowMs = 12_345L, lastRequestMs = 0L))
    }

    @Test
    fun `no request is allowed anywhere inside one throttle window`() {
        // A burst of corrupt frames must produce at most one focus cycle: the phone rebuilds the
        // stream on each one, so several inside a second costs more picture than it recovers.
        val last = 10_000L
        for (offset in 0L..KEYFRAME_REQUEST_THROTTLE_MS) {
            assertFalse(VideoRecoveryPolicy.canRequestKeyframe(last + offset, last))
        }
    }

    @Test
    fun `a drop before the first rendered frame never asks`() {
        // A warming codec sheds frames while healthy, and the nudge does nothing before the
        // phone has started its sink - that window belongs to WarmRelaunchKeyframePolicy.
        assertFalse(
            VideoRecoveryPolicy.shouldRequestOnDroppedFrame(
                hasRendered = false, nowMs = 12_345L, lastRequestMs = 0L
            )
        )
    }

    @Test
    fun `the first drop after a rendered frame asks immediately`() {
        assertTrue(
            VideoRecoveryPolicy.shouldRequestOnDroppedFrame(
                hasRendered = true, nowMs = 12_345L, lastRequestMs = 0L
            )
        )
    }

    @Test
    fun `drops share the corrupt-frame throttle window`() {
        // A sustained backlog drops a frame per arrival; that must cost one request per window,
        // not one per frame.
        val last = 10_000L
        assertFalse(
            VideoRecoveryPolicy.shouldRequestOnDroppedFrame(
                hasRendered = true, nowMs = last + KEYFRAME_REQUEST_THROTTLE_MS, lastRequestMs = last
            )
        )
        assertTrue(
            VideoRecoveryPolicy.shouldRequestOnDroppedFrame(
                hasRendered = true, nowMs = last + KEYFRAME_REQUEST_THROTTLE_MS + 1, lastRequestMs = last
            )
        )
    }
}
