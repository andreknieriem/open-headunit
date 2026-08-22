package com.andrerinas.openheadunit.aap

/**
 * How long an inbound AAP series goes completely silent, and how often.
 *
 * Two reporters on Android 8.1 head units describe the same thing in the same words - the music
 * cuts in and out - and on both the fault is neither the audio path nor the decoder. The media
 * stops, on video and audio at once, on a fixed cadence, and then resumes. One capture measured
 * 1.59 s of silence every 11.57 s; another, on different hardware, 5-6 s every 10-11 s with the
 * picture and the sound dying together.
 *
 * Neither was readable from what the app prints. The only instrument that could see it was an
 * offline script over `RECV:` lines, which exist solely at VERBOSE - so recognising the first case
 * took a 151,366-line export, and the second could not be diagnosed at all, because the reporter
 * had been asked for INFO. A fault this distinctive should not need a special capture to notice.
 *
 * So the same measurement runs in the app, at INFO, reporting the fields the script reports so a
 * log line and a rig run can be compared directly.
 *
 * **One instance per series.** An instance fed every inbound message measures *the link*, and that
 * is the one thing this fault leaves alone: the phone sends a `CONTROL Ping Request` about once a
 * second for the life of the session, so a link carrying nothing but pings scores perfectly healthy
 * while both media channels are dead. Measured on five captures of the second waveform: the
 * all-channel series printed twice, 1.6-1.8 s in a 30 s window, across sessions whose picture was
 * gone for six seconds out of every ten. Separate instances for video and for audio are what make
 * that visible, and comparing the three is what says whether the link died or only the media did.
 *
 * **Diagnostic only - nothing reads the report.** The [GAP_THRESHOLD_MS] below rests on one
 * observed phone behaviour rather than on anything in the protocol, which is reason enough not to
 * hang recovery off it. The line says what was measured and leaves the conclusion to the reader.
 *
 * Pure and clock-free: the caller passes the time in, so every measured waveform is replayable in
 * a unit test.
 *
 * @param subject the noun this series names in its report line - the thing that went quiet.
 * @param minGapsToReport how many gaps a window needs before it is worth printing. See
 *   [MIN_GAPS_MEDIA] for why a media series wants more than one and the link series wants exactly
 *   one.
 * @param maxDeadPercentToReport how much of a window may be silence before it stops being a
 *   stuttering series and starts being a stopped one. See [MAX_DEAD_PERCENT_MEDIA].
 */
