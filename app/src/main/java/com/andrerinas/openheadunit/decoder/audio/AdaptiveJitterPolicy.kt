package com.andrerinas.openheadunit.decoder.audio

/** Network timing only. All times come from the transport's monotonic clock, before decoding. */
internal class AdaptiveJitterPolicy(
    private val sampleRate: Int,
    latencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER
) {
    private val floorMs = maxOf(60L, AudioJitterBufferPolicy.targetMsFor(latencyMultiplier))
    // Keep the normal low-latency budget, but do not enforce it after the link demonstrates
    // that it cannot hold. Otherwise every 160-300ms batch is trimmed and then starves.
    private var ceilingMs = maxOf(150L, floorMs)
    private val maximumMs = maxOf(AudioJitterBufferPolicy.MAX_TARGET_MS, floorMs)
    private var marginMs = 15L
    private var previousArrivalMs = -1L
    private var previousFrames = 0
    private var lastAdjustmentMs = -1L
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
            // A stopped prompt/session is not network jitter. Bursts (gap=0) also cannot make the
            // estimate shrink: TCP retransmissions deliver several messages at the same instant.
            if (gap in 1..999) {
                val excess = (gap - previousFrames * 1000L / sampleRate).coerceAtLeast(0)
                val observedNeedMs = largestChunkFrames * 1000L / sampleRate + 20 + excess
                ceilingMs = maxOf(ceilingMs, observedNeedMs.coerceAtMost(maximumMs))
                if (excess + 10 > marginMs) {
                    marginMs = maxOf(marginMs + 20, excess + 10).coerceAtMost(maxMarginMs())
                    lastAdjustmentMs = nowMs
                }
            }
        }
        previousArrivalMs = nowMs
        previousFrames = chunkFrames
        recover(nowMs)
    }

    fun onUnderrun(nowMs: Long) {
        if (targetFrames >= frames(ceilingMs)) ceilingMs = (ceilingMs + 20).coerceAtMost(maximumMs)
        marginMs = (marginMs + 20).coerceAtMost(maxMarginMs())
        lastAdjustmentMs = nowMs
    }

    private fun recover(nowMs: Long) {
        if (lastAdjustmentMs < 0) lastAdjustmentMs = nowMs
        if (nowMs - lastAdjustmentMs >= 10_000) {
            marginMs = (marginMs - 5).coerceAtLeast(15)
            lastAdjustmentMs = nowMs
        }
    }

    fun resetArrival() {
        previousArrivalMs = -1L; previousFrames = 0
        largestArrivalGapMs = 0L
        chunkEpochs.fill(-1L); chunkMaxima.fill(0); largestChunkFrames = 0
    }
    private fun maxMarginMs() = (ceilingMs - largestChunkFrames * 1000L / sampleRate - 10).coerceAtLeast(15L)
    private fun frames(ms: Long) = (sampleRate * ms / 1000).toInt()
}
