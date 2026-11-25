package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RestartCause

/**
 * The process-level state of the local hotspot calls: which host is live, which request has no
 * answer, the start stamps and how the last one ended. No Android class, so a test passes its own.
 * [T] is the host. The `close` lambdas run outside the lock.
 */
class LocalHotspotLedger<T : Any> {

    /** One call to the platform. [earlier] is the unanswered host before it, for [threw]. */
    class Call<T : Any>(val host: T, val earlier: T?, val stampMs: Long)

    private val lock = Any()
    private val stamps = ArrayList<Long>()
    private var live: T? = null
    private var unanswered: T? = null
    private var end: RestartCause? = null
    private var refusedAtMs: Long? = null
    private var pair: Pair<String, String>? = null

    val lastEnd: RestartCause? get() = synchronized(lock) { end }
    val lastRefusedAtMs: Long? get() = synchronized(lock) { refusedAtMs }
    val lastPair: Pair<String, String>? get() = synchronized(lock) { pair }

    /** Closes a host that is still live, then decides. A caller cannot get a decision without the stray close. */
    fun begin(nowMs: Long, close: (T) -> Unit): LocalHotspotPolicy.StartDecision {
        val stray = synchronized(lock) { live }
        if (stray != null) {
            close(stray)
            closed(stray)
        }
        return synchronized(lock) { LocalHotspotPolicy.startDecision(stamps.toList(), end, nowMs) }
    }

    /** Marks [host] live and unanswered and stamps the call. Run it just before the platform call. */
    fun called(nowMs: Long, host: T): Call<T> = synchronized(lock) {
        val call = Call(host, unanswered, nowMs)
        live = host
        unanswered = host
        stamps.add(nowMs)
        while (stamps.size > LocalHotspotPolicy.MAX_STARTS_IN_WINDOW + 1) stamps.removeAt(0)
        call
    }

    /** The call returned: a refusal is forgiven. */
    fun accepted() = synchronized(lock) { refusedAtMs = null }

    /** The call threw: nothing was requested, so the stamp and the marks go back. */
    fun threw(call: Call<T>, nowMs: Long) = synchronized(lock) {
        val at = stamps.lastIndexOf(call.stampMs)
        if (at >= 0) stamps.removeAt(at)
        unanswered = LocalHotspotPolicy.pendingAfterThrow(unanswered, call.host, call.earlier)
        if (live === call.host) live = null
        refusedAtMs = nowMs
    }

    fun answered(host: T) = synchronized(lock) { if (unanswered === host) unanswered = null }

    /** The answer of a host that was closed first: close the reservation, then clear the mark. */
    fun lateAnswer(host: T, close: () -> Unit) {
        close()
        answered(host)
    }

    /** A null pair keeps the earlier one. */
    fun recordPair(newPair: Pair<String, String>?) = synchronized(lock) { if (newPair != null) pair = newPair }

    /** Only the live host moves [lastEnd]: the close of a refused host changes nothing. */
    fun closed(host: T) = end(host, RestartCause.CLOSE)

    fun ended(host: T, cause: RestartCause) = end(host, cause)

    private fun end(host: T, cause: RestartCause) = synchronized(lock) {
        if (live === host) {
            live = null
            end = cause
        }
    }

    fun hasUnansweredStart(): Boolean = synchronized(lock) { unanswered != null }

    fun recentStarts(nowMs: Long): List<Long> = synchronized(lock) { LocalHotspotPolicy.prune(stamps.toList(), nowMs) }
}
