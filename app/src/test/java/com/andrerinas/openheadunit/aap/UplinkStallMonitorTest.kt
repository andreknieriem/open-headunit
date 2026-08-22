package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbound half of the same question the gap monitor asks inbound.
 *
 * As with that one, the tests that matter most are the ones asserting silence: an instrument that
 * speaks on a healthy session gets ignored on an unhealthy one.
 */
class UplinkStallMonitorTest {

    /** Writes that return immediately, at [hz], for [durationMs] from [startMs]. */
    private fun drain(
        monitor: UplinkStallMonitor,
        startMs: Long,
        durationMs: Long,
        hz: Int,
        reports: MutableList<UplinkStallMonitor.Report>
    ): Long {
        val step = 1000L / hz
        var t = startMs
        val end = startMs + durationMs
        while (t <= end) {
            monitor.onWrite(1L, t)?.let { reports.add(it) }
            t += step
        }
        return t - step
    }

    @Test
    fun `an uplink that drains says nothing at all`() {
        val monitor = UplinkStallMonitor()
        val reports = mutableListOf<UplinkStallMonitor.Report>()
        drain(monitor, 0L, 90_000L, 50, reports)
        assertTrue("a healthy uplink must print nothing, got $reports", reports.isEmpty())
    }

    @Test
    fun `writes that block are counted and timed`() {
        val monitor = UplinkStallMonitor()
        val reports = mutableListOf<UplinkStallMonitor.Report>()
        var t = 0L
        // Three cycles of acks flowing, then one write that holds the thread for six seconds - the
        // shape a stalled radio would leave if the media stall were ours rather than the phone's.
        repeat(3) {
            t = drain(monitor, t, 4_500L, 50, reports)
            t += 6_000L
            monitor.onWrite(6_000L, t)?.let { r -> reports.add(r) }
        }
        t = drain(monitor, t, 20_000L, 50, reports)

        assertTrue("the blocked writes have to surface, got $reports", reports.isNotEmpty())
        val first = reports.first()
        assertEquals(6_000L, first.longestMs)
        assertTrue("more writes than stalls", first.writes > first.stalls)
        assertTrue("a stalled uplink reads as mostly blocked, got ${first.blockedPercent}%",
            first.blockedPercent > 30)
    }

    @Test
    fun `the threshold is exclusive`() {
        val atThreshold = UplinkStallMonitor()
        val quiet = mutableListOf<UplinkStallMonitor.Report>()
        atThreshold.onWrite(UplinkStallMonitor.STALL_THRESHOLD_MS, 0L)
        drain(atThreshold, 0L, 35_000L, 50, quiet)
        assertTrue("a write of exactly the threshold is not a stall", quiet.isEmpty())

        val overByOne = UplinkStallMonitor()
        val loud = mutableListOf<UplinkStallMonitor.Report>()
        overByOne.onWrite(UplinkStallMonitor.STALL_THRESHOLD_MS + 1, 0L)
        drain(overByOne, 0L, 35_000L, 50, loud)
        assertEquals("one millisecond over is", 1, loud.size)
        assertEquals(UplinkStallMonitor.STALL_THRESHOLD_MS + 1, loud[0].longestMs)
    }

    @Test
    fun `the first write after a reset opens no window across sessions`() {
        val monitor = UplinkStallMonitor()
        val reports = mutableListOf<UplinkStallMonitor.Report>()
        drain(monitor, 0L, 40_000L, 50, reports)
        monitor.reset()

        // A new session an hour later on the same monotonic clock. The elapsed time across the
        // reset is enormous and must not become one window's worth of measurement.
        monitor.onWrite(6_000L, 3_600_000L)?.let { reports.add(it) }
        assertTrue("a re-armed monitor starts its window at the new session", reports.isEmpty())
        drain(monitor, 3_600_000L, 35_000L, 50, reports)
        assertEquals("and then reports that session's own stall", 1, reports.size)
        assertEquals(6_000L, reports[0].longestMs)
    }

    @Test
    fun `the report says which end was measured`() {
        val report = UplinkStallMonitor.Report(
            stalls = 3, writes = 812, windowMs = 31_500L,
            blockedMs = 17_400L, longestMs = 6_460L
        )
        assertEquals(55, report.blockedPercent)
        assertEquals(
            "uplink blocked on 3 writes of 812 in 31500ms: blocked=17400ms (55%), longest=6460ms",
            report.toString()
        )

        val single = UplinkStallMonitor.Report(
            stalls = 1, writes = 400, windowMs = 30_000L,
            blockedMs = 300L, longestMs = 300L
        )
        assertEquals(
            "uplink blocked on 1 write of 400 in 30000ms: blocked=300ms (1%), longest=300ms",
            single.toString()
        )
    }
}