class LinkGapMonitor(
    private val subject: String = SUBJECT_LINK,
    private val minGapsToReport: Int = 1,
    private val maxDeadPercentToReport: Int = 100
) {

    /**
     * Silence longer than this counts as a gap.
     *
     * The phone sends a `CONTROL Ping Request` once a second for the life of the session, so a full
     * second in which *no* channel says anything is already outside what a working link does. The
     * value matches the offline script's default, which is what makes the two comparable, and it is
     * shared with the media series so that the three lines can be read against each other.
     */
    private val gapThresholdMs = GAP_THRESHOLD_MS

    /** How much link time one report covers. */
    private val windowMs = WINDOW_MS

    /**
     * Whether any message has been seen since the last [reset]. A separate flag rather than a
     * sentinel value of [lastMessageMs], because zero is a timestamp a monotonic clock can legally
     * produce and overloading it makes the very first interval of a session unmeasurable.
     */
    private var started = false
    private var lastMessageMs = 0L
    private var windowStartMs = 0L
    private var gapCount = 0
    private var deadMs = 0L
    private var longestMs = 0L

    /**
     * When each gap in this window began, for the start-to-start interval. Bounded because a link
     * that is more gap than traffic would otherwise grow this without limit; the counters above stay
     * exact regardless, and only the interval degrades.
     */
    private val gapStarts = ArrayList<Long>()

    /**
     * Feed one inbound message and get a report when a window closes with something to say.
     *
     * [nowMs] is a monotonic clock reading - `SystemClock.elapsedRealtime` at the call site.
     *
     * Returns `null` for every message that does not close a window, and for every window that had
     * fewer than [minGapsToReport] gaps in it. A healthy session therefore prints nothing at all,
     * which is the property that makes a line worth reading.
     */
    fun onMessage(nowMs: Long): Report? {
        if (!started) {
            // First message of the session. There is no interval before it to measure, and the
            // window has to start somewhere.
            started = true
            lastMessageMs = nowMs
            windowStartMs = nowMs
            return null
        }

        val gapMs = nowMs - lastMessageMs
        val gapStartMs = lastMessageMs
        lastMessageMs = nowMs

        if (gapMs > gapThresholdMs) {
            gapCount++
            deadMs += gapMs
            if (gapMs > longestMs) longestMs = gapMs
            if (gapStarts.size < MAX_TRACKED_STARTS) gapStarts.add(gapStartMs)
        }

        // The window can only close on a message, so a window that ends mid-outage runs long. The
        // report carries the elapsed time rather than the nominal one, so the percentage stays true.
        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < windowMs) return null

        // Both floors are about the same thing: whether this window describes a series that was
        // working badly or one that was not working at all. The second is an idle screen, and it is
        // not a fault.
        val deadPercent = if (elapsedMs <= 0L) 0 else (deadMs * 100 / elapsedMs).toInt()
        val worthSaying = gapCount >= minGapsToReport && deadPercent <= maxDeadPercentToReport
        val report = if (!worthSaying) null else Report(
            gaps = gapCount,
            windowMs = elapsedMs,
            deadMs = deadMs,
            longestMs = longestMs,
            medianPeriodMs = medianPeriodMs(),
            subject = subject
        )

        windowStartMs = nowMs
        gapCount = 0
        deadMs = 0L
        longestMs = 0L
        gapStarts.clear()
        return report
    }

    /**
     * Forget the previous session.
     *
     * The transport outlives a session and is re-armed for the next one, so a stamp left by the
     * previous phone would otherwise be measured as this session's first gap.
     */
    fun reset() {
        started = false
        lastMessageMs = 0L
        windowStartMs = 0L
        gapCount = 0
        deadMs = 0L
        longestMs = 0L
        gapStarts.clear()
    }

    /**
     * Median start-to-start interval between consecutive gaps, or `null` with fewer than two.
     *
     * The interval is the field that separates a periodic fault from an unlucky patch of traffic:
     * both measured waveforms hold theirs to within a second across an entire session, which is not
     * something congestion does.
     *
     * It is the interval between *gaps*, not between cycles, and on a link whose outages arrive in
     * pairs - one long, one short - it reports the spacing inside the pair rather than the cycle
     * length. That is the honest answer to what was asked, but it is not the number a reader
     * eyeballing the cycle would predict, so read it alongside the count.
     */
    private fun medianPeriodMs(): Long? {
        if (gapStarts.size < 2) return null
        val periods = LongArray(gapStarts.size - 1) { gapStarts[it + 1] - gapStarts[it] }
        periods.sort()
        val mid = periods.size / 2
        return if (periods.size % 2 == 1) periods[mid] else (periods[mid - 1] + periods[mid]) / 2
    }

    /** One window's worth of silence, in the offline script's fields. */
    data class Report(
        val gaps: Int,
        val windowMs: Long,
        val deadMs: Long,
        val longestMs: Long,
        val medianPeriodMs: Long?,
        /** The series that went quiet. Defaulted so the link series reads as it always has. */
        val subject: String = LinkGapMonitor.SUBJECT_LINK
    ) {
        /** Dead time as a whole percentage of the window. */
        val deadPercent: Int
            get() = if (windowMs <= 0L) 0 else (deadMs * 100 / windowMs).toInt()

        override fun toString(): String {
            val period = medianPeriodMs?.let { ", period~${it}ms" } ?: ""
            return "inbound $subject quiet $gaps time${if (gaps == 1) "" else "s"} in ${windowMs}ms: " +
                "dead=${deadMs}ms ($deadPercent%), longest=${longestMs}ms$period"
        }
    }

    companion object {
        /** Silence longer than this is a gap. See [gapThresholdMs]. */
        const val GAP_THRESHOLD_MS = 1_200L

        /**
         * How much link time one report covers.
         *
         * Long enough to hold about three cycles of either measured waveform, so a period can be
         * read off a single line, and clear of the decoder's own five-second throughput cadence so
         * the two do not interleave into noise.
         */
        const val WINDOW_MS = 30_000L

        /** Cap on remembered gap starts. See [gapStarts]. */
        const val MAX_TRACKED_STARTS = 256

        /** The whole link: every channel, every message. */
        const val SUBJECT_LINK = "link"

        /** The projected picture. */
        const val SUBJECT_VIDEO = "video"

        /** The three audio sinks together. */
        const val SUBJECT_AUDIO = "audio"

        /**
         * How many gaps a media series needs before a window is worth printing.
         *
         * More than one, so that a single uninterrupted silence never prints on its own. The link
         * series keeps a floor of one, because *any* second of total silence on every channel at
         * once is already outside what a working session does.
         *
         * On its own this floor does **not** make an idle screen quiet, which is what it was first
         * written to do, and hardware said so: a stationary Google Maps screen with no navigation
         * produced 2-5 gaps in every window, not one. See [MAX_DEAD_PERCENT_MEDIA].
         */
        const val MIN_GAPS_MEDIA = 2

        /**
         * How much of a window a media series may spend silent and still be worth reporting.
         *
         * The discriminator this instrument actually needs, found by putting the first version on
         * hardware. The assumption it replaces was that an idle Android Auto screen sends *no*
         * video, one long silence per window, which [MIN_GAPS_MEDIA] would then suppress. What a
         * stationary map really sends is a trickle - an isolated packet every few seconds, a clock
         * tick or a location pulse - and every one of those arrivals closes a gap and opens a new
         * one. Measured on a rig, three untouched minutes on a stationary map: four windows, 2-5
         * gaps each, `dead=95%`, `99%`, `96%`, `99%`, and intervals scattered from 3.3 s to 17.9 s.
         * The floor above suppressed none of it.
         *
         * What separates that from the fault is not how many gaps there are but how much picture
         * runs between them. The reporter's waveform is 4-8 s of silence every 10.5 s with video
         * flowing at 45-55 fps in between - `dead` around 55-60%, and 22% on the milder capture. An
         * idle screen is 95-99%. Nothing measured has ever landed between, and this sits in the
         * middle of that gap.
         *
         * So a window this dead is not a stuttering picture, it is a stopped one, and a stopped
         * picture is somebody else's instrument: `ProjectionWatchdogPolicy` decides when to call it
         * a lost connection, and the decoder's own throughput line reports the rate. The cost of
         * this ceiling is that a fault severe enough to exceed it goes unnamed here - accepted,
         * because at that point the picture is gone rather than stuttering and those two see it.
         */
        const val MAX_DEAD_PERCENT_MEDIA = 85
    }
}
