package com.andrerinas.openheadunit.decoder.audio

/** Back off only when AudioTrack accepts no PCM and its playback head gives a known reserve.
 * This is a retry hint, never permission to write: legacy admission still reads the actual
 * head, and modern tracks still use nonblocking writes. Unknown heads retain 1 ms retries.
 * Bound the hint to one quarter of the observed reserve, clamped to 1–4 ms.
 * Small reserves retain the original 1 ms floor; sleeps remain interruptible.
 */
internal class AudioTrackWritePacer {
    var delayMs = 1L
        private set
    private var fullChecks = 0

    fun full(queuedFrames: Long) {
        val reserveBoundMs = (queuedFrames / (48 * 4)).coerceIn(1L, 4L)
        delayMs = minOf(1L shl fullChecks, reserveBoundMs)
        fullChecks = (fullChecks + 1).coerceAtMost(2)
    }

    fun reset() {
        fullChecks = 0
        delayMs = 1
    }
}
