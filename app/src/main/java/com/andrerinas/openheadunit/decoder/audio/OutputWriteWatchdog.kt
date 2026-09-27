package com.andrerinas.openheadunit.decoder.audio

/** A nonblocking device write must eventually progress even when it reports no error.
 * A fresh output is allowed twice without progress; a broken route must not reopen forever. */
internal class OutputWriteWatchdog {
    enum class Action { WAIT, REOPEN, STOP }
    private var blockedSinceMs = -1L
    private var recoveryAttempts = 0

    fun onProgress() {
        blockedSinceMs = -1L
        recoveryAttempts = 0
    }

    fun onBlocked(nowMs: Long): Action {
        if (blockedSinceMs < 0) blockedSinceMs = nowMs
        if (nowMs - blockedSinceMs < 500L) return Action.WAIT
        blockedSinceMs = -1L
        return if (recoveryAttempts++ < 2) Action.REOPEN else Action.STOP
    }
}
