package com.andrerinas.openheadunit.decoder.audio

/** Session and registration ownership are checked at the same lock as focus activity/retirement. */
internal class PlaybackFocusLease {
    data class Request(val version: Long, val sinceMs: Long)
    data class Activity(val owner: Any, val revision: Long)
    private val owners = HashMap<Int, Any>()
    private val active = HashMap<Int, Activity>()
    private var revision = 0L
    private var pending: Request? = null
    private var ready = false
    private var closed = false

    /** Replacement retires only the old registration's demand, without announcing new audio. */
    @Synchronized fun register(channel: Int, owner: Any): Long? {
        if (closed) return null
        if (owners.put(channel, owner) === owner) return null
        return endActivity(channel)
    }
    @Synchronized fun retire(channel: Int, owner: Any): Long? {
        if (owners[channel] !== owner) return null
        owners.remove(channel)
        return endActivity(channel)
    }
    private fun endActivity(channel: Int): Long? {
        if (active.remove(channel) == null) return null
        revision++
        if (active.isNotEmpty()) return null
        pending = null; ready = false
        return revision
    }
    @Synchronized fun activity(channel: Int, owner: Any, nowMs: Long): Request? {
        if (closed || owners[channel] !== owner) return null
        active[channel] = Activity(owner, ++revision)
        if (ready || pending != null) return null
        return Request(revision, nowMs).also { pending = it }
    }
    @Synchronized fun accepts(request: Request): Boolean = !closed && pending == request
    @Synchronized fun complete(request: Request): Boolean {
        if (!accepts(request)) return false
        pending = null
        // Denied focus deliberately permits playback as before; do not strand a prompt.
        ready = true
        return true
    }
    @Synchronized fun canRender(nowMs: Long): Boolean {
        if (closed || active.isEmpty()) return false
        val request = pending
        if (!ready && request != null && nowMs - request.sinceMs >= 500) {
            // A blocked main/Binder must not permanently mute audio. Invalidate a late acquire.
            pending = null
            ready = true
        }
        return ready
    }
    @Synchronized fun snapshot(): Map<Int, Activity> = HashMap(active)
    /** Returns true only for the final still-current registration and activity revision. */
    @Synchronized fun release(channel: Int, expected: Activity): Boolean {
        if (active[channel] != expected) return false
        active.remove(channel)
        if (active.isNotEmpty()) return false
        pending = null; ready = false
        return true
    }
    @Synchronized fun isOpen(): Boolean = !closed
    @Synchronized fun clear(): Long {
        active.clear(); pending = null; ready = false
        return ++revision
    }
    @Synchronized fun acceptsRelease(version: Long): Boolean =
        !closed && active.isEmpty() && revision == version
    @Synchronized fun close() { clear(); owners.clear(); closed = true }
}
