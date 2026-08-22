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


    /** A media series, configured exactly as the transport configures video and audio. */
    private fun mediaSeries() = LinkGapMonitor(
        LinkGapMonitor.SUBJECT_VIDEO,
        LinkGapMonitor.MIN_GAPS_MEDIA,
        LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA
    )

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

    @Test
    fun `the ping masks a total media outage from the link series`() {
        // The fault five captures showed: video and audio stop for about six seconds out of every
        // ten and a half, while the phone's once-a-second CONTROL ping runs on untouched. Fed every
        // message, the monitor sees a second between pings and calls that healthy - which is exactly
        // what it did in the field, printing twice across five logs and never naming an outage
        // longer than 1.8s. The media series is what makes the same session legible.
        val link = LinkGapMonitor()
        val video = mediaSeries()
        val linkReports = mutableListOf<LinkGapMonitor.Report>()
        val videoReports = mutableListOf<LinkGapMonitor.Report>()

        var t = 0L
        repeat(12) {
            // 4.5s of picture, then 6s of nothing but pings, over and over.
            var frame = t
            while (frame < t + 4_500L) {
                link.onMessage(frame)?.let { r -> linkReports.add(r) }
                video.onMessage(frame)?.let { r -> videoReports.add(r) }
                frame += 20L
            }
            var ping = t + 4_500L
            while (ping < t + 10_500L) {
                link.onMessage(ping)?.let { r -> linkReports.add(r) }
                ping += 1_000L
            }
            t += 10_500L
        }

        assertTrue(
            "a link carrying only pings must still read as healthy - that is the blind spot, " +
                "got $linkReports",
            linkReports.isEmpty()
        )
        assertTrue("the video series has to see it, got $videoReports", videoReports.isNotEmpty())
        videoReports.forEach {
            assertEquals(LinkGapMonitor.SUBJECT_VIDEO, it.subject)
            assertTrue(
                "the picture was gone for well over half the window, got ${it.deadPercent}%",
                it.deadPercent > 45
            )
            assertTrue("several outages per window", it.gaps >= LinkGapMonitor.MIN_GAPS_MEDIA)
        }
        assertTrue(
            "the cadence is the field that identifies this waveform",
            videoReports.any { r -> r.medianPeriodMs?.let { it in 10_000L..11_000L } == true }
        )
    }

    @Test
    fun `one long silence on a media series says nothing`() {
        // The easy half of the idle screen: video stops dead and stays stopped. One gap, so the
        // recurrence floor alone is enough here. It is not enough for the case below.
        val video = mediaSeries()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        video.onMessage(0L)
        stream(video, 0L, 5_000L, 50, reports)
        video.onMessage(40_000L)?.let { reports.add(it) }   // 35s of a still screen
        stream(video, 40_000L, 60_000L, 50, reports)

        assertTrue("an idle screen is not a fault, got $reports", reports.isEmpty())
    }

    /**
     * The idle screen as hardware actually produces it, which is not what the recurrence floor was
     * designed against.
     *
     * Three untouched minutes on a stationary Google Maps screen with no navigation, measured on a
     * rig: four windows, `2-5` gaps in every one of them, `dead=95%`, `99%`, `96%`, `99%`, and
     * intervals scattered from 3.3s to 17.9s. The screen was not silent, it was trickling - an
     * isolated packet every few seconds - and each arrival closed one gap and opened the next. The
     * first version of this monitor printed all four lines on a session nobody was even touching.
     */
    @Test
    fun `a trickling idle screen says nothing either`() {
        val video = mediaSeries()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        val measuredGaps = longArrayOf(3_319L, 14_945L, 5_000L, 4_000L, 2_700L)

        var t = 0L
        video.onMessage(t)
        repeat(12) {
            for (gap in measuredGaps) {
                t += gap
                video.onMessage(t)?.let { r -> reports.add(r) }
                t += 40L                                     // the isolated arrival, a packet or two
                video.onMessage(t)?.let { r -> reports.add(r) }
            }
        }

        assertTrue(
            "a screen nobody is touching must not print, got $reports",
            reports.isEmpty()
        )
    }

    @Test
    fun `a media series still reports the moment the silence recurs`() {
        val video = mediaSeries()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        var t = 0L
        video.onMessage(t)
        repeat(6) {
            t = stream(video, t, 4_500L, 50, reports)
            t += 6_000L
            video.onMessage(t)?.let { r -> reports.add(r) }
        }

        assertTrue("two gaps in a window is a cadence, not an idle screen", reports.isNotEmpty())
        reports.forEach { assertTrue(it.gaps >= LinkGapMonitor.MIN_GAPS_MEDIA) }
    }

    @Test
    fun `a media report names its own series`() {
        val video = LinkGapMonitor.Report(
            gaps = 12, windowMs = 31_500L, deadMs = 17_400L,
            longestMs = 6_460L, medianPeriodMs = 10_500L,
            subject = LinkGapMonitor.SUBJECT_VIDEO
        )
        assertEquals(
            "inbound video quiet 12 times in 31500ms: dead=17400ms (55%), longest=6460ms, " +
                "period~10500ms",
            video.toString()
        )
    }

    @Test
    fun `the reporter's own waveform still prints against the new ceiling`() {
        // 4.5s of picture at 50fps, then 6s of nothing, over and over: dead lands near 57%, which is
        // what a stuttering picture looks like and is the whole reason the instrument exists. The
        // ceiling has to leave this alone.
        val video = mediaSeries()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        var t = 0L
        video.onMessage(t)
        repeat(8) {
            t = stream(video, t, 4_500L, 50, reports)
            t += 6_000L
            video.onMessage(t)?.let { r -> reports.add(r) }
        }

        assertTrue("the fault this was written for must still print", reports.isNotEmpty())
        reports.forEach {
            assertTrue(
                "and it must sit well inside the ceiling, got ${it.deadPercent}%",
                it.deadPercent <= LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA
            )
        }
    }

    @Test
    fun `the audio waveform a rig measured still prints`() {
        // Six scripted pause/play cycles, as run on hardware: the audio channel went quiet for about
        // 1.8s in every 10.2s and the series reported dead=11% and 18%. A long way under the
        // ceiling, and it must stay reported.
        val audio = LinkGapMonitor(
            LinkGapMonitor.SUBJECT_AUDIO,
            LinkGapMonitor.MIN_GAPS_MEDIA,
            LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA
        )
        val reports = mutableListOf<LinkGapMonitor.Report>()
        var t = 0L
        audio.onMessage(t)
        repeat(9) {
            t = stream(audio, t, 8_400L, 50, reports)
            t += 1_800L
            audio.onMessage(t)?.let { r -> reports.add(r) }
        }

        assertTrue("the measured positive control must still print", reports.isNotEmpty())
        reports.forEach { assertTrue("got ${it.deadPercent}%", it.deadPercent < 30) }
    }

    @Test
    fun `the ceiling is inclusive`() {
        // Two gaps of 12750ms inside a 30001ms window is 84%: reported. Two of 13000ms is 86%: not.
        fun replay(gapMs: Long): List<LinkGapMonitor.Report> {
            val video = mediaSeries()
            val reports = mutableListOf<LinkGapMonitor.Report>()
            var t = 0L
            video.onMessage(t)
            repeat(2) {
                t += gapMs
                video.onMessage(t)?.let { r -> reports.add(r) }
                t += 1L
                video.onMessage(t)?.let { r -> reports.add(r) }
            }
            // Carry traffic until the window closes, without opening a third gap.
            while (t < 30_001L) {
                t += 100L
                video.onMessage(t)?.let { r -> reports.add(r) }
            }
            return reports
        }

        val under = replay(12_750L)
        assertEquals("84% is inside the ceiling, so it reports", 1, under.size)
        assertEquals(2, under[0].gaps)
        assertTrue(
            "got ${under[0].deadPercent}%",
            under[0].deadPercent <= LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA
        )

        val over = replay(13_000L)
        assertTrue("86% is a stopped picture, not a stuttering one, got $over", over.isEmpty())
    }

    @Test
    fun `the link series keeps no ceiling`() {
        // A link that is 99% silent is a dead link and must still be reported: the ceiling belongs
        // to the media series, where near-total silence is the normal idle screen.
        val link = LinkGapMonitor()
        val reports = mutableListOf<LinkGapMonitor.Report>()
        var t = 0L
        link.onMessage(t)
        repeat(12) {
            t += 9_900L
            link.onMessage(t)?.let { r -> reports.add(r) }
            t += 40L
            link.onMessage(t)?.let { r -> reports.add(r) }
        }

        assertTrue("a link this quiet must still print, got $reports", reports.isNotEmpty())
        assertTrue(reports.any { it.deadPercent > LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA })
    }
}
