package com.andrerinas.openheadunit.connection.wifi.modes.helper

/** Serializes tunnel publication with retirement; endpoint IDs alone can be reused on reconnect. */
internal class NearbyAttemptGuard {
    class Attempt internal constructor(val endpoint: String)

    private var current: Attempt? = null

    @Synchronized fun <T> locked(action: () -> T): T = action()

    @Synchronized fun begin(endpoint: String): Attempt = Attempt(endpoint).also { current = it }

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
