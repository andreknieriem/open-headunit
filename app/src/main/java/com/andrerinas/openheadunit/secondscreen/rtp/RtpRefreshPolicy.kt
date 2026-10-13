package com.andrerinas.openheadunit.secondscreen.rtp

/**
 * When the RTP push asks the phone for a fresh keyframe. A pushed stream gets no word back from its
 * receiver, so a lost packet freezes it until the next IDR, and the phone's own GOP is about a minute.
 */
object RtpRefreshPolicy {

    /** The choices of the setting, in seconds; 0 is off. */
    val INTERVALS_S = intArrayOf(0, 1, 2, 5, 10, 30)
    const val DEFAULT_INTERVAL_S = 10

    /** At or above this interval each request gets its own log line; below it the report count says enough. */
    const val LOG_EACH_REQUEST_FROM_MS = 5_000L

    fun logsEachRequest(intervalMs: Long): Boolean = intervalMs >= LOG_EACH_REQUEST_FROM_MS

    fun intervalOrDefault(seconds: Int): Int = if (seconds in INTERVALS_S) seconds else DEFAULT_INTERVAL_S

    /** Extra wait after an ask with no keyframe yet, so a slow phone is not cycled again before its IDR. */
    const val PENDING_GRACE_MS = 2_000L

    /** True once [intervalMs] has passed with no keyframe sent and no request made, plus the grace while one is pending. */
    fun shouldRequest(nowMs: Long, lastKeyframeMs: Long, lastRequestMs: Long, intervalMs: Long): Boolean {
        if (intervalMs <= 0 || nowMs - lastKeyframeMs < intervalMs) return false
        val pending = lastKeyframeMs < lastRequestMs
        return nowMs - lastRequestMs >= if (pending) intervalMs + PENDING_GRACE_MS else intervalMs
    }
}
