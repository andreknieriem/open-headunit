package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Main-ordered ownership of the process-wide P2P group and submitted removals. A retained boundary
 * owns only the framework manager/channel, never the service that happened to preserve the group.
 */
internal class NativeGroupCleanupCoordinator {
    fun interface Removal {
        fun remove(onComplete: (Int?) -> Unit)
    }

    private class Retained(val owner: Any, val removal: Removal)

    private var owner: Any? = null
    private var retained: Retained? = null
    private val pending = mutableMapOf<Any, Any>()
    private val waiting = linkedMapOf<Any, () -> Unit>()

    /** Returns true when the caller must stop here; its continuation runs after actual callbacks. */
    fun deferUntilReady(nextOwner: Any, continuation: () -> Unit): Boolean {
        if (pending.isNotEmpty()) {
            // A refresh must not replace the bring-up already waiting for this same owner.
            if (!waiting.containsKey(nextOwner)) waiting[nextOwner] = continuation
            return true
        }
        owner = nextOwner
        // A failed or unsupported bring-up must not discard responsibility for the survivor.
        retained?.let { if (it.owner !== nextOwner) retained = Retained(nextOwner, it.removal) }
        return false
    }

    fun isOwnerReady(candidate: Any): Boolean = owner === candidate && pending.isEmpty()

    fun isOwner(candidate: Any): Boolean = owner === candidate

    /**
     * Actual adoption/use hands cleanup back to the live manager's transport-ordered stop. Merely
     * starting a bring-up must leave the detached survivor available to a later explicit Stop.
     */
    fun activate(candidate: Any): Boolean {
        if (!isOwnerReady(candidate)) return false
        retained = null
        return true
    }

    fun cancelWaiting(candidate: Any) {
        waiting.remove(candidate)
    }

    fun retain(candidate: Any, removal: Removal) {
        if (owner === candidate) retained = Retained(candidate, removal)
    }

    /** Stop ingress can retire only a detached survivor; active groups await transport retirement. */
    fun requestStop(isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        val group = retained ?: return
        // A submitted removal cannot be recalled by a later Run. It already owns the barrier.
        if (pending.values.any { it === group.owner }) return
        if (!isCurrent()) return
        remove(group.owner, group.removal) {}
    }

    /** Every removal, not only final cleanup, participates in the cross-service barrier. */
    fun remove(candidate: Any, removal: Removal, onComplete: (Int?) -> Unit): Boolean {
        if (owner !== candidate) return false
        val ticket = Any()
        pending[ticket] = candidate
        try {
            removal.remove completion@{ failure ->
                if (pending.remove(ticket) == null) return@completion
                if (failure == null && retained?.owner === candidate) retained = null
                try {
                    onComplete(failure)
                } finally {
                    resumeWaiting()
                }
            }
        } catch (error: Exception) {
            pending.remove(ticket)
            resumeWaiting()
            throw error
        }
        return true
    }

    private fun resumeWaiting() {
        if (pending.isNotEmpty() || waiting.isEmpty()) return
        val ready = waiting.toList()
        waiting.clear()
        for ((candidate, continuation) in ready) {
            if (pending.isNotEmpty()) {
                if (!waiting.containsKey(candidate)) waiting[candidate] = continuation
            } else {
                continuation()
            }
        }
    }
}
