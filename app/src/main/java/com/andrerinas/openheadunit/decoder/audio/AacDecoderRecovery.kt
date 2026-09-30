package com.andrerinas.openheadunit.decoder.audio

/** Codec callbacks request recovery; only the decode thread consumes it. Waiting for the retry
 * deadline must retain the request, including when codec construction itself failed. */
internal class AacDecoderRecovery {
    enum class Action { NONE, WAIT, REBUILD, STOP }
    @Volatile var pending = false
        private set
    @Volatile var attempts = 0
        private set
    private var lastAttemptMs = 0L
    private var exhausted = false
    @Synchronized fun isExhausted(): Boolean = exhausted
    private var playbackVersion = 0L
    private var attemptedPlaybackVersion = 0L

    @Synchronized fun request() { if (!exhausted) pending = true }

    /** Remember Start even if it races the final failed rebuild, before STOP is consumed. */
    @Synchronized fun retryIfExhausted() {
        playbackVersion++
        if (!exhausted) return
        resetBudget()
        pending = true
    }

    @Synchronized fun next(nowMs: Long): Action {
        if (!pending) return Action.NONE
        if (attempts >= AacDecoderRecoveryPolicy.MAX_REBUILDS) {
            if (playbackVersion != attemptedPlaybackVersion) resetBudget()
            else {
                pending = false
                exhausted = true
                return Action.STOP
            }
        }
        if (!AacDecoderRecoveryPolicy.allowsRebuild(attempts, nowMs - lastAttemptMs)) return Action.WAIT
        pending = false
        attempts++
        attemptedPlaybackVersion = playbackVersion
        lastAttemptMs = nowMs
        return Action.REBUILD
    }

    /** A new configuration or explicit playback request may retry a terminal decoder. */
    @Synchronized fun reset() {
        pending = false
        playbackVersion = 0
        attemptedPlaybackVersion = 0
        resetBudget()
    }

    private fun resetBudget() {
        attempts = 0
        lastAttemptMs = 0
        exhausted = false
    }
}
