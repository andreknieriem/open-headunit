package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.MainActivity.ConnectionUiMode

/**
 * How long an auto-connect attempt may run without the connection advancing, and what its failure
 * is allowed to do to the status pill.
 *
 * An attempt that never ends holds `autoConnectInProgress` for the life of the process, which makes
 * every later attempt a no-op and suppresses anything waiting for a free screen.
 *
 * Pure, so every combination is a unit test rather than a device.
 */
object AutoConnectAttemptPolicy {

    /** Null means the legacy Cancel action; PILL never promotes itself back to an overlay. */
    fun modeAfterOverlayDismiss(serverP2p: Boolean): ConnectionUiMode? =
        if (serverP2p) ConnectionUiMode.PILL else null

    /** USB opens and AOA switches fail silently, so the overlay needs a hard bound of its own. */
    const val OVERLAY_WATCHDOG_MS = 30_000L

    /**
     * Three wake passes. The poke holds a phone for 30 s and waits 15 s, so a phone that is going
     * to answer has answered well inside this; past it the attempt is not going to be answered.
     */
    const val PILL_WATCHDOG_MS = 150_000L

    /** How long [mode] may run before the attempt is declared unanswered. */
    fun watchdogMs(mode: ConnectionUiMode): Long = when (mode) {
        ConnectionUiMode.OVERLAY -> OVERLAY_WATCHDOG_MS
        ConnectionUiMode.PILL,
        ConnectionUiMode.PILL_THEN_OVERLAY -> PILL_WATCHDOG_MS
    }

    /**
     * When an attempt begun at [startedAtElapsedMs] under [mode] runs out.
     *
     * Fixed once at the start rather than re-derived: a PILL_THEN_OVERLAY attempt becomes OVERLAY
     * the moment the phone answers, and re-deriving there would shorten a bound already past.
     */
    fun deadlineAt(mode: ConnectionUiMode, startedAtElapsedMs: Long): Long =
        startedAtElapsedMs + watchdogMs(mode)

    /**
     * How far [deadlineElapsedMs] still is, clamped at zero.
     *
     * Both clocks are elapsed realtime, never uptime: a coroutine delay on the main thread is a
     * postDelayed, and that clock stops while a head unit sleeps with its screen off.
     */
    fun remainingMs(deadlineElapsedMs: Long, nowElapsedMs: Long): Long =
        (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    /** Whether the attempt's bound has passed. */
    fun hasExpired(deadlineElapsedMs: Long, nowElapsedMs: Long): Boolean =
        remainingMs(deadlineElapsedMs, nowElapsedMs) == 0L

    /**
     * Whether a failed attempt may drop the pill back to its opening step.
     *
     * Only the overlay's, because only there did the user's own attempt end. The pill is driven by
     * the connection stack, which is still running and still reporting; rewinding it to ARMED
     * would claim a bring-up had restarted when nothing had.
     */
    fun resetsStageOnFailure(mode: ConnectionUiMode): Boolean = mode == ConnectionUiMode.OVERLAY
}
