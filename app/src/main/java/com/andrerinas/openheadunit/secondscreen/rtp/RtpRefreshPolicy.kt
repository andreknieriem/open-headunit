package com.andrerinas.openheadunit.secondscreen.rtp

/**
 * When the RTP push asks the phone for a fresh keyframe. A pushed stream gets no word back from its
 * receiver, so a lost packet freezes it until the next IDR, and the phone's own GOP is about a minute.
 */
object RtpRefreshPolicy {

    /** The choices of the setting, in seconds; 0 is off. */
    val INTERVALS_S = intArrayOf(0, 5, 10, 30)
    const val DEFAULT_INTERVAL_S = 10

    fun intervalOrDefault(seconds: Int): Int = if (seconds in INTERVALS_S) seconds else DEFAULT_INTERVAL_S

    /** True once [intervalMs] has passed with no keyframe sent and no request made. */
    fun shouldRequest(nowMs: Long, lastKeyframeMs: Long, lastRequestMs: Long, intervalMs: Long): Boolean =
        intervalMs > 0 && nowMs - lastKeyframeMs >= intervalMs && nowMs - lastRequestMs >= intervalMs
}
