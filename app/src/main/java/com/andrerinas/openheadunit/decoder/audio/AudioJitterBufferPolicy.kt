package com.andrerinas.openheadunit.decoder.audio

/**
 * Converts the persisted latency dial to a requested reserve without changing saved preferences.
 * The 8x anchor means 200 ms; it is not the default. An unset preference remains 16x / 400 ms.
 *
 * This is not the effective device buffer size or a guarantee that a shallow setting can survive
 * the sender's chunk sizes. [AdaptiveJitterPolicy] applies the arrival/drain invariant and learns
 * a reserve; [AdaptivePcmBuffer] banks it independently of device output tuning. Keeping these
 * responsibilities separate avoids repeated rebanking on a steady but coarsely batched stream.
 */
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
