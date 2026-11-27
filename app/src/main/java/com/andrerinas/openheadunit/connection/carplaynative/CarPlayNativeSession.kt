package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy.Attach

/**
 * One native CarPlay session as a reducer: events and an `elapsedRealtime` clock in, state and actions out.
 * It owns the session order and its deadlines: the prepare waits, the first NCM frame, the first 0x4300 after auth,
 * the AirPlay connection, the one 0x4301 resend and the first video. Lockdown, iAP2 and USB keep their own timers.
 */
object CarPlayNativeSession {
    const val REENUMERATION_MS = 10_000L
    const val SECOND_PERMISSION_MS = 30_000L
    const val NCM_FIRST_FRAME_MS = 30_000L
    const val AIRPLAY_FIRST_MS = 2_000L
    const val AIRPLAY_AFTER_RESEND_MS = 10_000L

    /** From auth success to the first 0x4300. Neither reference bounds this wait, so 30 s. */
    const val START_REQUEST_MS = 30_000L

    /** From the AirPlay connection to the first frame; a phone that drops before SETUP ends here. */
    const val AIRPLAY_TO_VIDEO_MS = 15_000L

    private const val NO_DEADLINE = Long.MAX_VALUE
    private const val NEVER = Long.MIN_VALUE / 2

    sealed class Phase {
        object Idle : Phase()
        object AwaitReenumeration : Phase()
        object AwaitSecondPermission : Phase()
        object Opening : Phase()
        object Pairing : Phase()
        object Iap2 : Phase()
        object Authenticating : Phase()
        object StartSent : Phase()
        object NetworkUp : Phase()
        object AirPlay : Phase()
        object Streaming : Phase()
        data class Ended(val reason: String) : Phase()
    }

    data class State(
        val phase: Phase = Phase.Idle,
        val deadlineAt: Long = NO_DEADLINE,
        val modeRequested: Boolean = false,
        val permissionAsked: Boolean = false,
        /** When the first NCM frame came, if it came before the 0x4301 went out. */
        val ncmSeenAt: Long = NEVER,
        val resent: Boolean = false,
        /** AirPlay control connections that ended before SETUP, for the end line. */
        val airplayDrops: Int = 0,
    ) {
        /** True from the moment the arbiter flag may be set. */
        val begun: Boolean get() = phase is Phase.Opening || phase is Phase.Pairing || phase is Phase.Iap2 || phase is Phase.Authenticating ||
            phase is Phase.StartSent || phase is Phase.NetworkUp || phase is Phase.AirPlay || phase is Phase.Streaming
    }

    sealed class Event {
        /** An iPhone attached, or its permission came. [verdict] is [IphoneUsbPolicy.attach] for the device as it is now. */
        data class Attached(val verdict: Attach) : Event()
        object ModeAccepted : Event()
        object ModeRefused : Event()
        object PermissionDenied : Event()
        /** USB, usbmux, NCM, the tunnel and the AirPlay listener are up. */
        object Opened : Event()
        object CarkitOpen : Event()
        object IdentificationAccepted : Event()
        /** MFi auth succeeded: the iPhone may now send 0x4300. */
        object Authenticated : Event()
        data class StartSent(val count: Int) : Event()
        object FirstNcmFrame : Event()
        object AirPlayConnected : Event()
        /** An AirPlay control connection ended before its SETUP. */
        object AirPlayEndedBeforeSetup : Event()
        object Streaming : Event()
        /** The iPhone left the bus. */
        object Detached : Event()
        object UserExit : Event()
        data class Fail(val reason: String) : Event()
        object Tick : Event()
    }

    sealed class Action {
        object AskPermission : Action()
        object RequestMode : Action()
        object BeginSession : Action()
        /** Send the 0x4301 once more. */
        object ResendStart : Action()
        /** [begun] is true when a session ran, so the manager closes it and logs the loss. */
        data class End(val reason: String, val begun: Boolean) : Action()
        /** A verdict that stops the attach. */
        data class Refused(val verdict: Attach) : Action()
        data class Log(val line: String) : Action()
    }

    data class Result(val state: State, val actions: List<Action>)

