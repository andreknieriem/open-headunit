package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderRestartPolicyTest {

    @Test
    fun `a session that never rendered counts failed starts`() {
        // The one case the ladder exists for: a component that cannot decode this type at all.
        assertTrue(
            DecoderRestartPolicy.countsTowardFailure(
                renderedSinceLastStart = false, renderedThisSession = false
            )
        )
    }

    @Test
    fun `a stream that has already rendered is proven and never escalates`() {
        // The relaunch case: the surface swap zeroed the per-start timestamp, but the session
        // has decoded this stream - warm-up churn is not evidence the codec is broken.
        assertFalse(
            DecoderRestartPolicy.countsTowardFailure(
                renderedSinceLastStart = false, renderedThisSession = true
            )
        )
    }

    @Test
    fun `a restart after a frame from the current start never counts`() {
        assertFalse(
            DecoderRestartPolicy.countsTowardFailure(
                renderedSinceLastStart = true, renderedThisSession = true
            )
        )
    }

}
