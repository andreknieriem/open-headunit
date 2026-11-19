package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.UnresponsivePeerPolicy

/**
 * Staged wireless connection recovery policy.
 *
 * Escalates consecutive failed wireless connection attempts or unexpected drops through
 * four bounded stages:
 * 1. [Stage.FAST_RETRY] — quick discovery or wake retry (attempts 1..2).
 * 2. [Stage.LAUNCHER_REARM] — verify/rebuild the wireless server and re-arm the active launcher (attempts 3..4).
 * 3. [Stage.STACK_RECOVERY] — forced re-initialization of the wireless launcher from settings (attempt 5).
 * 4. [Stage.BACKOFF] — bounded long-tail retry cadence (attempts 6+).
 *
 * Resets on [Tracker.onHandshakeComplete] or [Tracker.onTransportStarted] (or when an ended session
 * reports `reachedSsl = true`), and suppresses immediately on user exit, projection-unraised hold,
 * settings restart, status-pill user cancel, or settings-screen pause.
 */
object WirelessRecoveryPolicy {

    const val FAILURE_TCP_CONNECT = "connect_failed"
    const val FAILURE_PEER_SILENT = "peer_silent"
    const val FAILURE_HANDSHAKE = "handshake_failed"
    const val FAILURE_READ_START = "read_failed"
    const val FAILURE_LINK_LOST = "link_lost"
    const val FAILURE_LINK_GOING_DOWN = "link_going_down"

    const val FAST_RETRY_MAX_ATTEMPTS = 2
    const val LAUNCHER_REARM_MAX_ATTEMPTS = 4
    const val STACK_RECOVERY_MAX_ATTEMPTS = 5

    const val BASE_DELAY_MS = 2_000L
    const val FAST_RETRY_SECOND_DELAY_MS = 4_000L
    const val LAUNCHER_REARM_FIRST_DELAY_MS = 8_000L
    const val LAUNCHER_REARM_SECOND_DELAY_MS = 16_000L
    const val STACK_RECOVERY_DELAY_MS = 30_000L
    const val MAX_BACKOFF_DELAY_MS = 60_000L

    enum class Stage {
        FAST_RETRY,
        LAUNCHER_REARM,
        STACK_RECOVERY,
        BACKOFF,
    }

    data class Decision(
        val attempt: Int,
        val stage: Stage,
        val delayMs: Long,
        val ticket: Any,
        val endedState: Any,
        val failureCause: String? = null,
    )

    fun shouldSuppress(
        isUserExit: Boolean = false,
        holdsRecovery: Boolean = false,
        isSettingsRestart: Boolean = false,
        cancelledByUser: Boolean = false,
        wirelessPausedForSettings: Boolean = false,
    ): Boolean =
        isUserExit ||
            holdsRecovery ||
            isSettingsRestart ||
            cancelledByUser ||
            wirelessPausedForSettings

    fun stageForAttempt(attempt: Int): Stage = when {
        attempt <= FAST_RETRY_MAX_ATTEMPTS -> Stage.FAST_RETRY
        attempt <= LAUNCHER_REARM_MAX_ATTEMPTS -> Stage.LAUNCHER_REARM
        attempt <= STACK_RECOVERY_MAX_ATTEMPTS -> Stage.STACK_RECOVERY
        else -> Stage.BACKOFF
    }

    fun delayMsForAttempt(
        attempt: Int,
        failureCause: String? = null,
        silentPeerFailures: Int = 0,
    ): Long {
        val stagedDelay = when {
            attempt <= 1 -> BASE_DELAY_MS
            attempt == 2 -> FAST_RETRY_SECOND_DELAY_MS
            attempt == 3 -> LAUNCHER_REARM_FIRST_DELAY_MS
            attempt == 4 -> LAUNCHER_REARM_SECOND_DELAY_MS
            attempt == 5 -> STACK_RECOVERY_DELAY_MS
            else -> MAX_BACKOFF_DELAY_MS
        }
        if (failureCause == FAILURE_PEER_SILENT &&
            silentPeerFailures >= UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF
        ) {
            return maxOf(stagedDelay, UnresponsivePeerPolicy.rescanDelayMs(silentPeerFailures))
        }
        return stagedDelay
    }

    class Tracker {
        var consecutiveFailures: Int = 0
            private set

        var currentStage: Stage = Stage.FAST_RETRY
            private set

        var activeTicket: Any? = null
            private set

        var activeEndedState: Any? = null
            private set

        fun onHandshakeComplete() {
            reset()
        }

        fun onTransportStarted() {
            reset()
        }

        fun reset() {
            consecutiveFailures = 0
            currentStage = Stage.FAST_RETRY
            activeTicket = null
            activeEndedState = null
        }

        fun onDisconnected(
            endedState: Any,
            ticket: Any?,
            isUserExit: Boolean = false,
            holdsRecovery: Boolean = false,
            isSettingsRestart: Boolean = false,
            cancelledByUser: Boolean = false,
            wirelessPausedForSettings: Boolean = false,
            reachedSsl: Boolean = false,
            failureCause: String? = null,
            silentPeerFailures: Int = 0,
        ): Decision? {
            if (shouldSuppress(
                    isUserExit = isUserExit,
                    holdsRecovery = holdsRecovery,
                    isSettingsRestart = isSettingsRestart,
                    cancelledByUser = cancelledByUser,
                    wirelessPausedForSettings = wirelessPausedForSettings,
                )
            ) {
                reset()
                return null
            }
            if (ticket == null) return null

            if (reachedSsl) {
                consecutiveFailures = 0
            }

            val attempt = ++consecutiveFailures
            val stage = stageForAttempt(attempt)
            val delayMs = delayMsForAttempt(attempt, failureCause, silentPeerFailures)
            currentStage = stage
            activeTicket = ticket
            activeEndedState = endedState
            return Decision(
                attempt = attempt,
                stage = stage,
                delayMs = delayMs,
                ticket = ticket,
                endedState = endedState,
                failureCause = failureCause,
            )
        }

        fun canExecute(
            decision: Decision,
            currentState: Any,
            isTicketCurrent: (Any) -> Boolean,
            cancelledByUser: Boolean = false,
            wirelessPausedForSettings: Boolean = false,
        ): Boolean {
            if (cancelledByUser || wirelessPausedForSettings) return false
            if (activeTicket !== decision.ticket || activeEndedState !== decision.endedState) return false
            if (currentState !== decision.endedState) return false
            return isTicketCurrent(decision.ticket)
        }
    }
}
