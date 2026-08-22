package com.andrerinas.openheadunit.aap

/**
 * How long the outbound half of the session spends blocked inside a write, and how often.
 *
 * The companion question to [LinkGapMonitor]. That one says the phone stopped sending; this one says
 * whether we stopped sending, which on this protocol is not a separate concern: the media channels
 * are flow-controlled by `max_unacked` (twelve frames on video, thirty on each audio sink, both
 * announced at sink setup), so a head unit whose `MediaAck`s stop leaving the socket stalls video and
 * audio together while control traffic, which carries no such limit, runs on untouched.
 *
 * That is exactly the shape of the fault a reporter's captures showed - picture and sound dying in
 * the same window, twelve times out of twelve, with the inbound link never quiet for even one and a
 * half seconds - and nothing in the app could say which end caused it. This is the missing half.
 *
 * A write is measured, not an enqueue: the send handler is a single thread serving one socket, so
 * the time `write()` itself holds is the time the uplink refused to drain.
 *
 * **Diagnostic only - nothing reads the report.** Like the gap monitor, it says what was measured
 * and leaves the conclusion to the reader.
 *
 * Pure and clock-free: the caller passes both the duration and the time, so a measured session is
 * replayable in a unit test.
 */
class UplinkStallMonitor {

    private var started = false
    private var windowStartMs = 0L
    private var stallCount = 0
    private var blockedMs = 0L
    private var longestMs = 0L
    private var writes = 0

    /**
     * Feed one completed write and get a report when a window closes with something to say.
     *
     * [durationMs] is how long the write itself took; [nowMs] is a monotonic clock reading taken
     * after it returned.
     *
     * Returns `null` for every write that does not close a window, and for every window in which no
     * write blocked past [STALL_THRESHOLD_MS]. A session whose uplink drains normally therefore
     * prints nothing at all.
     */
    fun onWrite(durationMs: Long, nowMs: Long): Report? {
        if (!started) {
            started = true
            windowStartMs = nowMs
        }

        writes++
        if (durationMs > STALL_THRESHOLD_MS) {
            stallCount++
            blockedMs += durationMs
            if (durationMs > longestMs) longestMs = durationMs
        }

        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < WINDOW_MS) return null

        val report = if (stallCount == 0) null else Report(
            stalls = stallCount,
            writes = writes,
            windowMs = elapsedMs,
            blockedMs = blockedMs,
            longestMs = longestMs
        )

        windowStartMs = nowMs
        stallCount = 0
        blockedMs = 0L
        longestMs = 0L
        writes = 0
        return report
    }

    /**
     * Forget the previous session, for the same reason [LinkGapMonitor.reset] exists: the transport
     * outlives a session and is re-armed for the next one.
     */
    fun reset() {
        started = false
        windowStartMs = 0L
        stallCount = 0
        blockedMs = 0L
        longestMs = 0L
        writes = 0
    }

    /** One window's worth of blocked uplink. */
    data class Report(
        val stalls: Int,
        val writes: Int,
        val windowMs: Long,
        val blockedMs: Long,
        val longestMs: Long
    ) {
        /** Blocked time as a whole percentage of the window. */
        val blockedPercent: Int
            get() = if (windowMs <= 0L) 0 else (blockedMs * 100 / windowMs).toInt()

        override fun toString(): String =
            "uplink blocked on $stalls write${if (stalls == 1) "" else "s"} of $writes in " +
                "${windowMs}ms: blocked=${blockedMs}ms ($blockedPercent%), longest=${longestMs}ms"
    }

    companion object {
        /**
         * A write slower than this is a stall.
         *
         * The socket carries a 512 KB send buffer and the messages that matter here are acks of a
         * few bytes, so a write that returns in anything but microseconds means the buffer is full
         * and the radio is not draining it. A quarter of a second is far enough above scheduling
         * noise on a loaded single-core head unit to leave no doubt, and far below the multi-second
         * outages this was written to catch.
         */
        const val STALL_THRESHOLD_MS = 250L

        /** Matches [LinkGapMonitor.WINDOW_MS], so the two lines describe the same stretch of time. */
        const val WINDOW_MS = LinkGapMonitor.WINDOW_MS
    }
}
