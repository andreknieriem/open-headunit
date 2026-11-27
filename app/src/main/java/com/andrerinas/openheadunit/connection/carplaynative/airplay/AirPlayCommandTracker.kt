package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * Matches the phone's replies on the event channel to the commands we sent. A reply names the CSeq.
 * At most [MAX_PENDING] unanswered commands are kept: the oldest is forgotten first.
 */
class AirPlayCommandTracker {
    class Answered(val type: String, val count: Int) {
        /** A touch train would flood the log, so only the first answers and every hundredth show. */
        val shouldLog: Boolean get() = count <= LOGGED_FIRST || count % LOGGED_EVERY == 0
    }

    private val pending = object : LinkedHashMap<Int, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>) = size > MAX_PENDING
    }
    private val counts = HashMap<String, Int>()

    @Synchronized
    fun sent(cseq: Int, type: String) {
        pending[cseq] = type
    }

    /** Null for a CSeq we did not send, or one that was answered already. */
    @Synchronized
    fun answered(cseq: Int): Answered? {
        val type = pending.remove(cseq) ?: return null
        val count = (counts[type] ?: 0) + 1
        counts[type] = count
        return Answered(type, count)
    }

    companion object {
        const val MAX_PENDING = 64
        private const val LOGGED_FIRST = 3
        private const val LOGGED_EVERY = 100
    }
}
