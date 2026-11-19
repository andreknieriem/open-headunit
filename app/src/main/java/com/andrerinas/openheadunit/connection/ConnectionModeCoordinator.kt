package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.ConnectionFallbackPolicy.CandidateMode
import com.andrerinas.openheadunit.connection.ConnectionFallbackPolicy.Preconditions
import com.andrerinas.openheadunit.connection.ConnectionFallbackPolicy.SkipReason
import com.andrerinas.openheadunit.connection.wifi.WirelessRecoveryPolicy
import com.andrerinas.openheadunit.connection.wifi.WirelessRecoveryPolicy.Stage

/**
 * Serialized coordinator for connection-mode fallback.
 *
 * Tracks per-mode attempt counts, failure causes, and skip reasons across the fallback chain
 * defined by [ConnectionFallbackPolicy], and fences scheduled recovery callbacks with both the
 * terminal [CommManager.ConnectionState.Disconnected] identity and the active
 * `WirelessCleanupGeneration` ticket so stale callbacks from an earlier mode or attempt can never
 * launch overlapping connections.
 */
class ConnectionModeCoordinator(
    initialLastSuccessfulMode: CandidateMode? = null,
    private val onPersistLastSuccessfulMode: ((CandidateMode) -> Unit)? = null,
) {

    data class ModeStatus(
        val mode: CandidateMode,
        val eligible: Boolean,
        val skipReason: SkipReason?,
        val attemptsInCycle: Int,
        val lastFailureCause: String?,
    )

    data class DiagnosticsSnapshot(
        val fallbackEnabled: Boolean,
        val currentMode: CandidateMode?,
        val lastSuccessfulMode: CandidateMode?,
        val totalFailuresInCycle: Int,
        val lastTransitionReason: String?,
        val orderedCandidates: List<CandidateMode>,
        val eligibleModes: List<CandidateMode>,
        val skippedModes: Map<CandidateMode, SkipReason>,
        val modeStatuses: List<ModeStatus>,
    )

    data class FallbackStep(
        val fromMode: CandidateMode?,
        val targetMode: CandidateMode,
        val modeChanged: Boolean,
        val attemptInMode: Int,
        val totalAttemptsInCycle: Int,
        val stage: Stage,
        val delayMs: Long,
        val ticket: Any,
        val endedState: Any,
        val failureCause: String?,
        val transitionReason: String,
        val skippedModes: Map<CandidateMode, SkipReason>,
    )

    var lastSuccessfulMode: CandidateMode? = initialLastSuccessfulMode
        private set

    var currentMode: CandidateMode? = initialLastSuccessfulMode
        private set

    var totalFailuresInCycle: Int = 0
        private set

    var lastTransitionReason: String? = null
        private set

    var activeTicket: Any? = null
        private set

    var activeEndedState: Any? = null
        private set

    private var inFlightStep: FallbackStep? = null
    private val attemptsByMode = LinkedHashMap<CandidateMode, Int>()
    private val lastFailureByMode = LinkedHashMap<CandidateMode, String?>()
    private var lastSkippedModes: Map<CandidateMode, SkipReason> = emptyMap()

    fun attemptsFor(mode: CandidateMode): Int = attemptsByMode[mode] ?: 0

    fun lastFailureFor(mode: CandidateMode): String? = lastFailureByMode[mode]

    fun skippedModesSnapshot(): Map<CandidateMode, SkipReason> = lastSkippedModes

    fun isExecutionInFlight(): Boolean = inFlightStep != null

    /**
     * Seeds or updates [lastSuccessfulMode] from persisted settings without clearing an in-progress
     * cycle unless requested.
     */
    fun syncPersistedLastSuccessfulMode(mode: CandidateMode?) {
        if (mode != null && lastSuccessfulMode == null) {
            lastSuccessfulMode = mode
        }
    }

    /**
     * Selects the initial mode to start when arming a connection cycle from scratch.
     */
    fun selectInitialMode(preconditions: Preconditions): CandidateMode? {
        val effectivePreconditions = preconditions.copy(
            lastSuccessfulMode = preconditions.lastSuccessfulMode ?: lastSuccessfulMode
        )
        val evaluation = ConnectionFallbackPolicy.evaluateChain(effectivePreconditions)
        lastSkippedModes = evaluation.skippedModes
        val chosen = evaluation.eligibleModes.firstOrNull()
        if (chosen != null) {
            currentMode = chosen
            lastTransitionReason = if (evaluation.lastSuccessfulPromoted) {
                "Selected last successful mode ${chosen.displayName}"
            } else {
                "Selected highest-priority eligible mode ${chosen.displayName}"
            }
        }
        return chosen
    }

    /**
     * Called when a session reaches `HandshakeComplete`, `TransportStarted`, or ends having
     * `reachedSsl = true`. Promotes [mode] to [lastSuccessfulMode] and resets failure counters.
     */
    fun onSessionHealthy(mode: CandidateMode?) {
        if (mode != null) {
            val changed = lastSuccessfulMode != mode
            lastSuccessfulMode = mode
            currentMode = mode
            if (changed) {
                onPersistLastSuccessfulMode?.invoke(mode)
            }
        }
        resetCycle()
    }

    fun onHandshakeComplete(mode: CandidateMode? = currentMode) {
        onSessionHealthy(mode)
    }

    fun onTransportStarted(mode: CandidateMode? = currentMode) {
        onSessionHealthy(mode)
    }

    /**
     * Resets per-cycle failure counts and cancels any active timer ownership token while
     * preserving [lastSuccessfulMode].
     */
    fun resetCycle() {
        totalFailuresInCycle = 0
        attemptsByMode.clear()
        lastFailureByMode.clear()
        activeTicket = null
        activeEndedState = null
        inFlightStep = null
    }

    /**
     * Evaluates a disconnect event and returns the next serialized [FallbackStep], or `null` if
     * recovery is suppressed or no eligible mode exists.
     */
    fun onDisconnected(
        endedState: Any,
        ticket: Any?,
        preconditions: Preconditions,
        failedMode: CandidateMode? = currentMode,
        isUserExit: Boolean = false,
        holdsRecovery: Boolean = false,
        isSettingsRestart: Boolean = false,
        cancelledByUser: Boolean = false,
        wirelessPausedForSettings: Boolean = false,
        reachedSsl: Boolean = false,
        failureCause: String? = null,
        silentPeerFailures: Int = 0,
    ): FallbackStep? {
        if (WirelessRecoveryPolicy.shouldSuppress(
                isUserExit = isUserExit,
                holdsRecovery = holdsRecovery,
                isSettingsRestart = isSettingsRestart,
                cancelledByUser = cancelledByUser,
                wirelessPausedForSettings = wirelessPausedForSettings,
            )
        ) {
            resetCycle()
            return null
        }
        if (ticket == null) return null

        // A session that reached SSL was healthy before it dropped; promote its mode and start
        // recovery from a clean cycle with that mode at priority #1.
        if (reachedSsl) {
            onSessionHealthy(failedMode ?: currentMode)
        }

        val effectiveLastSuccessful = preconditions.lastSuccessfulMode ?: lastSuccessfulMode
        val effectivePreconditions = preconditions.copy(lastSuccessfulMode = effectiveLastSuccessful)
        val evaluation = ConnectionFallbackPolicy.evaluateChain(effectivePreconditions)
        lastSkippedModes = evaluation.skippedModes

        val eligible = evaluation.eligibleModes
        if (eligible.isEmpty()) {
            lastTransitionReason = "No eligible connection modes available (${evaluation.skippedModes.entries.joinToString { "${it.key.displayName}: ${it.value.description}" }})"
            activeTicket = null
            activeEndedState = null
            return null
        }

        val originMode = failedMode ?: currentMode ?: eligible.first()
        if (!reachedSsl) {
            lastFailureByMode[originMode] = failureCause
        }

        val maxPerMode = effectivePreconditions.maxAttemptsPerMode.coerceAtLeast(1)
        val priorAttemptsInOrigin = attemptsByMode[originMode] ?: 0
        val recordedOriginAttempts = if (reachedSsl) 0 else priorAttemptsInOrigin + 1
        if (!reachedSsl) {
            attemptsByMode[originMode] = recordedOriginAttempts
        }

        val originEligible = originMode in eligible
        val escalateEarly = !reachedSsl && ConnectionFallbackPolicy.shouldEscalateEarly(
            mode = originMode,
            failureCause = failureCause,
            silentPeerFailures = silentPeerFailures,
        )
        val budgetSpent = !reachedSsl && recordedOriginAttempts >= maxPerMode

        val targetMode: CandidateMode
        val attemptInTarget: Int
        val modeChanged: Boolean
        val reason: String

        if (!effectivePreconditions.fallbackEnabled) {
            targetMode = if (originEligible) originMode else eligible.first()
            modeChanged = targetMode != originMode
            attemptInTarget = (attemptsByMode[targetMode] ?: 0).coerceAtLeast(1)
            reason = "Fallback disabled; retrying ${targetMode.displayName} (attempt $attemptInTarget)"
        } else if (reachedSsl && originEligible) {
            // First retry after a healthy session drop stays on the last successful mode.
            targetMode = originMode
            modeChanged = false
            attemptsByMode[targetMode] = 1
            attemptInTarget = 1
            reason = "Retrying last successful mode ${targetMode.displayName} after mid-session drop"
        } else if (!originEligible || escalateEarly || budgetSpent) {
            targetMode = nextEligibleModeAfter(originMode, evaluation.orderedCandidates, eligible)
            modeChanged = targetMode != originMode
            if (modeChanged) {
                attemptsByMode[targetMode] = 1
                attemptInTarget = 1
            } else {
                // Only one mode is eligible in the entire chain; continue retrying it.
                attemptInTarget = recordedOriginAttempts.coerceAtLeast(1)
            }
            val trigger = when {
                !originEligible -> {
                    val skip = evaluation.skippedModes[originMode]?.description ?: "preconditions not met"
                    "${originMode.displayName} is no longer eligible ($skip)"
                }
                escalateEarly -> "${originMode.displayName} peer is unresponsive ($silentPeerFailures silent failures)"
                else -> "${originMode.displayName} exhausted its $maxPerMode-attempt budget (last cause=${failureCause ?: "none"})"
            }
            reason = if (modeChanged) {
                "$trigger; falling back to ${targetMode.displayName}"
            } else {
                "$trigger; no other mode eligible, continuing with ${targetMode.displayName}"
            }
        } else {
            targetMode = originMode
            modeChanged = false
            attemptInTarget = recordedOriginAttempts.coerceAtLeast(1)
            reason = "Retrying ${targetMode.displayName} (attempt $attemptInTarget of $maxPerMode, last cause=${failureCause ?: "none"})"
        }

        val total = ++totalFailuresInCycle
        // Stage and delay: when switching to a fresh fallback mode for the first time in a cycle,
        // use FAST_RETRY delay so fallback transitions happen promptly; when cycling or repeating
        // within a mode, scale with wireless recovery stages.
        val stageAttempt = if (modeChanged && total <= eligible.size * maxPerMode) {
            attemptInTarget
        } else {
            total
        }
        val stage = WirelessRecoveryPolicy.stageForAttempt(stageAttempt)
        val delayMs = WirelessRecoveryPolicy.delayMsForAttempt(
            attempt = stageAttempt,
            failureCause = if (modeChanged) null else failureCause,
            silentPeerFailures = if (modeChanged) 0 else silentPeerFailures,
        )

        currentMode = targetMode
        lastTransitionReason = reason
        activeTicket = ticket
        activeEndedState = endedState

        return FallbackStep(
            fromMode = originMode,
            targetMode = targetMode,
            modeChanged = modeChanged,
            attemptInMode = attemptInTarget,
            totalAttemptsInCycle = total,
            stage = stage,
            delayMs = delayMs,
            ticket = ticket,
            endedState = endedState,
            failureCause = failureCause,
            transitionReason = reason,
            skippedModes = evaluation.skippedModes,
        )
    }

    private fun nextEligibleModeAfter(
        originMode: CandidateMode,
        orderedCandidates: List<CandidateMode>,
        eligibleModes: List<CandidateMode>,
    ): CandidateMode {
        val originIndex = orderedCandidates.indexOf(originMode)
        if (originIndex < 0) return eligibleModes.first()
        for (offset in 1..orderedCandidates.size) {
            val candidate = orderedCandidates[(originIndex + offset) % orderedCandidates.size]
            if (candidate in eligibleModes) {
                return candidate
            }
        }
        return eligibleModes.first()
    }

    /**
     * Checks whether [step] is still the current serialized recovery action and no connection
     * attempt is currently in flight.
     */
    fun canExecute(
        step: FallbackStep,
        currentState: Any,
        isTicketCurrent: (Any) -> Boolean,
        isBusy: Boolean = false,
        cancelledByUser: Boolean = false,
        wirelessPausedForSettings: Boolean = false,
    ): Boolean {
        if (isBusy || inFlightStep != null) return false
        if (cancelledByUser || wirelessPausedForSettings) return false
        if (activeTicket !== step.ticket || activeEndedState !== step.endedState) return false
        if (currentState !== step.endedState) return false
        return isTicketCurrent(step.ticket)
    }

    /**
     * Runs [action] exclusively for [step] if [canExecute] holds, preventing overlapping
     * executions until [action] finishes.
     */
    inline fun executeSerialized(
        step: FallbackStep,
        currentState: Any,
        noinline isTicketCurrent: (Any) -> Boolean,
        isBusy: Boolean = false,
        cancelledByUser: Boolean = false,
        wirelessPausedForSettings: Boolean = false,
        action: (FallbackStep) -> Unit,
    ): Boolean {
        if (!tryBeginExecution(
                step = step,
                currentState = currentState,
                isTicketCurrent = isTicketCurrent,
                isBusy = isBusy,
                cancelledByUser = cancelledByUser,
                wirelessPausedForSettings = wirelessPausedForSettings,
            )
        ) {
            return false
        }
        return try {
            action(step)
            true
        } finally {
            finishExecution(step)
        }
    }

    @PublishedApi
    internal fun tryBeginExecution(
        step: FallbackStep,
        currentState: Any,
        isTicketCurrent: (Any) -> Boolean,
        isBusy: Boolean,
        cancelledByUser: Boolean,
        wirelessPausedForSettings: Boolean,
    ): Boolean {
        if (!canExecute(
                step = step,
                currentState = currentState,
                isTicketCurrent = isTicketCurrent,
                isBusy = isBusy,
                cancelledByUser = cancelledByUser,
                wirelessPausedForSettings = wirelessPausedForSettings,
            )
        ) {
            return false
        }
        inFlightStep = step
        return true
    }

    @PublishedApi
    internal fun finishExecution(step: FallbackStep) {
        if (inFlightStep === step) {
            inFlightStep = null
        }
    }

    fun diagnosticsSnapshot(preconditions: Preconditions): DiagnosticsSnapshot {
        val effective = preconditions.copy(
            lastSuccessfulMode = preconditions.lastSuccessfulMode ?: lastSuccessfulMode
        )
        val evaluation = ConnectionFallbackPolicy.evaluateChain(effective)
        val statuses = evaluation.orderedCandidates.map { mode ->
            val skip = evaluation.skippedModes[mode]
            ModeStatus(
                mode = mode,
                eligible = skip == null,
                skipReason = skip,
                attemptsInCycle = attemptsByMode[mode] ?: 0,
                lastFailureCause = lastFailureByMode[mode],
            )
        }
        return DiagnosticsSnapshot(
            fallbackEnabled = effective.fallbackEnabled,
            currentMode = currentMode,
            lastSuccessfulMode = effective.lastSuccessfulMode,
            totalFailuresInCycle = totalFailuresInCycle,
            lastTransitionReason = lastTransitionReason,
            orderedCandidates = evaluation.orderedCandidates,
            eligibleModes = evaluation.eligibleModes,
            skippedModes = evaluation.skippedModes,
            modeStatuses = statuses,
        )
    }
}