    fun reduce(state: State, event: Event, nowMs: Long): Result {
        if (state.phase is Phase.Ended) return Result(state, emptyList())
        return when (event) {
            is Event.Attached -> attach(state, event.verdict, nowMs)
            Event.ModeAccepted -> if (state.phase == Phase.Idle && state.modeRequested) {
                Result(
                    state.copy(phase = Phase.AwaitReenumeration, deadlineAt = nowMs + REENUMERATION_MS),
                    listOf(Action.Log("CarPlayNative: requested CarPlay mode")),
                )
            } else ignore(state)
            // A refused mode request or a denied ask stays answered until the iPhone leaves the bus, so a rescan sends nothing.
            Event.ModeRefused -> ignore(state)
            Event.PermissionDenied -> when (state.phase) {
                Phase.AwaitSecondPermission -> end(state, "permission for the CarPlay mode iPhone was denied; not started")
                else -> ignore(state)
            }
            Event.Opened -> advance(state, Phase.Opening, Phase.Pairing)
            Event.CarkitOpen -> advance(state, Phase.Pairing, Phase.Iap2)
            Event.IdentificationAccepted -> advance(state, Phase.Iap2, Phase.Authenticating)
            Event.Authenticated ->
                if (state.phase == Phase.Authenticating) Result(state.copy(deadlineAt = nowMs + START_REQUEST_MS), emptyList()) else ignore(state)
            is Event.StartSent -> startSent(state, nowMs)
            Event.FirstNcmFrame -> firstNcmFrame(state, nowMs)
            Event.AirPlayConnected -> when (state.phase) {
                // A reconnect in AirPlay keeps the first deadline, so a phone that drops and comes back cannot hold the session.
                // In Streaming a new connection replaced the streaming one, so the picture has stopped: the wait starts again.
                Phase.StartSent, Phase.NetworkUp, Phase.Streaming ->
                    Result(state.copy(phase = Phase.AirPlay, deadlineAt = nowMs + AIRPLAY_TO_VIDEO_MS), emptyList())
                else -> ignore(state)
            }
            Event.AirPlayEndedBeforeSetup ->
                if (state.phase == Phase.AirPlay || state.phase == Phase.Streaming) {
                    Result(state.copy(airplayDrops = state.airplayDrops + 1), emptyList())
                } else ignore(state)
            Event.Streaming -> when (state.phase) {
                Phase.StartSent, Phase.NetworkUp, Phase.AirPlay -> Result(state.copy(phase = Phase.Streaming, deadlineAt = NO_DEADLINE), emptyList())
                else -> ignore(state)
            }
            Event.Detached -> when {
                state.phase == Phase.Idle -> Result(state.copy(modeRequested = false, permissionAsked = false), emptyList())
                // The old instance leaves as the iPhone changes mode. Only its new instance counts.
                state.phase == Phase.AwaitReenumeration -> ignore(state)
                else -> end(state, "unplugged")
            }
            Event.UserExit -> if (state.phase == Phase.Idle) ignore(state) else end(state, "user exit")
            is Event.Fail -> end(state, event.reason)
            Event.Tick -> tick(state, nowMs)
        }
    }

    private fun ignore(state: State) = Result(state, emptyList())

    private fun noVideo(drops: Int): String {
        val base = "no video from the iPhone in ${AIRPLAY_TO_VIDEO_MS / 1000} s"
        return if (drops == 0) base else "$base ($drops AirPlay connection${if (drops == 1) "" else "s"} ended before SETUP)"
    }

    /** A session that ran logs its loss when it closes. A prepare phase has nothing to close, so its line is logged here. */
    private fun end(state: State, reason: String): Result {
        val ended = state.copy(phase = Phase.Ended(reason))
        if (state.begun) return Result(ended, listOf(Action.End(reason, begun = true)))
        return Result(ended, listOf(Action.Log("CarPlayNative: $reason"), Action.End(reason, begun = false)))
    }

    private fun advance(state: State, from: Phase, to: Phase): Result =
        if (state.phase == from) Result(state.copy(phase = to), emptyList()) else ignore(state)

    private fun attach(state: State, verdict: Attach, nowMs: Long): Result = when (state.phase) {
        Phase.Idle -> when (verdict) {
            Attach.Open -> Result(state.copy(phase = Phase.Opening), listOf(Action.BeginSession))
            Attach.RequestMode ->
                if (state.modeRequested) ignore(state) else Result(state.copy(modeRequested = true), listOf(Action.RequestMode))
            Attach.AskPermission ->
                if (state.permissionAsked) ignore(state) else Result(state.copy(permissionAsked = true), listOf(Action.AskPermission))
            Attach.NotAnIphone, Attach.Disabled -> ignore(state)
            else -> Result(state, listOf(Action.Refused(verdict)))
        }
        Phase.AwaitReenumeration -> when (verdict) {
            Attach.Open -> Result(state.copy(phase = Phase.Opening, deadlineAt = NO_DEADLINE), listOf(Action.BeginSession))
            Attach.AskPermission -> Result(
                state.copy(phase = Phase.AwaitSecondPermission, deadlineAt = nowMs + SECOND_PERMISSION_MS),
                listOf(Action.AskPermission),
            )
            // Still the old mode, or a refusal: the deadline decides.
            else -> ignore(state)
        }
        Phase.AwaitSecondPermission ->
            if (verdict == Attach.Open) Result(state.copy(phase = Phase.Opening, deadlineAt = NO_DEADLINE), listOf(Action.BeginSession)) else ignore(state)
        else -> ignore(state)
    }

