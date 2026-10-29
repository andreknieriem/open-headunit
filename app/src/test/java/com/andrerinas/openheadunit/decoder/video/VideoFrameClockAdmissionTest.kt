package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.*
import org.junit.Test

class VideoFrameClockAdmissionTest {
    @Test fun `local timestamps admit delayed delivery without changing presentation time`() {
        val arrival = 16_000_000_000_000L
        val requested = arrival + 100_000_000
        val actual = requested + 8_000_000
        assertTrue(VideoFrameClockAdmission.accepts(arrival, requested, actual, actual))
        assertTrue(VideoFrameClockAdmission.accepts(arrival, requested, actual, actual + 3_000_000_000))
        assertFalse(VideoFrameClockAdmission.accepts(arrival, requested, actual,
            actual + VideoFrameClockAdmission.MAX_CALLBACK_DELAY_NS + 1))
    }

    @Test fun `foreign future prearrival and stalled presentation values are rejected`() {
        val arrival = 16_018_145_329_872L
        val requested = arrival + 10_000_000
        val receipt = requested + 20_000_000
        assertFalse(VideoFrameClockAdmission.accepts(arrival, requested, 13_100_171_000, receipt))
        assertFalse(VideoFrameClockAdmission.accepts(arrival, requested, arrival - 1, receipt))
        assertFalse(VideoFrameClockAdmission.accepts(arrival, requested, receipt + 1, receipt))
        val stalled = arrival + VideoFrameClockAdmission.MAX_PIPELINE_NS + 1
        assertFalse(VideoFrameClockAdmission.accepts(arrival, requested, stalled, stalled))
    }

    @Test fun `sentinel and subtraction overflow cannot pass bounded clock checks`() {
        assertFalse(VideoFrameClockAdmission.accepts(1, 2, Long.MIN_VALUE, 3))
        assertFalse(VideoFrameClockAdmission.accepts(1, 2, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(VideoFrameClockAdmission.accepts(Long.MIN_VALUE + 1, 2, 3, 4))
        assertFalse(VideoFrameClockAdmission.accepts(-10, -9, -8, Long.MAX_VALUE))
        // nanoTime's origin is arbitrary; coherent negative-domain times need no conversion.
        assertTrue(VideoFrameClockAdmission.accepts(-1_000, -900, -800, -700))
    }
}
