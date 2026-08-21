package com.andrerinas.openheadunit.connection.wifi.server

/**
 * Whether the TCP server the phone is told to dial should be started, left alone, or rebuilt.
 *
 * The server is one object held in a field, and the bind happens asynchronously inside a coroutine
 * it launches. That split is where the bug lives: a bind that throws, or an accept loop that exits,
 * leaves the object assigned to the field with its listening flag false. The old guard was
 * `if (wirelessServer != null) return`, which reads that as "already running" and returns. Nothing
 * rebinds after that, and the only thing that clears the field is a full mode re-initialisation.
 *
 * What the user sees is the whole of the failure: the head unit hosts its network, wakes the phone
 * over Bluetooth, hands out credentials, and the phone joins and finds nothing on the port. The
 * handshake aborts, the phone is woken again seconds later, and it repeats until someone changes a
 * setting.
 *
 * So "assigned" and "working" have to be separate questions, and the answer to a dead server has to
 * be a rebuild. The rebuild then needs a bound, because the caller is a handshake that retries about
 * every four seconds: without one, a port that cannot be bound at all turns into a rebuild loop that
 * is worse than the stall it replaces.
 */
object WirelessServerRestartPolicy {

    /**
     * How long to wait before another rebuild after one has been attempted.
     *
     * Above the handshake's own retry cadence on purpose. A phone that keeps arriving must not be
     * able to drive one rebuild per arrival; the point of a rebuild is to recover from a transient
     * bind failure, and a transient failure clears on the first or second attempt.
     */
    const val REBUILD_COOLDOWN_MS = 10_000L

    /** Rebuilds allowed in one window before the answer becomes "this is not going to work". */
    const val MAX_REBUILDS_PER_WINDOW = 3

    /** The window those rebuilds are counted in. Cleared whenever a bind succeeds. */
    const val REBUILD_WINDOW_MS = 60_000L

    enum class Action {
        /** Already bound and accepting. Do nothing. */
        NO_OP,

        /** Nothing has been created yet. Create it. */
        START,

        /**
         * An instance exists and its coroutine is still running, but the port is not bound yet.
         *
         * Distinct from [REBUILD] and the distinction is load-bearing: the bind is retried inside
         * the coroutine, so an instance can legitimately be assigned and not listening for a second
         * or two. Reading that as dead would tear down a server that was about to succeed, and the
         * replacement would then race the original's socket for the same port.
         */
        AWAIT,

        /** An instance exists, its coroutine has ended, and it never bound. Replace it. */
        REBUILD,

        /** A rebuild is due but too soon, or too many have been tried. Do nothing this time. */
        BACKOFF,
    }

    /**
     * @param assigned whether the server field currently holds an instance.
     * @param alive whether that instance's coroutine is still running (`job?.isActive`). This is
     *   what separates a bind in progress from one that died, which the listening flag alone
     *   cannot do, and it is the reason the old guard could never self-heal.
     * @param listening whether that instance reports its port bound.
     * @param nowMs monotonic clock, `SystemClock.elapsedRealtime()` at the call site.
     * @param sessionBusy whether a projection session is already up. A live session arrived over
     *   this very socket, so replacing it can only do harm.
     * @param lastRebuildAtMs when a rebuild was last attempted, or 0 if never.
     * @param rebuildsInWindow how many rebuilds have been attempted since the window opened.
     * @param windowStartedAtMs when the current window opened, or 0 if none is open.
     */
    fun decide(
        assigned: Boolean,
        alive: Boolean,
        listening: Boolean,
        nowMs: Long,
        sessionBusy: Boolean = false,
        history: WirelessServerHistory,
    ): Action {
        if (assigned && listening) return Action.NO_OP
        if (!assigned) return Action.START
        if (alive) return Action.AWAIT
        if (sessionBusy) return Action.NO_OP

        // Assigned, not listening, and its coroutine has ended: it is dead and nothing else will
        // revive it. The cooldown is for the port that refuses to bind at all.
        if (history.lastRebuildAtMs > 0L && nowMs - history.lastRebuildAtMs < REBUILD_COOLDOWN_MS) return Action.BACKOFF
        if (windowIsOpen(nowMs, history.rebuildWindowStartedAtMs) && history.rebuildsInWindow >= MAX_REBUILDS_PER_WINDOW) {
            return Action.BACKOFF
        }
        return Action.REBUILD
    }

    /** True while [windowStartedAtMs] is recent enough for its rebuild count to still apply. */
    fun windowIsOpen(nowMs: Long, windowStartedAtMs: Long): Boolean =
        windowStartedAtMs > 0L && nowMs - windowStartedAtMs < REBUILD_WINDOW_MS

    /**
     * The rebuild count to carry forward after an attempt at [nowMs].
     *
     * A window that has aged out restarts at one rather than continuing to climb, so a unit that
     * fails once an hour is never talked out of trying again.
     */
    fun nextRebuildCount(nowMs: Long, windowStartedAtMs: Long, rebuildsInWindow: Int): Int =
        if (windowIsOpen(nowMs, windowStartedAtMs)) rebuildsInWindow + 1 else 1

    /** The window start to carry forward after an attempt at [nowMs]. */
    fun nextWindowStart(nowMs: Long, windowStartedAtMs: Long): Long =
        if (windowIsOpen(nowMs, windowStartedAtMs)) windowStartedAtMs else nowMs

    /**
     * Why a decision came out the way it did, for the log.
     *
     * The reason is the point of this whole change. Before it, a server that was never started and
     * one that was started and silently skipped produced identical logs, which is to say no log at
     * all, and two complete reporter captures could not be told apart.
     */
    fun describe(action: Action, assigned: Boolean, listening: Boolean): String = when (action) {
        Action.NO_OP -> if (listening) "already listening" else "a session is already running on it"
        Action.START -> "no server yet"
        Action.AWAIT -> "a server is starting and has not finished binding its port"
        Action.REBUILD ->
            if (assigned && !listening) "a server exists but its port is not bound" else "restarting"
        Action.BACKOFF -> "the port would not bind on the last attempt, waiting before trying again"
    }
}
