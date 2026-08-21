package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monitor exists because two reporters' captures said the same thing and only one of them could
 * be read. Both waveforms are replayed here in the numbers their captures produced, so that what the
 * app prints can be checked against what the offline script printed for the same link.
 *
 * The tests that matter most are the ones asserting silence. An instrument that speaks on a healthy
 * session gets ignored on an unhealthy one, and that is not hypothetical here: it is what happened
 * to the framing audit, which spent its whole print budget on false positives in the first 200 ms
 * and was then switched off for the five minutes that mattered.
 */
class LinkGapMonitorTest {

    /**
     * Feed traffic at [fps] for [durationMs] starting at [startMs], collecting any reports.
     * Returns the timestamp of the last message sent.
     */
    private fun stream(
        monitor: LinkGapMonitor,
        startMs: Long,
        durationMs: Long,
        fps: Int,
        reports: MutableList<LinkGapMonitor.Report>
    ): Long {
        val step = 1000L / fps
        var t = startMs
        val end = startMs + durationMs
        while (t <= end) {
            monitor.onMessage(t)?.let { reports.add(it) }
            t += step
        }
        return t - step
    }

    @Test
    fun `a healthy link says nothing at all`() {
        val monitor = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        // Sixty seconds at 50 fps - two full windows, nothing anywhere near the threshold.
        stream(monitor, 10_000L, 60_000L, 50, reports)
        assertTrue("a clean link must print nothing, got $reports", reports.isEmpty())
    }

    @Test
    fun `the waveform one reporter's verbose capture measured`() {
        // 1.59s of silence every 11.57s, sustained - profiled at 14.1% dead over 487.7s. A 30s
        // window holds two or three of those cycles depending on where it falls, so the counts
        // quantise; the two fields that identify the waveform do not.
        val monitor = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        var t = 0L
        monitor.onMessage(t)
        repeat(12) {
            t = stream(monitor, t, 9_980L, 50, reports)   // the quiet interval, carrying traffic
            t += 1_590L                                   // the silence
            monitor.onMessage(t)?.let { r -> reports.add(r) }
        }

        assertTrue("expected several reports, got ${reports.size}", reports.size >= 3)
        reports.forEach {
            assertEquals("every gap in this waveform is the same length", 1_590L, it.longestMs)
            assertTrue(
                "dead time should read near the 14% the script measured, got ${it.deadPercent}%",
                it.deadPercent in 9..16
            )
        }
        assertTrue(
            "the cadence must show up as the start-to-start interval",
            reports.any { it.medianPeriodMs == 11_570L }
        )
    }

    @Test
    fun `the waveform the second reporter's capture measured`() {
        // 5-6s of silence every ~10.5s, each long gap trailed by a short one, with only two or
        // three seconds of picture in between. Far more severe than the first: most of that session
        // is dead air, which is what "it did nothing, no improvements at all" was describing.
        val monitor = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        var t = 0L
        monitor.onMessage(t)
        repeat(8) {
            t = stream(monitor, t, 2_500L, 50, reports)
            t += 5_960L                                   // the long gap
            monitor.onMessage(t)?.let { r -> reports.add(r) }
            t = stream(monitor, t, 20L, 50, reports)
            t += 2_130L                                   // the short one trailing it
            monitor.onMessage(t)?.let { r -> reports.add(r) }
        }

        assertTrue("expected at least two reports, got ${reports.size}", reports.size >= 2)
        reports.forEach {
            assertEquals("the long gap is the longest", 5_960L, it.longestMs)
            assertTrue(
                "a link this bad must read as mostly dead, got ${it.deadPercent}%",
                it.deadPercent > 60
            )
            assertNotNull("paired gaps give an interval to report", it.medianPeriodMs)
        }
    }

    @Test
    fun `only the window holding a gap speaks`() {
        val monitor = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        monitor.onMessage(0L)
        monitor.onMessage(2_000L)?.let { reports.add(it) }   // one gap, early in the window
        stream(monitor, 2_000L, 90_000L, 50, reports)        // three clean windows after it

        assertEquals("exactly one window held the single gap", 1, reports.size)
        assertEquals(1, reports[0].gaps)
        assertEquals(2_000L, reports[0].longestMs)
        assertNull("one gap has no start-to-start interval", reports[0].medianPeriodMs)
    }

    @Test
    fun `the threshold is exclusive`() {
        val exactly = LinkGapMonitor()
        val atThreshold = mutableListOf<LinkGapMonitor.Report>()
        exactly.onMessage(0L)
        exactly.onMessage(LinkGapMonitor.GAP_THRESHOLD_MS)
        stream(exactly, LinkGapMonitor.GAP_THRESHOLD_MS, 35_000L, 50, atThreshold)
        assertTrue("a gap of exactly the threshold is not a gap", atThreshold.isEmpty())

        val overByOne = LinkGapMonitor()
        val overThreshold = mutableListOf<LinkGapMonitor.Report>()
        overByOne.onMessage(0L)
        overByOne.onMessage(LinkGapMonitor.GAP_THRESHOLD_MS + 1)
        stream(overByOne, LinkGapMonitor.GAP_THRESHOLD_MS + 1, 35_000L, 50, overThreshold)
        assertEquals("one millisecond over is", 1, overThreshold.size)
        assertEquals(LinkGapMonitor.GAP_THRESHOLD_MS + 1, overThreshold[0].longestMs)
    }

    @Test
    fun `the first message after a reset opens no gap`() {
        val monitor = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        stream(monitor, 0L, 40_000L, 50, reports)
        monitor.reset()

        // A new session an hour later on the same monotonic clock. The interval across the reset is
        // enormous and must not be measured - the transport outliving a session is why reset exists.
        monitor.onMessage(3_600_000L)
        stream(monitor, 3_600_000L, 40_000L, 50, reports)
        assertTrue(
            "a re-armed monitor must not report the gap between sessions, got $reports",
            reports.isEmpty()
        )
    }

    @Test
    fun `a gap spanning a window boundary is counted once`() {
        val monitor = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        monitor.onMessage(0L)
        stream(monitor, 0L, 29_000L, 50, reports)
        // Silence starting inside the window and ending well past its nominal close. A window can
        // only end on a message, so this one runs long and has to say so.
        monitor.onMessage(45_000L)?.let { reports.add(it) }
        stream(monitor, 45_000L, 40_000L, 50, reports)

        assertEquals("one gap, reported in one window", 1, reports.size)
        assertEquals(1, reports[0].gaps)
        assertEquals(45_000L, reports[0].windowMs)
        assertEquals(16_000L, reports[0].deadMs)
        assertEquals("the percentage is against the elapsed window, not the nominal one",
            35, reports[0].deadPercent)
    }

    @Test
    fun `the report reads as the offline script's fields`() {
        val periodic = LinkGapMonitor.Report(
            gaps = 3, windowMs = 30_412L, deadMs = 17_240L,
            longestMs = 6_110L, medianPeriodMs = 10_520L
        )
        assertEquals(56, periodic.deadPercent)
        assertEquals(
            "inbound link quiet 3 times in 30412ms: dead=17240ms (56%), longest=6110ms, " +
                "period~10520ms",
            periodic.toString()
        )

        val single = LinkGapMonitor.Report(
            gaps = 1, windowMs = 30_000L, deadMs = 1_310L,
            longestMs = 1_310L, medianPeriodMs = null
        )
        assertEquals(
            "inbound link quiet 1 time in 30000ms: dead=1310ms (4%), longest=1310ms",
            single.toString()
        )
    }
}
