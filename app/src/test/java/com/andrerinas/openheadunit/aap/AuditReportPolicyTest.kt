package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that failed in the field: the audit spent its whole ten-line budget in 200ms on
 * false positives and was silent for the remaining five minutes, including both windows in which a
 * reporter's artifact was on screen.
 */
class AuditReportPolicyTest {

    private val burst = AuditReportPolicy.BURST_REPORTS
    private val interval = AuditReportPolicy.SUMMARY_INTERVAL_MS

    @Test
    fun `the first reports print immediately, however fast they arrive`() {
        // The connect-time burst is worth seeing in full - it is where a systematic fault shows up.
        for (printed in 0 until burst) {
            assertTrue(AuditReportPolicy.shouldReport(printed, lastReportMs = 1000, nowMs = 1000))
        }
    }

    @Test
    fun `the budget refills instead of running out`() {
        // The whole point. Under the old hard cap this was false forever.
        assertFalse(AuditReportPolicy.shouldReport(burst, lastReportMs = 1000, nowMs = 1000))
        assertTrue(AuditReportPolicy.shouldReport(burst, lastReportMs = 1000, nowMs = 1000 + interval))
        assertTrue(AuditReportPolicy.shouldReport(10_000, lastReportMs = 1000, nowMs = 1000 + interval))
    }

    @Test
    fun `a fault that starts in minute nine is still reported`() {
        // The failure this exists to prevent, stated as the case: budget long gone, hours later,
        // something starts going wrong.
        val nineMinutes = 9 * 60_000L
        assertTrue(AuditReportPolicy.shouldReport(burst, lastReportMs = 500, nowMs = nineMinutes))
    }

    @Test
    fun `a storm is bounded to roughly one line per interval`() {
        // Replay a fault firing every 100ms for ten minutes and count what would be printed.
        var printed = 0
        var lastReport = 0L
        var now = 0L
        while (now < 10 * 60_000L) {
            if (AuditReportPolicy.shouldReport(printed, lastReport, now)) {
                printed++
                lastReport = now
            }
            now += 100
        }
        // The burst, plus one per interval afterwards.
        assertTrue("expected the burst plus ~10 summaries, got $printed", printed in burst + 8..burst + 11)
    }

    @Test
    fun `the interval is not so long that a report is lost in the noise of a drive`() {
        assertTrue("a summary every minute at most", interval <= 60_000L)
        assertTrue("but not so often it floods", interval >= 10_000L)
    }
}
