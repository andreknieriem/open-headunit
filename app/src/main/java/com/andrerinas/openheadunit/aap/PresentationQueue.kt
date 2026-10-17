package com.andrerinas.openheadunit.aap

/**
 * A session's latest display work, never protocol traffic. There is at most one pending task per
 * slot, so a stalled UI cannot accumulate notifications or album-art payloads at packet rate.
 * [post] and [cancel] must enqueue/remove on the same serial executor; neither may execute inline.
 * Work runs outside the lock: a slow Binder call must not stop the reader from replacing a slot.
 */
internal class PresentationQueue(
    private val post: (Runnable, Long) -> Boolean,
    private val cancel: (Runnable) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    enum class Slot { METADATA, PLAYBACK, NAV_NOTIFICATION, NAV_BROADCAST }

    private class Pending(var action: () -> Unit) { lateinit var runnable: Runnable }
    private val lock = Any()
    private val pending = mutableMapOf<Slot, Pending>()
    private var closed = false

    fun submit(slot: Slot, delayMs: Long = 0, action: () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        pending[slot]?.let {
            // Retain the original deadline. In particular, continuous navigation updates must
            // still publish once a second, rather than postpone their broadcast indefinitely.
            it.action = action
            return@synchronized
        }
        val task = Pending(action)
        task.runnable = Runnable {
            val latest = synchronized(lock) {
                if (closed || pending[slot] !== task) null
                else pending.remove(slot)?.action
            }
            if (latest != null) safely(latest)
        }
        pending[slot] = task
        if (!post(task.runnable, delayMs)) pending.remove(slot)
    }

    /**
     * Retire before publishing transport disconnect. A claimed call may finish, but cleanup is
     * queued behind it on the same executor and before a replacement session can publish UI work.
     * Teardown never waits for the UI/Binder from the reader thread.
     */
    fun close(cleanup: () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        pending.values.forEach { cancel(it.runnable) }
        pending.clear()
        post(Runnable { safely(cleanup) }, 0)
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Exception) { onError(e) }
    }
}
