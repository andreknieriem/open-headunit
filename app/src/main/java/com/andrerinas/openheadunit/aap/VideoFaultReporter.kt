package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The log an active [VideoFaultInjector] owes its reader.
 *
 * Three lines, all of them load on a hardware round: the announcement that the stream is being
 * broken on purpose, the per-fault line, and a periodic summary. A fourth, the budget-spent line,
 * marks the moment the injector stops - which is where a recovery measurement is timed from.
 *
 * This exists as its own class because the injector is now applied at two stages
 * ([VideoFaultInjector.Stage]) and both need identical lines. Two copies would be two chances for
 * the wording to drift, and every brief and results document on this thread greps these strings:
 * `FAULT INJECTED`, `fault injection - `, `fault injection budget spent after`. They are an
 * interface, not prose, and changing one silently invalidates the rounds that quoted it.
 *
 * [prefix] names the site, so a capture says where a fault was injected as well as that it was.
 */
class VideoFaultReporter(private val prefix: String) {

    /** Stamped at construction so the first summary is one interval in, not on the first message. */
    private var lastSummaryMs = SystemClock.elapsedRealtime()

    /** The budget-spent line is a moment, not a state, so it is said once. */
    private var reportedBudgetSpent = false

    /**
     * Says the injector is on, at construction of the injector rather than on its first fault.
     *
     * A run that injects nothing is the interesting failure, and it has to be distinguishable from a
     * run where the setting never took. This line is the half of that answer that does not depend on
     * the stream fragmenting at all.
     */
    fun announce(injector: VideoFaultInjector) {
        AppLog.w(
            "$prefix: FAULT INJECTION IS ON - %s. The video stream is being corrupted on purpose; " +
                "this log does not show a real fault.",
            injector.describe()
        )
    }

    /**
     * Reports one injected fault, then the summary lines that give it context.
     *
     * Called for every message the injector was asked about, faulted or not, because the summary and
     * the budget-spent line are about the stream going by rather than about any one message.
     */
    fun onMessage(injector: VideoFaultInjector, effect: VideoFaultInjector.Effect, flags: Int, len: Int) {
        if (effect != VideoFaultInjector.Effect.NONE) {
            AppLog.w(
                "$prefix: FAULT INJECTED (#%d of %d candidates): %s on flag %d, len=%d",
                injector.injectedCount, injector.matchingCount, effect, flags, len
            )
        }
        reportBudgetSpentOnce(injector)
        reportProgress(injector)
    }

    /**
     * Marks the moment the injector stops breaking the stream.
     *
     * A bounded run is two measurements and this line is the boundary: everything before it is the
     * damage, everything after it is how long the picture took to come back. It lands immediately
     * after the last `FAULT INJECTED` line and exists to say that that one *was* the last - which a
     * reader counting faults against a setting cannot otherwise know until the capture ends.
     */
    private fun reportBudgetSpentOnce(injector: VideoFaultInjector) {
        if (reportedBudgetSpent || !injector.budgetSpent) return
        reportedBudgetSpent = true
        AppLog.w(
            "$prefix: fault injection budget spent after %d faults - the stream is clean from here",
            injector.injectedCount
        )
    }

    /**
     * Says periodically what the injector has had to work with, so a run that injects nothing says
     * so rather than looking like a run where the setting never took.
     *
     * See [VideoFaultInjector.describe] for why the candidate count matters as much as the fault
     * count: how often a frame fragments at all is a property of what the phone is projecting, and
     * two rounds at the same rate have differed by a factor of forty-five because of it.
     */
    private fun reportProgress(injector: VideoFaultInjector) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSummaryMs < VideoFaultInjector.SUMMARY_INTERVAL_MS) return
        lastSummaryMs = now
        AppLog.w("$prefix: fault injection - %s", injector.describe())
    }
}
