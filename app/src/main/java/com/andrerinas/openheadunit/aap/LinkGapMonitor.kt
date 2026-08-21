package com.andrerinas.openheadunit.aap

/**
 * How long the inbound AAP link goes completely silent, and how often.
 *
 * Two reporters on Android 8.1 head units describe the same thing in the same words - the music
 * cuts in and out - and on both the fault is neither the audio path nor the decoder. The whole
 * link stops, on every channel at once, on a fixed cadence, and then resumes. One capture measured
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
 * **Diagnostic only - nothing reads the report.** The [GAP_THRESHOLD_MS] below rests on one
 * observed phone behaviour rather than on anything in the protocol, which is reason enough not to
 * hang recovery off it. The line says what was measured and leaves the conclusion to the reader.
 *
 * Pure and clock-free: the caller passes the time in, so both measured waveforms are replayable in
 * a unit test.
 */
class LinkGapMonitor {

    /**
     * Silence longer than this counts as a gap.
     *
     * The phone sends a `CONTROL Ping Request` once a second for the life of the session, so a full
     * second in which *no* channel says anything is already outside what a working link does. The
     * value matches the offline script's default, which is what makes the two comparable.
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
     * no gaps in it. A healthy session therefore prints nothing at all, which is the property that
     * makes a line worth reading.
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

        val report = if (gapCount == 0) null else Report(
            gaps = gapCount,
            windowMs = elapsedMs,
            deadMs = deadMs,
            longestMs = longestMs,
            medianPeriodMs = medianPeriodMs()
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
        val medianPeriodMs: Long?
    ) {
        /** Dead time as a whole percentage of the window. */
        val deadPercent: Int
            get() = if (windowMs <= 0L) 0 else (deadMs * 100 / windowMs).toInt()

        override fun toString(): String {
            val period = medianPeriodMs?.let { ", period~${it}ms" } ?: ""
            return "inbound link quiet $gaps time${if (gaps == 1) "" else "s"} in ${windowMs}ms: " +
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
    }
}
