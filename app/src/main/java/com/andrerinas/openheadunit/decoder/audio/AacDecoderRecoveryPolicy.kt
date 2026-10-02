package com.andrerinas.openheadunit.decoder.audio

/**
 * Whether a failed AAC decoder is rebuilt or parked. A failed codec may never return another
 * input buffer; merely timing out each submission would discard audio for the rest of the session.
 * Rebuilds recover that path, while the count and spacing prevent a persistently failing codec
 * from churning. Unsubmitted input remains owned by the wrapper during bounded recovery, rather
 * than being treated as accepted or retained for tens of seconds as stale audio.
 */
object AacDecoderRecoveryPolicy {

    /** Rebuilds allowed per track lifetime. */
    const val MAX_REBUILDS = 3

    /** Minimum spacing between rebuilds. */
    const val MIN_SPACING_MS = 1_000L

    fun allowsRebuild(rebuildsSoFar: Int, sinceLastRebuildMs: Long): Boolean =
        rebuildsSoFar < MAX_REBUILDS && (rebuildsSoFar == 0 || sinceLastRebuildMs >= MIN_SPACING_MS)
}
