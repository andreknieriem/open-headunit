package com.andrerinas.openheadunit.connection.wifi.modes.helper

/** Serializes tunnel publication with retirement; endpoint IDs alone can be reused on reconnect. */
internal class NearbyAttemptGuard {
    class Attempt internal constructor(val endpoint: String, val ticket: Long)

    private var current: Attempt? = null

    @Synchronized fun <T> locked(action: () -> T): T = action()

    @Synchronized fun begin(endpoint: String, ticket: Long): Attempt = Attempt(endpoint, ticket).also { current = it }

    @Synchronized fun current(): Attempt? = current

    /** The action must not suspend: ownership must cover the actual resource publication. */
    @Synchronized fun run(attempt: Attempt, endpoint: String = attempt.endpoint, action: () -> Unit): Boolean {
        if (current !== attempt || attempt.endpoint != endpoint) return false
        action()
        return true
    }

    @Synchronized fun retire(cleanup: (Attempt?) -> Unit) {
        val previous = current
        current = null
        cleanup(previous)
    }
}

/**
 * Endpoint-ID SDK calls in the order their state changes were made. Nearby silently ends a pending
 * request on disconnectFromEndpoint, so a retired attempt's cleanup must never overtake a newer
 * request for the same endpoint -- including one from the manager that replaced its launcher, since
 * every ConnectionsClient drives the same process-wide Nearby connections.
 *
 * Lock order: a caller may reserve while holding its [NearbyAttemptGuard]; this monitor never
 * acquires another lock and is never held while a call runs.
 */
internal object NearbyEndpointCalls {
    private val queues = HashMap<String, ArrayDeque<() -> Unit>>()

    /** Queue [call] for [endpoint]. True means the caller opened the queue and must [drain] it. */
    @Synchronized fun reserve(endpoint: String, call: () -> Unit): Boolean {
        queues[endpoint]?.let { it.addLast(call); return false }
        queues[endpoint] = ArrayDeque<() -> Unit>().apply { addLast(call) }
        return true
    }

    /** Runs the queue, including calls reserved meanwhile, without holding this monitor. */
    fun drain(endpoint: String) {
        var failure: Throwable? = null
        while (true) {
            val call = synchronized(this) {
                queues.getValue(endpoint).removeFirstOrNull() ?: null.also { queues.remove(endpoint) }
            } ?: break
            try { call() } catch (e: Throwable) { failure?.addSuppressed(e) ?: run { failure = e } }
        }
        failure?.let { throw it }
    }
}
