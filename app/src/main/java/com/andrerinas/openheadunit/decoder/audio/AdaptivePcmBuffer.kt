package com.andrerinas.openheadunit.decoder.audio

/** A bounded network bank, independent of the device output buffer. One producer and one reader;
 * short synchronized copies also make reset safe during sink stop. All samples are interleaved. */
internal class AdaptivePcmBuffer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    latencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER
) {
    private val cycleFrames = sampleRate / 100
    private val cycleSamples = cycleFrames * channels
    private val ring = ShortArray(sampleRate * channels) // one second of capacity, never a target
    private val lastGood = ShortArray(cycleSamples)
    private val previousOutput = ShortArray(cycleSamples)
    private val policy = AdaptiveJitterPolicy(sampleRate, latencyMultiplier)
    private var head = 0
    private var count = 0
    private var started = false
    private var firstDataMs = -1L
    private var gapFrames = 0
    private var needsFade = true
    private var ended = false
    @Volatile var silentCycles = 0L
        private set
    @Volatile var rebanks = 0L
        private set
    @Volatile var concealedFrames = 0L
        private set
    @Volatile var droppedFrames = 0L
        private set

    @Synchronized fun noteArrival(nowMs: Long, frames: Int) {
        if (ended) policy.resetArrival()
        ended = false
        policy.onArrival(nowMs, frames)
    }
    @Synchronized fun finish() { ended = true }
    @Synchronized fun targetFrames(): Int = policy.targetFrames
    @Synchronized fun depthFrames(): Int = count / channels

    @Synchronized fun write(data: ShortArray, length: Int, nowMs: Long) {
        val aligned = length.coerceAtMost(data.size) / channels * channels
        if (aligned == 0) return
        if (firstDataMs < 0) firstDataMs = nowMs
        val keep = minOf(aligned, ring.size)
        val skip = aligned - keep
        if (count + keep > ring.size) discard(count + keep - ring.size)
        if (skip > 0) { droppedFrames += skip / channels; needsFade = true }
        val tail = (head + count) % ring.size
        val first = minOf(keep, ring.size - tail)
        System.arraycopy(data, skip, ring, tail, first)
        System.arraycopy(data, skip + first, ring, 0, keep - first)
        count += keep
    }

    /** Renders one 10ms block. Returns true when there is real or concealed audio. */
    @Synchronized fun render(out: ShortArray, nowMs: Long): Boolean {
        require(out.size >= cycleSamples)
        java.util.Arrays.fill(out, 0, cycleSamples, 0.toShort())
        val target = policy.targetFrames
        if (!started) {
            // A short navigation prompt must play even if it can never fill the network target.
            if (count == 0 || (!ended && count / channels < target && nowMs - firstDataMs < 100)) return false
            started = true
        }

        // Leave room for the normal packet-sized sawtooth; do not mistake it for stale audio.
        val slack = maxOf(sampleRate * 30 / 1000, policy.largestChunkFrames - cycleFrames)
        if (count / channels > target + slack) discard(count - target * channels)

        val real = minOf(count, cycleSamples)
        val first = minOf(real, ring.size - head)
        System.arraycopy(ring, head, out, 0, first)
        System.arraycopy(ring, 0, out, first, real - first)
        head = (head + real) % ring.size
        count -= real

        val recovering = gapFrames > 0
        if (real == cycleSamples) {
            System.arraycopy(out, 0, lastGood, 0, cycleSamples)
            gapFrames = 0
        } else if (ended) {
            // Sink Stop is a deliberate end, not packet loss. Drain even a partial final block.
            started = false
            firstDataMs = -1L
            gapFrames = 0
            lastGood.fill(0)
        } else {
            if (gapFrames == 0) { policy.onUnderrun(nowMs); rebanks++ }
            silentCycles++
            for (frame in real / channels until cycleFrames) {
                val missingFrame = gapFrames + frame - real / channels
                val block = missingFrame / cycleFrames
                val gain = when (block) { 0 -> 0.7f; 1 -> 0.4f; 2 -> 0.15f; else -> 0f }
                for (ch in 0 until channels) {
                    out[frame * channels + ch] = (lastGood[(missingFrame % cycleFrames) * channels + ch] * gain).toInt().toShort()
                }
                if (block < 3) concealedFrames++
            }
            gapFrames += cycleFrames - real / channels
            if (gapFrames >= cycleFrames * 3) {
                started = false
                firstDataMs = if (count > 0) nowMs else -1L
                gapFrames = 0
                needsFade = true
                lastGood.fill(0)
            }
        }

        // Blend a new live position into the tail that was actually heard. This also fades back
        // from PLC and fades initial playback in, without adding a block of latency.
        if (needsFade || recovering || real < cycleSamples) {
            val fadeFrames = cycleFrames / 2
            for (frame in 0 until fadeFrames) {
                val mix = (frame + 1).toFloat() / fadeFrames
                for (ch in 0 until channels) {
                    val index = frame * channels + ch
                    val old = previousOutput[(cycleFrames - fadeFrames + frame) * channels + ch]
                    out[index] = (old * (1f - mix) + out[index] * mix).toInt().toShort()
                }
            }
            needsFade = false
        }
        System.arraycopy(out, 0, previousOutput, 0, cycleSamples)
        return real > 0 || (!ended && real < cycleSamples)
    }

    private fun discard(samples: Int) {
        val drop = samples.coerceAtMost(count) / channels * channels
        head = (head + drop) % ring.size
        count -= drop
        droppedFrames += drop / channels
        needsFade = true
    }

    @Synchronized fun reset() {
        head = 0; count = 0; started = false; firstDataMs = -1L; gapFrames = 0
        needsFade = true; ended = false
        lastGood.fill(0); previousOutput.fill(0)
        policy.resetArrival()
    }
}
