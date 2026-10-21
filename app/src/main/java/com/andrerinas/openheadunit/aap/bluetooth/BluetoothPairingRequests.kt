package com.andrerinas.openheadunit.aap.bluetooth

/** One in-flight query plus the newest retry; a slow Binder must not accumulate old replies. */
internal class BluetoothPairingRequests(
    private val now: () -> Long,
    private val post: (() -> Unit) -> Boolean,
    private val consume: (Request, () -> Boolean) -> Unit,
) {
    data class Request(val address: String, val method: Int, val receivedAt: Long)
    private val lock = Any()
    private var latest: Request? = null
    private var scheduled = false
    private var closed = false

    fun offer(address: String, method: Int) {
        val schedule = synchronized(lock) {
            if (closed) return
            latest = Request(address, method, now())
            if (scheduled) false else { scheduled = true; true }
        }
        if (schedule && !post(::drain)) close()
    }

    private fun drain() {
        val request = synchronized(lock) { if (closed) null else latest } ?: return
        try {
            consume(request) { synchronized(lock) { !closed && latest === request } }
        } finally {
            val again = synchronized(lock) {
                if (latest === request) latest = null
                if (closed || latest == null) { scheduled = false; false } else true
            }
            if (again && !post(::drain)) close()
        }
    }

    fun close() = synchronized(lock) { closed = true; latest = null }
}
