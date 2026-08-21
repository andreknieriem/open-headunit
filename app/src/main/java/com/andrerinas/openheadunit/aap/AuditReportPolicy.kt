package com.andrerinas.openheadunit.aap

/**
 * How often one repeating diagnostic line may be printed.
 *
 * Written for a [FragmentedMessageAudit] outcome, which is the case the rest of this describes, and
 * since reused for [AapTransport]'s held-cycle line - a check that repeats every two seconds and can
 * hold for minutes. Callers keep their own counters, so two of them never share a budget.
 *
 * The first version of this was a hard cap: ten lines per outcome per session, then silence. It was
 * meant to stop a broken link filling the log with one line per frame, and on a healthy link it
 * would never have been reached. Hardware reached it in **200 milliseconds** - a
 * scaling bug in the audit produced ten false `DELTA_CHANGED` lines at connect - and the check was
 * then dead for the remaining five minutes, including both windows in which a reporter's artifact
 * was visible on screen. The audit read clean because it had been switched off, and that absence was
 * very nearly written up as evidence.
 *
 * A cap that can be exhausted is a cap that will be exhausted by whichever bug is noisiest, and the
 * quiet fault it was hiding is always the one worth having. So the budget refills: the first
 * [BURST_REPORTS] print in full, and after that one line per [SUMMARY_INTERVAL_MS] carries however
 * many were suppressed since. The log stays bounded at roughly one line a minute per outcome, and
 * something that starts going wrong in minute nine still says so.
 *
 * Pure, so the interaction that failed in the field is testable without a device or a clock: the
 * caller passes the time in.
 */
object AuditReportPolicy {

    /** Reports printed in full before the periodic summary takes over. */
    const val BURST_REPORTS = 10

    /** Smallest gap between reports once the burst is spent. */
    const val SUMMARY_INTERVAL_MS = 60_000L

    /**
     * Whether this occurrence should be printed.
     *
     * [reportsSoFar] counts what has already been printed for this outcome, [lastReportMs] is when
     * the most recent one was, and [nowMs] is a monotonic clock reading - `SystemClock.elapsedRealtime`
     * at the call site. Anything not printed is expected to be counted by the caller and named in
     * the next line that is.
     */
    fun shouldReport(reportsSoFar: Int, lastReportMs: Long, nowMs: Long): Boolean {
        if (reportsSoFar < BURST_REPORTS) return true
        return nowMs - lastReportMs >= SUMMARY_INTERVAL_MS
    }
}
