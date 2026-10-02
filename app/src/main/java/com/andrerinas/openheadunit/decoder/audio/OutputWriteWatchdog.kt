package com.andrerinas.openheadunit.decoder.audio

/** A nonblocking device write must eventually progress even when it reports no error.
 * A fresh output is allowed twice without progress; a broken route must not reopen forever. */
internal class OutputWriteWatchdog(private val stableProgressUnits: Int = 1) {
    enum class Action { WAIT, REOPEN, STOP }
    private var blockedSinceMs = -1L
    private var recoveryAttempts = 0
    private var progressUnits = 0L

    fun onProgress(units: Int = 1) {
        blockedSinceMs = -1L
        progressUnits = (progressUnits + units).coerceAtMost(stableProgressUnits.toLong())
        if (progressUnits >= stableProgressUnits) recoveryAttempts = 0
    }

    fun onBlocked(nowMs: Long): Action {
        if (blockedSinceMs < 0) blockedSinceMs = nowMs
        if (nowMs - blockedSinceMs < 500L) return Action.WAIT
        return onFailure()
    }

    /** A dead AudioTrack cannot make progress by waiting for writable space. */
    fun onFailure(): Action {
        blockedSinceMs = -1L
        progressUnits = 0
        return if (recoveryAttempts++ < 2) Action.REOPEN else Action.STOP
    }
}
