package com.andrerinas.openheadunit.decoder.audio

/** Back off only after the legacy AudioTrack admission check observes a full device ring.
 * This is a retry hint, never permission to write: the next attempt still reads the actual
 * playback head. A coarse/stalled vendor head therefore cannot grant fictional free space.
 * Keep at least four checks per observed queued duration and never request more than 4 ms;
 * small buffers and new outputs retain the original 1 ms response. Sleeps remain interruptible.
 */
internal class LegacyAudioWritePacer {
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
