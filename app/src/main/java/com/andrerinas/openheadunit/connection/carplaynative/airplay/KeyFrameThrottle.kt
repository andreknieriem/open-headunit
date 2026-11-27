package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * Lets one forceKeyFrame through per [MIN_INTERVAL_MS]. A call it refuses does not move the window,
 * so a held train of requests still gives one a second. Times are `elapsedRealtime` milliseconds.
 */
class KeyFrameThrottle {
    private var lastAllowedAt = NEVER

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastAllowedAt != NEVER && nowMs - lastAllowedAt < MIN_INTERVAL_MS) return false
        lastAllowedAt = nowMs
        return true
    }

    /** Gives the slot back when the command was never written. */
    @Synchronized
    fun release() {
        lastAllowedAt = NEVER
    }

    companion object {
        const val MIN_INTERVAL_MS = 1000L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
