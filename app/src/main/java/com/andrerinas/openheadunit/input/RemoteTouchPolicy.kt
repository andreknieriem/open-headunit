package com.andrerinas.openheadunit.input

import kotlin.math.hypot

/**
 * Decides what the phone sees of a local touch. A first finger that may start a gesture is withheld
 * until it lifts, moves, outlasts [HOLD_MS] or a second finger lands. A recognised or undecided
 * two-finger touch sends nothing, because a release over a large control reads as a tap.
 * No CANCEL: type 3 reads as DOWN.
 */
class RemoteTouchPolicy {
    enum class Action { DOWN, MOVE, UP, POINTER_DOWN, POINTER_UP }
    enum class Local { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    data class Pointer(val id: Int, val x: Float, val y: Float)
    data class Send(val action: Action, val actionIndex: Int, val pointers: List<Pointer>)

    private enum class Phase { IDLE, HOLDING, FORWARDING, DROPPED }

    private var phase = Phase.IDLE
    private var down = Pointer(-1, 0f, 0f)

    // Fingers the phone has not seen, and fingers it has, each in landing order at their last point.
    private val withheld = LinkedHashMap<Int, Pointer>()
    private val held = LinkedHashMap<Int, Pointer>()

    /** True while only a withheld first finger is down: the one state a gesture can start from. */
    val mayStartGesture get() = phase == Phase.HOLDING && withheld.size == 1

    fun onEvent(
        local: Local,
        actionIndex: Int,
        pointers: List<Pointer>,
        mayStart: Boolean,
        undecided: Boolean,
        touchSlop: Float
    ): List<Send> {
        if (local == Local.DOWN) reset()
        val acting = pointers.getOrNull(actionIndex)
        return when (phase) {
            Phase.IDLE -> if (local == Local.DOWN && acting != null) start(acting, mayStart) else emptyList()
            Phase.HOLDING -> holding(local, acting, pointers, undecided, touchSlop)
            Phase.FORWARDING -> forwarding(local, acting, pointers)
            Phase.DROPPED -> emptyList()
        }
    }

    /** The hold window ended with the first finger alone: the phone gets it at its down point. */
    fun holdExpired(): List<Send> {
        if (!mayStartGesture) return emptyList()
        withheld.clear()
        held[down.id] = down
        phase = Phase.FORWARDING
        return listOf(Send(Action.DOWN, 0, listOf(down)))
    }

    /** A gesture that can no longer fire: every withheld finger joins the phone's touch where it is now. */
    fun giveUp(pointers: List<Pointer>): List<Send> {
        if (phase != Phase.HOLDING) return emptyList()
        refresh(withheld, pointers)
        return forwardWithheld()
    }

    /** A recognised gesture: the phone sees nothing of this touch, now or until every finger is up. */
    fun recognise() {
        reset()
        phase = Phase.DROPPED
    }

    private fun start(p: Pointer, mayStart: Boolean): List<Send> {
        down = p
        if (mayStart) {
            withheld[p.id] = p
            phase = Phase.HOLDING
            return emptyList()
        }
        held[p.id] = p
        phase = Phase.FORWARDING
        return listOf(Send(Action.DOWN, 0, listOf(p)))
    }

    private fun holding(local: Local, acting: Pointer?, pointers: List<Pointer>, undecided: Boolean, touchSlop: Float): List<Send> {
        refresh(withheld, pointers)
        return when (local) {
            Local.DOWN -> emptyList()
            Local.MOVE -> {
                val first = withheld[down.id]
                if (!mayStartGesture || first == null ||
                    hypot(first.x - down.x, first.y - down.y) <= touchSlop) return emptyList()
                val sends = holdExpired()
                held[first.id] = first
                sends + Send(Action.MOVE, 0, listOf(first))
            }
            Local.POINTER_DOWN -> {
                val p = acting ?: return emptyList()
                withheld[p.id] = p
                if (undecided) emptyList() else forwardWithheld()
            }
            Local.POINTER_UP -> {
                // Undecided fingers have moved, so forwarding the one left down would tap where it is now.
                recognise()
                emptyList()
            }
            Local.UP -> {
                val p = acting ?: return emptyList()
                val at = if (p.id == down.id) down else p
                reset()
                listOf(Send(Action.DOWN, 0, listOf(at)), Send(Action.UP, 0, listOf(at)))
            }
            Local.CANCEL -> {
                reset()
                emptyList()
            }
        }
    }

    private fun forwarding(local: Local, acting: Pointer?, pointers: List<Pointer>): List<Send> {
        when (local) {
            Local.DOWN -> return emptyList()
            Local.POINTER_DOWN -> {
                val p = acting ?: return emptyList()
                refresh(held, pointers)
                held[p.id] = p
                return listOf(Send(Action.POINTER_DOWN, held.size - 1, snapshot()))
            }
            Local.MOVE -> {
                refresh(held, pointers)
                return listOf(Send(Action.MOVE, 0, snapshot()))
            }
            Local.POINTER_UP, Local.UP -> {
                val p = acting ?: return emptyList()
                if (p.id !in held) return emptyList()
                refresh(held, pointers)
                val send = if (held.size > 1 && local == Local.POINTER_UP) {
                    Send(Action.POINTER_UP, held.keys.indexOf(p.id), snapshot())
                } else {
                    Send(Action.UP, 0, listOf(p))
                }
                held.remove(p.id)
                if (local == Local.UP) reset()
                return listOf(send)
            }
            // The phone never gets a CANCEL, and an UP here would be a tap on whatever is under the finger.
            Local.CANCEL -> {
                reset()
                return emptyList()
            }
        }
    }

    private fun forwardWithheld(): List<Send> {
        val sends = ArrayList<Send>()
        for (p in withheld.values) {
            held[p.id] = p
            sends += if (held.size == 1) Send(Action.DOWN, 0, listOf(p)) else Send(Action.POINTER_DOWN, held.size - 1, snapshot())
        }
        withheld.clear()
        phase = if (held.isEmpty()) Phase.IDLE else Phase.FORWARDING
        return sends
    }

    private fun reset() {
        withheld.clear()
        held.clear()
        phase = Phase.IDLE
    }

    private fun refresh(map: LinkedHashMap<Int, Pointer>, pointers: List<Pointer>) {
        for (p in pointers) if (p.id in map) map[p.id] = p
    }

    private fun snapshot() = held.values.toList()

    companion object {
        /** Well below the long-press timeout, so a one-finger long press on the rail still works. */
        const val HOLD_MS = 150L

        fun describe(send: Send): String =
            "Remote touch: action=${send.action} count=${send.pointers.size} ids=${send.pointers.joinToString(",") { it.id.toString() }}"
    }
}
