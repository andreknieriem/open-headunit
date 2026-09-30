package com.andrerinas.openheadunit.decoder.audio

/** Persisted latency dial. AdaptiveJitterPolicy owns runtime arrival/underrun adaptation. */
object AudioJitterBufferPolicy {
    /** Preserve the 400ms default for installs that have never saved a latency setting. */
    const val DEFAULT_MULTIPLIER = 16
    /** Existing saved 8x settings still mean 200ms. This anchor is not the default. */
    const val TARGET_MS = 200L
    const val ANCHOR_MULTIPLIER = 8
    const val MIN_TARGET_MS = 25L
    const val MAX_TARGET_MS = 400L

    fun targetMsFor(latencyMultiplier: Int): Long {
        val m = latencyMultiplier.coerceAtLeast(1)
        return (TARGET_MS * m / ANCHOR_MULTIPLIER).coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
    }
}
