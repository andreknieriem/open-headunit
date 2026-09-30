package com.andrerinas.openheadunit.decoder.audio

/** Independent ingress and decoded-PCM clocks share a bounded reserve. A timely encoded
 * packet cannot hide a codec that supplies PCM in late batches. */
internal class AdaptiveJitterPolicy(
    private val sampleRate: Int,
    latencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER
) {
    private val floorMs = maxOf(60L, AudioJitterBufferPolicy.targetMsFor(latencyMultiplier))
    // Keep the normal low-latency budget, but do not enforce it after the link demonstrates
    // that it cannot hold. Otherwise every 160-300ms batch is trimmed and then starves.
    private val initialCeilingMs = maxOf(150L, floorMs)
    private var ceilingMs = initialCeilingMs
    private val maximumMs = maxOf(AudioJitterBufferPolicy.MAX_TARGET_MS, floorMs)
    private var marginMs = 15L
    private var previousArrivalMs = -1L
    private var previousFrames = 0
    private var previousPcmMs = -1L
    private var previousPcmFrames = 0
    var largestPcmGapMs = 0L
        private set
    private var lastAdjustmentMs = -1L
    private var lastDisturbanceMs = -1L
    // Ten one-second buckets age out unusual packet sizes without allocating per arrival.
    private val chunkEpochs = LongArray(10) { -1L }
    private val chunkMaxima = IntArray(10)
    var largestChunkFrames = 0
        private set
    var largestArrivalGapMs = 0L
        private set

    val targetFrames: Int
        get() {
            val invariant = largestChunkFrames + frames(10)
            return maxOf(invariant, minOf(frames(ceilingMs), maxOf(frames(floorMs), invariant + frames(marginMs))))
        }

    fun onArrival(nowMs: Long, chunkFrames: Int) {
        if (chunkFrames <= 0) return
        val epoch = nowMs / 1000
        val slot = (epoch % chunkEpochs.size).toInt()
        if (chunkEpochs[slot] != epoch) {
            chunkEpochs[slot] = epoch
            chunkMaxima[slot] = 0
        }
        chunkMaxima[slot] = maxOf(chunkMaxima[slot], chunkFrames)
        largestChunkFrames = chunkFrames
        for (i in chunkEpochs.indices) {
            if (epoch - chunkEpochs[i] in 0 until chunkEpochs.size.toLong()) {
                largestChunkFrames = maxOf(largestChunkFrames, chunkMaxima[i])
            }
        }
        if (previousArrivalMs >= 0) {
            val gap = nowMs - previousArrivalMs
            largestArrivalGapMs = maxOf(largestArrivalGapMs, gap)
            observeSupplyGap(nowMs, gap, previousFrames)
        }
        previousArrivalMs = nowMs
        previousFrames = chunkFrames
        recover(nowMs)
    }

    fun onPcmDelivery(nowMs: Long, chunkFrames: Int) {
        if (chunkFrames <= 0) return
        if (previousPcmMs >= 0) {
            val gap = (nowMs - previousPcmMs).coerceAtLeast(0)
            largestPcmGapMs = maxOf(largestPcmGapMs, gap)
            observeSupplyGap(nowMs, gap, previousPcmFrames)
        }
        previousPcmMs = nowMs
        previousPcmFrames = chunkFrames
    }

    private fun observeSupplyGap(nowMs: Long, gapMs: Long, precedingFrames: Int) {
        // Sink Stop explicitly resets both clocks. An active outage of 1s or longer is
        // still starvation evidence; never silently classify it as a paused stream.
        if (gapMs <= 0) return
        val excess = (gapMs - precedingFrames * 1000L / sampleRate).coerceIn(0, maximumMs)
        // Repeated batches are continuing evidence even when the reserve already covers them.
        // A stable encoded stream alone must not shrink a late decoder's PCM reserve.
        if (excess > 5) lastDisturbanceMs = nowMs
        val observedNeedMs = largestChunkFrames * 1000L / sampleRate + 20 + excess
        ceilingMs = maxOf(ceilingMs, observedNeedMs.coerceAtMost(maximumMs))
        if (excess + 10 > marginMs) {
            marginMs = maxOf(marginMs + 20, excess + 10).coerceAtMost(maxMarginMs())
            lastAdjustmentMs = nowMs
        }
    }

    fun onUnderrun(nowMs: Long) {
        if (targetFrames >= frames(ceilingMs)) ceilingMs = (ceilingMs + 20).coerceAtMost(maximumMs)
        marginMs = (marginMs + 20).coerceAtMost(maxMarginMs())
        lastAdjustmentMs = nowMs
        lastDisturbanceMs = nowMs
    }

    private fun recover(nowMs: Long) {
        if (lastAdjustmentMs < 0) lastAdjustmentMs = nowMs
        if (lastDisturbanceMs < 0) lastDisturbanceMs = nowMs
        // First earn ten quiet seconds, then release 5ms per second. PCM is still repaid by
        // LatencyRecoveryPolicy's bounded overlaps; changing this target never discards audio.
        if (nowMs - lastDisturbanceMs >= 10_000 && nowMs - lastAdjustmentMs >= 1000) {
            marginMs = (marginMs - 5).coerceAtLeast(15)
            ceilingMs = (ceilingMs - 5).coerceAtLeast(initialCeilingMs)
            lastAdjustmentMs = nowMs
        }
    }

    fun resetArrival() {
        previousArrivalMs = -1L; previousFrames = 0
        largestArrivalGapMs = 0L
        previousPcmMs = -1L; previousPcmFrames = 0; largestPcmGapMs = 0L
        chunkEpochs.fill(-1L); chunkMaxima.fill(0); largestChunkFrames = 0
    }
    private fun maxMarginMs() = (ceilingMs - largestChunkFrames * 1000L / sampleRate - 10).coerceAtLeast(15L)
    private fun frames(ms: Long) = (sampleRate * ms / 1000).toInt()
}
