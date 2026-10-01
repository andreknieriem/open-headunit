package com.andrerinas.openheadunit.aap

import java.util.ArrayDeque

/** Bounded microphone window. The dispatch callback only queues work; it must not do socket I/O. */
internal class MicFlowControl<T>(private val dispatch: (Long, T) -> Unit) {
    enum class Offer { ACCEPTED, CLOSED, OVERFLOW }
    private val pending = ArrayDeque<T>()
    private var generation = 0L
    private var session = 0
    private var window = 0
    private var inFlight = 0
    private var open = false
    private var ready = false

    @Synchronized fun begin(sessionId: Int, maxUnacked: Int): Long {
        require(maxUnacked in 1..4096)
        generation++
        session = sessionId
        window = maxUnacked
        inFlight = 0
        pending.clear()
        open = true
        ready = false // The successful MicrophoneResponse must be queued before any DATA.
        return generation
    }

    @Synchronized fun activate(token: Long) {
        if (!isCurrent(token)) return
        ready = true
        pump()
    }

    @Synchronized fun offer(frame: T): Offer {
        if (!open) return Offer.CLOSED
        if (pending.size >= MAX_PENDING_FRAMES) return Offer.OVERFLOW
        pending.addLast(frame)
        pump()
        return Offer.ACCEPTED
    }

    @Synchronized fun acknowledge(sessionId: Int, count: Int): Boolean {
        if (!open || sessionId != session || count <= 0 || count > inFlight) return false
        inFlight -= count
        pump()
        return true
    }

    @Synchronized fun isCurrent(token: Long): Boolean = open && token == generation
    @Synchronized fun isOpen(): Boolean = open

    @Synchronized fun close(): List<T> {
        open = false
        ready = false
        generation++
        inFlight = 0
        return pending.toList().also { pending.clear() }
    }

    private fun pump() {
        while (open && ready && inFlight < window && pending.isNotEmpty()) {
            inFlight++ // Reserve before enqueueing; an ACK cannot grant more than was sent.
            dispatch(generation, pending.removeFirst())
        }
    }

    companion object {
        // Four 128 ms capture chunks. A stalled peer ends this mic session instead of growing latency.
        const val MAX_PENDING_FRAMES = 4
    }
}
