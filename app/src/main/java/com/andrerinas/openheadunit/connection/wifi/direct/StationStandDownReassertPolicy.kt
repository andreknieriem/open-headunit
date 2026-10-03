package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Decides whether a station that rejoined mid-session should be stood down again.
 *
 * A ROM can rejoin a disabled network, putting the station back on the group's radio. Re-assertion
 * is budgeted per window, and a ROM that spends one is marked contested and not fought again.
 */
object StationStandDownReassertPolicy {
    const val MAX_REASSERTS = 3
    const val MIN_SPACING_MS = 10_000L
    const val BUDGET_WINDOW_MS = 300_000L
    const val RECHECK_DELAY_MS = 1_500L

    sealed interface Decision {
        object Ignore : Decision
        object Reassert : Decision
        data class Defer(val delayMs: Long) : Decision
        object BudgetSpent : Decision
        object Suppressed : Decision
        data class Recheck(val delayMs: Long) : Decision
    }

    // leftSeen: a join is a rejoin only once the station was read gone, since registering a
    // callback replays the network the stand-down is still tearing down.
    fun decide(
        mode: StationStandDownMode,
        networkId: Int,
        associated: Boolean?,
        leftSeen: Boolean,
        reassertsSoFar: Int,
        windowStartMs: Long,
        nowMs: Long,
        lastReassertAtMs: Long,
        contested: Boolean,
        isRecheck: Boolean = false,
    ): Decision = when {
        networkId < 0 -> Decision.Ignore
        mode == StationStandDownMode.NEVER -> Decision.Ignore
        !leftSeen -> Decision.Ignore
        // A join read before the supplicant completes gets one more read, never a chain of them.
        associated != true -> if (isRecheck) Decision.Ignore else Decision.Recheck(RECHECK_DELAY_MS)
        contested -> Decision.Suppressed
        reassertsSoFar >= MAX_REASSERTS && !opensWindow(windowStartMs, nowMs) -> Decision.BudgetSpent
        lastReassertAtMs > 0L && nowMs - lastReassertAtMs < MIN_SPACING_MS ->
            Decision.Defer(MIN_SPACING_MS - (nowMs - lastReassertAtMs))
        else -> Decision.Reassert
    }

    /** Whether the verdict stored for a ROM fingerprint is in force on [current]. */
    fun isContested(stored: String?, current: String): Boolean =
        !stored.isNullOrBlank() && current.isNotBlank() && stored == current

    /** A changed stand-down mode retires the verdict; re-saving the same one keeps it. */
    fun modeChangeClearsVerdict(oldMode: Int, newMode: Int): Boolean = oldMode != newMode

    /** A stand-down that held through a live session disproves the home-WiFi banner. */
    fun retiresRejoinIssue(sessionWentLive: Boolean, platformWon: Boolean): Boolean =
        sessionWentLive && !platformWon

    /** Whether a re-assertion at [nowMs] starts a fresh budget window. */
    fun opensWindow(windowStartMs: Long, nowMs: Long): Boolean =
        windowStartMs <= 0L || nowMs - windowStartMs >= BUDGET_WINDOW_MS

    // Any reader may latch it (settle poll, read-back, disconnect events), since the leave can
    // outlast one fixed read and the rejoin can come before it.
    fun latchesLeft(standDownInForce: Boolean, associated: Boolean?): Boolean =
        standDownInForce && associated == false

    /** WifiConfiguration.Status: 0 current, 1 disabled, 2 enabled. */
    fun describeConfigStatus(status: Int?): String = when (status) {
        0 -> "current"
        1 -> "disabled"
        2 -> "enabled"
        else -> "unreadable"
    }

    fun describeLock(msSinceLock: Long?): String =
        if (msSinceLock == null) "WifiLock not held" else "WifiLock held for ${msSinceLock}ms"
}
