package com.andrerinas.openheadunit.connection.wifi

/**
 * Main-thread ownership stamp for work which waits for transport teardown. A launcher may
 * be re-armed without changing its instance, so object identity alone is insufficient.
 * Capture before suspending; compare and mutate the launcher in one non-suspending main
 * thread action. A stale completion must not stop the new shared server or network.
 */
internal class WirelessCleanupGeneration {
    private var current = Any()
    private var armedWhileDisconnected: Any? = null
    private var armedDuringAttempt: Any? = null
    private var armedAfterEndOf: Any? = null
    fun capture(): Any = current
    /**
     * Null when [disconnected] ends something an arm already superseded: the terminal that
     * was published at the arm, or the attempt that was still Connecting then. Such an
     * attempt's end is created after the arm, so its object cannot be recorded in advance.
     * An attempt that went on to reach a physical connection replaced the arm instead
     * (its own Connected was observed later), so its end keeps the ordinary policy.
     * An attempt whose terminal was already published at the arm had ended before it,
     * physical or not; teardown republishes that terminal when an Error replaced it.
     */
    fun captureFor(disconnected: Any, attempt: Any? = null, reachedPhysical: Boolean = true): Any? =
        if (armedWhileDisconnected === disconnected ||
            (attempt != null && attempt === armedAfterEndOf) ||
            (attempt != null && attempt === armedDuringAttempt && !reachedPhysical)) null else current
    fun advance(disconnected: Any? = null, attempt: Any? = null, endedAttempt: Any? = null) {
        current = Any()
        armedWhileDisconnected = disconnected
        armedDuringAttempt = attempt
        armedAfterEndOf = endedAttempt
    }
    fun isCurrent(ticket: Any): Boolean = current === ticket
}
