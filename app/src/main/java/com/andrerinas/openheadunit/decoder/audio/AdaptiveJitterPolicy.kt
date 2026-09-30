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
    private val arrivals = SupplyClock()
    private val pcm = SupplyClock()
    var largestPcmGapMs = 0L
        private set
    private var lastAdjustmentMs = -1L
    private var lastDisturbanceMs = -1L
    // Ten one-second buckets age out unusual packet/batch sizes without per-arrival allocation.
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

    /** One observation clock per stage: encoded ingress never advances PCM supply. */
    private inner class SupplyClock {
        var lastMs = -1L
        var startedMs = -1L
        var batchFrames = 0L
        var lastFrames = 0
        var peakFrames = 0L
        var gapMs = 0L
        var excessMs = 0L
        var completedFrames = 0L

        fun observe(nowMs: Long, suppliedFrames: Int) {
            gapMs = if (lastMs < 0) 0 else (nowMs - lastMs).coerceAtLeast(0)
            excessMs = 0
            completedFrames = 0
            // Join deliveries substantially faster than the preceding chunk's playback time.
            // The existing 5ms disturbance tolerance also avoids merging ordinary paced input.
            val coalesced = lastMs >= 0 &&
                (gapMs == 0L || (gapMs + 5) * sampleRate < lastFrames * 1000L)
            if (coalesced) {
                batchFrames += suppliedFrames
                // Time spent delivering a batch is not itself extra instantaneous reserve.
                peakFrames = maxOf(peakFrames, batchFrames -
                    (nowMs - startedMs).coerceAtLeast(0) * sampleRate / 1000)
            } else {
                if (lastMs >= 0) {
                    completedFrames = peakFrames
                    excessMs = (((nowMs - startedMs).coerceAtLeast(0) * sampleRate -
                        batchFrames * 1000L).coerceAtLeast(0) / sampleRate)
                }
                startedMs = nowMs
                batchFrames = suppliedFrames.toLong()
                peakFrames = batchFrames
            }
            lastMs = nowMs
            lastFrames = suppliedFrames
        }

        fun reset() {
            lastMs = -1L; startedMs = -1L; batchFrames = 0; lastFrames = 0
            gapMs = 0; excessMs = 0; completedFrames = 0; peakFrames = 0
        }
    }

    fun onArrival(nowMs: Long, chunkFrames: Int) {
        if (chunkFrames <= 0) return
        arrivals.observe(nowMs, chunkFrames)
        largestArrivalGapMs = maxOf(largestArrivalGapMs, arrivals.gapMs)
        observeSupply(nowMs, arrivals, chunkFrames)
    }

    fun onPcmDelivery(nowMs: Long, chunkFrames: Int) {
        if (chunkFrames <= 0) return
        pcm.observe(nowMs, chunkFrames)
        largestPcmGapMs = maxOf(largestPcmGapMs, pcm.gapMs)
        // The actual decoded-frame count supplies the independent decoder batching bound.
        observeSupply(nowMs, pcm, 0)
    }

    private fun observeSupply(nowMs: Long, clock: SupplyClock, ingressFrames: Int) {
        // Learn complete batches, not a transient running sum on every callback. A large
        // physical catch-up stays bounded by the existing target ceiling and ages out.
        val batch = clock.completedFrames.coerceAtMost(frames(maximumMs - 10).toLong()).toInt()
        recordSupplySize(nowMs, maxOf(ingressFrames, batch))
        if (clock.completedFrames > 0 && clock.gapMs > 0) {
            val excess = clock.excessMs.coerceAtMost(maximumMs)
            if (excess > 5) lastDisturbanceMs = nowMs
            val observedNeedMs = largestChunkFrames * 1000L / sampleRate + 20 + excess
            ceilingMs = maxOf(ceilingMs, observedNeedMs.coerceAtMost(maximumMs))
            if (excess + 10 > marginMs) {
                marginMs = maxOf(marginMs + 20, excess + 10).coerceAtMost(maxMarginMs())
                lastAdjustmentMs = nowMs
            }
        }
        recover(nowMs)
    }

    private fun recordSupplySize(nowMs: Long, size: Int) {
        val epoch = nowMs / 1000
        val slot = (epoch % chunkEpochs.size).toInt()
        if (chunkEpochs[slot] != epoch) {
            chunkEpochs[slot] = epoch
            chunkMaxima[slot] = 0
        }
        chunkMaxima[slot] = maxOf(chunkMaxima[slot], size)
        largestChunkFrames = size
        for (i in chunkEpochs.indices) {
            if (epoch - chunkEpochs[i] in 0 until chunkEpochs.size.toLong()) {
                largestChunkFrames = maxOf(largestChunkFrames, chunkMaxima[i])
            }
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
        arrivals.reset(); pcm.reset()
        largestArrivalGapMs = 0L; largestPcmGapMs = 0L
        chunkEpochs.fill(-1L); chunkMaxima.fill(0); largestChunkFrames = 0
    }
    private fun maxMarginMs() = (ceilingMs - largestChunkFrames * 1000L / sampleRate - 10).coerceAtLeast(15L)
    private fun frames(ms: Long) = (sampleRate * ms / 1000).toInt()
}
