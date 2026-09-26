package com.andrerinas.openheadunit.decoder.audio

/**
 * How large an [android.media.AudioTrack] to ask for.
 *
 * Capacity is fixed at construction and can only be requested, never grown, so ask generously. A
 * capacity below the jitter target cannot hold at all, which is why the multiplier has a floor
 * under it.
 *
 * Capacity is reserve space, not a playback target. On API 24+ the effective size is limited to
 * the active jitter target and grows when the sink re-banks. Otherwise the framework's start
 * threshold and the mixer's continuous silence writes turn this reserve into audible latency.
 */
object AudioBufferSizingPolicy {

    /** Capacity floor, whatever the latency multiplier works out to. */
    const val MIN_CAPACITY_MS = 400L

    fun framesFor(sampleRateInHz: Int, ms: Long): Int =
        if (sampleRateInHz <= 0) 0 else (sampleRateInHz.toLong() * ms / 1000L).toInt()

    /**
     * Bytes to request. [minBufferBytes] is the device minimum; a non-positive one is handed back
     * untouched so the caller keeps the framework's own error.
     */
    fun requestedBytes(
        minBufferBytes: Int,
        multiplier: Int,
        sampleRateInHz: Int,
        bytesPerFrame: Int
    ): Int {
        if (minBufferBytes <= 0) return minBufferBytes
        val byMultiplier = minBufferBytes * multiplier.coerceAtLeast(1)
        val floorBytes = framesFor(sampleRateInHz, MIN_CAPACITY_MS) * bytesPerFrame.coerceAtLeast(1)
        return maxOf(byMultiplier, floorBytes, minBufferBytes)
    }

    /** A small write margin above the active bank; never force a device below its minimum. */
    fun effectiveFrames(
        sampleRateInHz: Int,
        targetFrames: Int,
        minFrames: Int,
        capacityFrames: Int
    ): Int {
        val wanted = maxOf(targetFrames + framesFor(sampleRateInHz, 5), minFrames, 1)
        return if (capacityFrames > 0) wanted.coerceAtMost(capacityFrames) else wanted
    }
}
