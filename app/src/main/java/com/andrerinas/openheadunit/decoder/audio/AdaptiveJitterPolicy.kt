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

    // A steady arrival interval drains one chunk before its replacement arrives. Reserve must
    // also cover a render quantum or the bank reaches zero every interval, even without jitter.
    // This invariant takes precedence over the nominal ceiling for unusually large batches.
    val targetFrames: Int
        get() {
            val invariant = largestChunkFrames + frames(10)
            return maxOf(invariant, minOf(frames(ceilingMs), maxOf(frames(floorMs), invariant + frames(marginMs))))
        }

    /** Conserved supply decides gaps; a separately aged envelope learns reserve.
     * Neither counter is the actual PCM bank or a playback-head measurement. */
    private inner class SupplyClock {
        var lastMs = -1L
        var gapMs = 0L
        var excessMs = 0L
        private var position = 0L
        private var envelopeFrameMillis = 0L
        // Elapsed time consumes this credit. An epoch rollover must never consume it.
        private var conservedFrameMillis = 0L
        var coveredGapFrames = 0L
        private var epoch = -1L
        private var currentMinimum = Long.MAX_VALUE
        private var previousMinimum = Long.MAX_VALUE
        private var learningMinimum = Long.MAX_VALUE
        private var currentMaximum = Long.MIN_VALUE
        private var previousMaximum = Long.MIN_VALUE
        var completedFrames = 0L
        private var peakFrameMillis = 0L
        private var peakStartedMs = -1L

        fun observe(nowMs: Long, suppliedFrames: Int) {
            gapMs = if (lastMs < 0) 0 else (nowMs - lastMs).coerceAtLeast(0)
            // Frames * 1000 retains fractional frames while elapsed time is in milliseconds.
            val elapsedFrameMillis = gapMs * sampleRate
            // Publish after the supplied audio covers the interval, not during a short
            // prefix whose fragments would otherwise move their own startup target.
            // A prolonged busy period publishes at least once a second. This does not
            // erase credit: the 5ms tolerance is only a learning boundary.
            val complete = gapMs > 0 && (elapsedFrameMillis + 5L * sampleRate >= envelopeFrameMillis ||
                nowMs - peakStartedMs >= 1000)
            completedFrames = if (complete) (peakFrameMillis + 999) / 1000 else 0
            if (complete || peakStartedMs < 0) {
                peakFrameMillis = 0
                peakStartedMs = nowMs
            }
            // Fresh evidence of an interval bridged by prior supply, even if that supply
            // accumulated over many seconds. Do not publish the lifetime credit as a peak.
            coveredGapFrames = minOf(elapsedFrameMillis, conservedFrameMillis) / 1000
            excessMs = (elapsedFrameMillis - conservedFrameMillis).coerceAtLeast(0) / sampleRate
            conservedFrameMillis = ((conservedFrameMillis - elapsedFrameMillis).coerceAtLeast(0) + suppliedFrames * 1000L)
                .coerceAtMost(maximumMs * sampleRate)
            position -= elapsedFrameMillis
            // An observed return to a recent trough closes the previous excursion.
            // This rebases learning, not conserved supply (including any old surplus).
            if (position <= minOf(currentMinimum, previousMinimum)) learningMinimum = position
            val nextEpoch = nowMs / 1000
            if (nextEpoch != epoch) {
                if (nextEpoch != epoch + 1) learningMinimum = position
                // A contained/stationary range can retire an old constant offset. A
                // growing range keeps its origin, however long its delivery train lasts.
                else if (currentMaximum <= previousMaximum && currentMinimum >= previousMinimum)
                    learningMinimum = minOf(currentMinimum, previousMinimum)
                previousMaximum = if (nextEpoch == epoch + 1) currentMaximum else Long.MIN_VALUE
                currentMaximum = Long.MIN_VALUE
                previousMinimum = if (nextEpoch == epoch + 1) currentMinimum else Long.MAX_VALUE
                currentMinimum = Long.MAX_VALUE
                epoch = nextEpoch
            }
            // Fixed-size buckets are stationarity evidence only, not the lifetime of
            // a supply obligation. Both observations and learning use scalar storage.
            currentMinimum = minOf(currentMinimum, position)
            learningMinimum = minOf(learningMinimum, position)
            position += suppliedFrames * 1000L
            currentMaximum = maxOf(currentMaximum, position)
            envelopeFrameMillis = position - learningMinimum
            peakFrameMillis = maxOf(peakFrameMillis, envelopeFrameMillis)
            lastMs = nowMs
        }

        fun reset() {
            lastMs = -1L; gapMs = 0; excessMs = 0; position = 0; envelopeFrameMillis = 0
            conservedFrameMillis = 0; coveredGapFrames = 0
            completedFrames = 0; peakFrameMillis = 0; peakStartedMs = -1L
            epoch = -1L; currentMinimum = Long.MAX_VALUE; previousMinimum = Long.MAX_VALUE
            learningMinimum = Long.MAX_VALUE; currentMaximum = Long.MIN_VALUE; previousMaximum = Long.MIN_VALUE
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
        // Learn completed envelope peaks and newly bridged gaps, never the raw lifetime
        // credit. Existing ten-second buckets age both kinds of reserve evidence.
        val completed = maxOf(clock.completedFrames, clock.coveredGapFrames).coerceAtMost(frames(maximumMs - 10).toLong()).toInt()
        recordSupplySize(nowMs, maxOf(ingressFrames, completed))
        if (clock.gapMs > 0) {
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
