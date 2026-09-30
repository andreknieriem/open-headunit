package com.andrerinas.openheadunit.decoder.audio

/**
 * Whether a failed AAC decoder is rebuilt or parked. Unsubmitted input remains owned by the
 * wrapper. Bound both retries and spacing without retaining tens of seconds of stale audio.
 */
object AacDecoderRecoveryPolicy {

    /** Rebuilds allowed per track lifetime. */
    const val MAX_REBUILDS = 3

    /** Minimum spacing between rebuilds. */
    const val MIN_SPACING_MS = 1_000L

    fun allowsRebuild(rebuildsSoFar: Int, sinceLastRebuildMs: Long): Boolean =
        rebuildsSoFar < MAX_REBUILDS && (rebuildsSoFar == 0 || sinceLastRebuildMs >= MIN_SPACING_MS)
}
