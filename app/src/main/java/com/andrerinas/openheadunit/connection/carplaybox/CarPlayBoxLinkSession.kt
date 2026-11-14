package com.andrerinas.openheadunit.connection.carplaybox

/**
 * The box link as a reducer: events and a clock in, state and actions out. Silence from the box
 * ends nothing, because no source names a periodic box message; only a closed link, an unplug of
 * the box or the user ends it. Times are `elapsedRealtime` milliseconds.
 */
object CarPlayBoxLinkSession {

    const val HEARTBEAT_MS = 2000L
    const val OPEN_RETRY_MS = 1000L
    const val KEYFRAME_MIN_MS = 1000L
    const val NO_SCREEN_MS = 16000L

    const val BOX_CMD_MIC_ON = 7
    const val BOX_CMD_MIC_OFF = 8

    private const val NEVER = Long.MIN_VALUE / 2

    sealed class Phase {
        object Idle : Phase()
        object Opening : Phase()
        data class Linked(val phoneConnected: Boolean) : Phase()
        data class Ended(val reason: String) : Phase()
    }

    data class State(
        val phase: Phase = Phase.Idle,
        val nextHeartbeatAt: Long = 0,
        val nextOpenAt: Long = 0,
        val echoedAt: Long = NEVER,
        val surfaceReady: Boolean = false,
        val lastKeyframeAt: Long = NEVER,
    )

    sealed class Event {
        object Started : Event()
        object Tick : Event()
        object OpenEchoed : Event()
        data class VersionSeen(val version: String) : Event()
        object PhoneConnected : Event()
        object PhoneGone : Event()
        object FrameSeen : Event()
        object SurfaceReady : Event()
        object DecoderAskedKeyframe : Event()
        data class BoxCommand(val n: Int) : Event()
        data class StreamEnded(val reason: String) : Event()
        object UserExit : Event()
    }

    sealed class Action {
        object SendHeartBeat : Action()
        /** SendFile for the DPI, then Command 7, then Command 23. */
        object SendInit : Action()
        object SendOpen : Action()
        object SendKeyframeRequest : Action()
        object MicOn : Action()
        object MicOff : Action()
        data class End(val reason: String) : Action()
        data class Log(val line: String) : Action()
    }

    data class Result(val state: State, val actions: List<Action>)

    fun reduce(state: State, event: Event, nowMs: Long): Result {
        if (state.phase is Phase.Ended) return Result(state, emptyList())
        if (state.phase is Phase.Idle && event != Event.Started) return Result(state, emptyList())
        return when (event) {
            Event.Started -> Result(
                state.copy(
                    phase = Phase.Opening,
                    nextHeartbeatAt = nowMs + HEARTBEAT_MS,
                    nextOpenAt = nowMs + OPEN_RETRY_MS,
                ),
                listOf(Action.SendHeartBeat, Action.SendInit, Action.SendOpen),
            )
            Event.Tick -> tick(state, nowMs)
            Event.OpenEchoed -> {
                if (state.echoedAt != NEVER) Result(state, emptyList())
                else Result(
                    state.copy(phase = Phase.Linked(false), echoedAt = nowMs),
                    listOf(Action.Log("open echoed by the box")),
                )
            }
            is Event.VersionSeen -> Result(state, listOf(Action.Log("box version ${event.version}")))
            Event.PhoneConnected -> Result(
                state.copy(phase = Phase.Linked(true), echoedAt = if (state.echoedAt == NEVER) nowMs else state.echoedAt),
                listOf(Action.Log("phone connected")),
            )
            Event.PhoneGone -> {
                val linked = state.phase as? Phase.Linked
                if (linked == null || !linked.phoneConnected) Result(state, emptyList())
                else Result(state.copy(phase = Phase.Linked(false)), listOf(Action.Log("phone gone")))
            }
            Event.FrameSeen -> Result(state, emptyList())
            Event.SurfaceReady -> keyframe(state.copy(surfaceReady = true), nowMs)
            Event.DecoderAskedKeyframe -> keyframe(state, nowMs)
            is Event.BoxCommand -> when (event.n) {
                BOX_CMD_MIC_ON -> Result(state, listOf(Action.MicOn))
                BOX_CMD_MIC_OFF -> Result(state, listOf(Action.MicOff))
                else -> Result(state, emptyList())
            }
            is Event.StreamEnded -> end(state, event.reason)
            Event.UserExit -> end(state, "user exit")
        }
    }

    private fun tick(state: State, nowMs: Long): Result {
        if (state.echoedAt != NEVER && !state.surfaceReady && nowMs - state.echoedAt >= NO_SCREEN_MS) {
            return end(state, "no screen")
        }
        var next = state
        val actions = ArrayList<Action>()
        if (nowMs >= state.nextHeartbeatAt) {
            actions.add(Action.SendHeartBeat)
            next = next.copy(nextHeartbeatAt = nowMs + HEARTBEAT_MS)
        }
        if (state.echoedAt == NEVER && nowMs >= state.nextOpenAt) {
            actions.add(Action.SendOpen)
            next = next.copy(nextOpenAt = nowMs + OPEN_RETRY_MS)
        }
        return Result(next, actions)
    }

    private fun keyframe(state: State, nowMs: Long): Result {
        if (nowMs - state.lastKeyframeAt < KEYFRAME_MIN_MS) return Result(state, emptyList())
        return Result(state.copy(lastKeyframeAt = nowMs), listOf(Action.SendKeyframeRequest))
    }

    private fun end(state: State, reason: String): Result =
        Result(state.copy(phase = Phase.Ended(reason)), listOf(Action.End(reason)))
}