    private fun startSent(state: State, nowMs: Long): Result = when (state.phase) {
        Phase.Opening, Phase.Pairing, Phase.Iap2, Phase.Authenticating ->
            if (state.ncmSeenAt != NEVER) {
                Result(state.copy(phase = Phase.NetworkUp, deadlineAt = nowMs + AIRPLAY_FIRST_MS), emptyList())
            } else {
                Result(state.copy(phase = Phase.StartSent, deadlineAt = nowMs + NCM_FIRST_FRAME_MS), emptyList())
            }
        // A later 0x4301 (a second 0x4300) changes no deadline.
        else -> ignore(state)
    }

    private fun firstNcmFrame(state: State, nowMs: Long): Result = when (state.phase) {
        Phase.StartSent -> Result(state.copy(phase = Phase.NetworkUp, deadlineAt = nowMs + AIRPLAY_FIRST_MS), emptyList())
        Phase.Opening, Phase.Pairing, Phase.Iap2, Phase.Authenticating ->
            if (state.ncmSeenAt == NEVER) Result(state.copy(ncmSeenAt = nowMs), emptyList()) else ignore(state)
        else -> ignore(state)
    }

    private fun tick(state: State, nowMs: Long): Result {
        if (nowMs < state.deadlineAt) return ignore(state)
        return when (state.phase) {
            Phase.AwaitReenumeration -> end(state, "the iPhone did not come back in CarPlay mode in ${REENUMERATION_MS / 1000} s; not started")
            Phase.AwaitSecondPermission -> end(state, "permission for the CarPlay mode iPhone not given in ${SECOND_PERMISSION_MS / 1000} s; not started")
            Phase.Authenticating -> end(state, "no CarPlay start request from the iPhone in ${START_REQUEST_MS / 1000} s")
            Phase.StartSent -> end(state, "no USB network from the iPhone")
            Phase.NetworkUp ->
                if (!state.resent) {
                    Result(state.copy(resent = true, deadlineAt = nowMs + AIRPLAY_AFTER_RESEND_MS), listOf(Action.ResendStart))
                } else end(state, "no AirPlay connection")
            Phase.AirPlay -> end(state, noVideo(state.airplayDrops))
            else -> ignore(state)
        }
    }

    /** A detach of the latched iPhone this soon after its close may be the phone leaving CarPlay mode, not an unplug. */
    const val LATCH_DETACH_AFTER_CLOSE_MS = 10_000L

    /** An attach this soon after that detach is the same phone coming back, so the latch stays. */
    const val LATCH_REATTACH_MS = 5_000L

    /**
     * The user-exit latch. Our close sets the iPhone back to its own configuration, and the phone may answer with a
     * detach and a new attach: that must not reopen a session the user closed. A real unplug clears the latch.
     * Times are `elapsedRealtime` milliseconds.
     */
    data class ExitLatch(
        val held: Boolean = false,
        val deviceId: String? = null,
        val closing: Boolean = false,
        val closedAt: Long = NEVER,
        val detachedAt: Long = NEVER,
        /** The phone already came back once: its next detach is an unplug. */
        val settled: Boolean = false,
    ) {
        /** A user exit of the session on [id]; its close is still to run, or running. */
        fun exit(id: String?): ExitLatch = if (held) this else ExitLatch(held = true, deviceId = id, closing = true)

        fun closed(nowMs: Long): ExitLatch = if (held && closing) copy(closing = false, closedAt = nowMs) else this

        fun onDetach(id: String, nowMs: Long): ExitLatch {
            if (!held || id != deviceId) return this
            val soon = !settled && (closing || nowMs - closedAt <= LATCH_DETACH_AFTER_CLOSE_MS)
            return if (soon) copy(detachedAt = nowMs) else ExitLatch()
        }

        fun onAttach(id: String, nowMs: Long): ExitLatch = when {
            !held || detachedAt == NEVER -> this
            nowMs - detachedAt <= LATCH_REATTACH_MS -> copy(deviceId = id, detachedAt = NEVER, settled = true)
            // The detach was an unplug after all: the phone stayed away longer than a re-enumeration takes.
            else -> ExitLatch()
        }
    }
}
