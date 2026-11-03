package com.andrerinas.openheadunit.aap

/** Subscription identity also owns queued events, so stop/restart cannot replay an old fix. */
internal class GalSensorSubscriptions<T : Any> {
    class Ticket<T> internal constructor(val type: Int, internal val intervalNs: Long) {
        internal var lastSentNs: Long? = null
        internal var pending: T? = null
        internal var scheduled = false
    }
    private var advertised = emptySet<Int>()
    private var fixed = false
    private val active = mutableMapOf<Int, Ticket<T>>()

    @Synchronized fun advertise(types: Set<Int>) {
        check(!fixed)
        advertised = types.toSet()
        fixed = true
    }

    /**
     * AA18 unregister sends -1; zero is unthrottled. Despite OHU's historical field name
     * min_update_period, the sender's delivery limiter refills rate/1000 tokens per ms:
     * positive values act as Hz in that limiter, NOT milliseconds. Use that cadence for
     * our continuous updates, keeping the latest sample for the next slot. This is a receiver
     * scheduling policy, not a claim that every older sender uses that unit.
     */
    @Synchronized fun request(type: Int, rate: Long): Boolean {
        if (type !in advertised || rate < -1) return false
        if (rate == -1L) active.remove(type)
        else active[type] = Ticket<T>(type, if (rate == 0L) 0L else
            1L + (1_000_000_000L - 1L) / rate)
        return true
    }

    data class Queued<T>(val ticket: Ticket<T>, val schedule: Boolean)
    data class Delivery<T>(val value: T? = null, val waitNs: Long = 0)

    @Synchronized fun enqueue(type: Int, value: T): Queued<T>? {
        val ticket = active[type] ?: return null
        ticket.pending = value
        val schedule = !ticket.scheduled
        ticket.scheduled = true
        return Queued(ticket, schedule)
    }

    @Synchronized fun take(ticket: Ticket<T>, nowNs: Long): Delivery<T> {
        if (active[ticket.type] !== ticket) return Delivery()
        val last = ticket.lastSentNs
        val remaining = if (last == null || nowNs < last) 0L else
            (ticket.intervalNs - (nowNs - last)).coerceAtLeast(0)
        if (remaining > 0) return Delivery(waitNs = remaining)
        val value = ticket.pending
        ticket.pending = null
        ticket.scheduled = false
        if (value != null) ticket.lastSentNs = nowNs
        return Delivery(value)
    }

    @Synchronized fun cancelQueued(ticket: Ticket<T>) {
        if (active[ticket.type] === ticket) {
            ticket.pending = null
            ticket.scheduled = false
        }
    }

    @Synchronized fun stopAll() { active.clear() }
    @Synchronized fun activeTypes(): Set<Int> = active.keys.toSet()
}
