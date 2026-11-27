package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * A phone that sent no request, timing packet or keep-alive datagram for [SILENT_MS] has gone.
 * The count starts when the timing channel opens. Times are `elapsedRealtime` milliseconds.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-cp, `stack.rs` `went_silent`.
 */
class PhoneSilence(startMs: Long) {
    @Volatile private var lastHeardMs = startMs

    fun heard(nowMs: Long) {
        lastHeardMs = nowMs
    }

    fun isSilent(nowMs: Long): Boolean = nowMs - lastHeardMs > SILENT_MS

    companion object {
        const val SILENT_MS = 10_000L
    }
}
