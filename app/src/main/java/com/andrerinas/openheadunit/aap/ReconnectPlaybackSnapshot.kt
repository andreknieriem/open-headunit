package com.andrerinas.openheadunit.aap

/** Playback belongs to the last reporting session, not to attempts that never carried media. */
internal class ReconnectPlaybackSnapshot {
    data class Resume(val wasPlaying: Boolean, val elapsedMs: Long)
    private var wasPlaying = false
    private var disconnectedAt = 0L

    fun onDisconnected(lastPlaying: Boolean?, now: Long, deliberate: Boolean) {
        if (deliberate) {
            wasPlaying = false
        } else if (lastPlaying != null) {
            wasPlaying = lastPlaying
            disconnectedAt = now
        }
        // A failed handshake has no playback report. Preserve both the intent and its original
        // age, so retries cannot erase a playing session or extend the auto-resume window.
    }

    fun consume(now: Long): Resume = Resume(wasPlaying, now - disconnectedAt).also {
        wasPlaying = false
    }
}
