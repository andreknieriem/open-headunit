package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers in the "measured" cases come from a #219 reporter's Galaxy Tab S7 FE running
 * 2560x1440 HEVC on `c2.qti.hevc.decoder`. They are the reason the threshold is where it is, so
 * they are pinned here rather than described in a comment somewhere.
 */
class VideoBackpressurePolicyTest {

    private val window = 5000L

    @Test
    fun `shedding while waiting a large share of the window is backpressure`() {
        // The four measured drop bursts: 40%, 27%, 16% and 14% of a 5s window.
        listOf(2019L to 29L, 1333L to 18L, 804L to 11L, 705L to 2L).forEach { (wait, dropped) ->
            assertTrue(
                "wait=${wait}ms dropped=$dropped should be attributed to the codec",
                VideoBackpressurePolicy.isBackpressureWindow(window, wait, dropped)
            )
        }
    }

    @Test
    fun `shedding with a small wait is not the codec`() {
        // The fifth measured burst: 9 frames shed after only 180ms of waiting - 3.6% of the window.
        // Something else lost those, and calling it a slow decoder would send the next reader to
        // the wrong file.
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, 180, 9))
    }

    @Test
    fun `waiting without shedding is a decoder under load that is still keeping up`() {
        // The median healthy window in that capture waited 188ms; the 90th percentile waited 587ms.
        // Neither shed anything, and neither is a fault.
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, 188, 0))
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, 587, 0))
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, 4000, 0))
    }

    @Test
    fun `the threshold is inclusive at exactly the share`() {
        val exact = window * VideoBackpressurePolicy.WAIT_PERCENT / 100
        assertTrue(VideoBackpressurePolicy.isBackpressureWindow(window, exact, 1))
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, exact - 1, 1))
    }

    @Test
    fun `degenerate windows are never backpressure`() {
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(0, 4000, 10))
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, 0, 10))
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, 4000, 0))
        assertFalse(VideoBackpressurePolicy.isBackpressureWindow(window, -1, 10))
    }

    @Test
    fun `one window is not a verdict`() {
        assertFalse(VideoBackpressurePolicy.shouldReport(1, alreadyReported = false))
        assertTrue(VideoBackpressurePolicy.shouldReport(2, alreadyReported = false))
    }

    @Test
    fun `the report is once per decoder session`() {
        assertFalse(VideoBackpressurePolicy.shouldReport(2, alreadyReported = true))
        assertFalse(VideoBackpressurePolicy.shouldReport(50, alreadyReported = true))
    }

    @Test
    fun `the required windows do not have to be consecutive`() {
        // Deliberate: in the capture this was built from the bursts were isolated windows, so a rule
        // demanding a run of them would never have fired on the device that motivated it.
        assertEquals(2, VideoBackpressurePolicy.WINDOWS_BEFORE_REPORT)
    }
}
