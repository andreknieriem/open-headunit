package com.andrerinas.openheadunit.decoder.audio

/** A bounded network bank, independent of the device output buffer. One producer and one reader;
 * short synchronized copies also make reset safe during sink stop. All samples are interleaved. */
internal class AdaptivePcmBuffer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    latencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
    private val isMediaSink: Boolean = false
) {
    private val mediaPrerollDeadlineMs = 1500L
    private val cycleFrames = sampleRate / 100
    private val cycleSamples = cycleFrames * channels
    // Music needs room for a 1s catch-up burst plus its <=400ms reserve. This is
    // capacity, not added latency: 96KB extra at 48k stereo, only for the media sink.
    private val capacityFrames = if (isMediaSink) sampleRate * 3 / 2 else sampleRate
    private val ring = ShortArray(capacityFrames * channels)
    private val lastGood = ShortArray(cycleSamples)
    private val previousOutput = ShortArray(cycleSamples)
    private val policy = AdaptiveJitterPolicy(sampleRate, latencyMultiplier)
    private val recovery = LatencyRecoveryPolicy(sampleRate)
    private val prerollDeadlineMs = maxOf(100L, AudioJitterBufferPolicy.targetMsFor(latencyMultiplier) + 50)
    // Music gets a small reserve while the connection settles, before arrival history exists.
    // Count actual PCM, not time since setup: a precreated sink or a pause must not use it up.
    // Retain this counter across stop/reset so later song changes do not restart the warmup.
    private var startupFramesPlayed = 0L
    private var head = 0
    private var count = 0
    private var inputFrames = 0L
    private var offeredFrames = 0L
    private var resetDiscardedFrames = 0L
    private var started = false
    private var rebanking = false
    private var firstDataMs = -1L
    private var gapFrames = 0
    private var needsFade = true
    private var ended = false
    private var lastReadMs = -1L
    private var pendingOverflow: Overflow? = null
    // Capture the first loss under the bank lock; format/report it on the mixer thread.
    // Normal writes allocate nothing, and repeated overflow cannot grow a diagnostic queue.
    internal data class Overflow(
        val atMs: Long,
        val incomingFrames: Int,
        val depthBeforeFrames: Int,
        val capacityFrames: Int,
        val oldFramesDropped: Int,
        val incomingFramesDropped: Int,
        val offeredBeforeFrames: Long,
        val consumedFrames: Long,
        val compressedFrames: Long,
        val overflowBeforeFrames: Long,
        val resetDiscardedFrames: Long,
        val targetFrames: Int,
        val lastReadMs: Long,
        val started: Boolean,
        val rebanking: Boolean,
        val ended: Boolean
    )
    @Volatile var silentCycles = 0L
        private set
    @Volatile var rebanks = 0L
        private set
    @Volatile var concealedFrames = 0L
        private set
    @Volatile var droppedFrames = 0L
        private set
    @Volatile var compressedFrames = 0L
        private set

    @Synchronized fun noteArrival(nowMs: Long, frames: Int) {
        if (ended) policy.resetArrival()
        ended = false
        policy.onArrival(nowMs, frames)
    }
    @Synchronized fun finish() { ended = true }
    @Synchronized fun targetFrames(): Int = playbackTargetFrames()
    @Synchronized fun maxArrivalGapMs(): Long = policy.largestArrivalGapMs
    @Synchronized fun maxPcmGapMs(): Long = policy.largestPcmGapMs
    @Synchronized fun depthFrames(): Int = count / channels
    @Synchronized fun takeOverflow(): Overflow? = pendingOverflow.also { pendingOverflow = null }
    // An empty network rebank is still a live stream. Parking the device here adds a
    // pause/flush/play cycle to every late packet burst.
    @Synchronized fun isIdle(): Boolean = count == 0 && (ended || (firstDataMs < 0 && !rebanking)) && !started

    @Synchronized fun write(data: ShortArray, length: Int, nowMs: Long) {
        val aligned = length.coerceAtMost(data.size) / channels * channels
        if (aligned == 0) return
        if (!ended) policy.onPcmDelivery(nowMs, aligned / channels)
        if (firstDataMs < 0) firstDataMs = nowMs
        val keep = minOf(aligned, ring.size)
        val skip = aligned - keep
        val oldDrop = (count + keep - ring.size).coerceAtLeast(0)
        if ((oldDrop > 0 || skip > 0) && pendingOverflow == null) {
            pendingOverflow = Overflow(nowMs, aligned / channels, count / channels, capacityFrames,
                oldDrop / channels, skip / channels, offeredFrames, startupFramesPlayed,
                compressedFrames, droppedFrames, resetDiscardedFrames, playbackTargetFrames(), lastReadMs,
                started, rebanking, ended)
        }
        if (oldDrop > 0) discard(oldDrop)
        if (skip > 0) { droppedFrames += skip / channels; needsFade = true; recovery.reset() }
        val tail = (head + count) % ring.size
        val first = minOf(keep, ring.size - tail)
        System.arraycopy(data, skip, ring, tail, first)
        System.arraycopy(data, skip + first, ring, 0, keep - first)
        count += keep
        offeredFrames += aligned / channels
        inputFrames += keep / channels
    }

    /** Renders one 10ms block. Returns true when there is real or concealed audio. */
    @Synchronized fun render(out: ShortArray, nowMs: Long, outputBurstFrames: Int = cycleFrames): Boolean {
        require(out.size >= cycleSamples)
        java.util.Arrays.fill(out, 0, cycleSamples, 0.toShort())
        val target = playbackTargetFrames()
        if (!started) {
            // A short navigation prompt must play even if it can never fill the network target.
            // Media resuming after Stop still needs its learned reserve. The short-prompt
            // escape can otherwise start a sparse first batch and immediately starve again.
            // The same rule applies after starvation, while an explicit end drains below it.
            val waitMs = when {
                isMediaSink && startupFramesPlayed == 0L -> maxOf(prerollDeadlineMs, mediaPrerollDeadlineMs)
                isMediaSink || rebanking -> maxOf(prerollDeadlineMs, target * 1000L / sampleRate + 50)
                else -> prerollDeadlineMs
            }
            if (count == 0 || (!ended && count / channels < target && nowMs - firstDataMs < waitMs)) return false
            started = true
            rebanking = false
            needsFade = true
        }

        // Packets arrive in bursts and the device can drain several mixer cycles at once.
        // Both create normal depth peaks; allow for them before repaying sustained backlog.
        val outputSlack = (outputBurstFrames - cycleFrames).coerceAtLeast(0)
        val slack = maxOf(sampleRate * 30 / 1000, policy.largestChunkFrames - cycleFrames + outputSlack)
        val canRecover = !ended && gapFrames == 0
        recovery.observe(nowMs, target, policy.largestChunkFrames, slack, count / channels,
            inputFrames, canRecover)
        // A late decoder batch is still playable PCM, even at a fixed latency setting.
        // Only write-side physical overflow may discard whole frames. Sustained excess
        // latency is repaid below in small overlaps after a stable rendering window.

        val catchUp = if (canRecover) recovery.correction(nowMs, count / channels) else 0
        val skipped = catchUp * channels
        val real = minOf(count, cycleSamples)
        val readHead = (head + skipped) % ring.size
        val first = minOf(real, ring.size - readHead)
        System.arraycopy(ring, readHead, out, 0, first)
        System.arraycopy(ring, 0, out, first, real - first)
        if (catchUp > 0) {
            // Overlap the original and advanced waveforms for 5ms. The rest keeps its original
            // sample spacing; do not resample every block or change the pitch of normal playback.
            val fadeFrames = cycleFrames / 2
            for (frame in 0 until fadeFrames) {
                val mix = (frame + 1).toFloat() / fadeFrames
                for (ch in 0 until channels) {
                    val index = frame * channels + ch
                    val original = ring[(head + index) % ring.size]
                    out[index] = (original * (1f - mix) + out[index] * mix).toInt().toShort()
                }
            }
            compressedFrames += catchUp
        }
        head = (head + real + skipped) % ring.size
        count -= real + skipped
        recovery.consumed(nowMs, catchUp)
        startupFramesPlayed += real / channels
        if (real > 0) lastReadMs = nowMs

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
            recovery.reset()
        } else {
            recovery.reset()
            if (gapFrames == 0) { policy.onUnderrun(nowMs); rebanks++ }
            silentCycles++
            for (frame in real / channels until cycleFrames) {
                val missingFrame = gapFrames + frame - real / channels
                val block = missingFrame / cycleFrames
                var gain = when (block) { 0 -> 0.7f; 1 -> 0.4f; 2 -> 0.15f; else -> 0f }
                if (block == 2) {
                    // End the final concealed block at zero instead of stepping from 0.15 to silence.
                    val tail = missingFrame % cycleFrames - cycleFrames / 2 + 1
                    if (tail > 0) gain *= 1f - tail.toFloat() / (cycleFrames / 2)
                }
                for (ch in 0 until channels) {
                    out[frame * channels + ch] = (lastGood[(missingFrame % cycleFrames) * channels + ch] * gain).toInt().toShort()
                }
                if (block < 3) concealedFrames++
            }
            gapFrames += cycleFrames - real / channels
            if (gapFrames >= cycleFrames * 3) {
                started = false
                rebanking = true
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
                    // Anchor to the last sample actually emitted. Replaying an earlier tail
                    // would introduce a phase jump at the very first sample of the crossfade.
                    val old = previousOutput[cycleSamples - channels + ch]
                    out[index] = (old * (1f - mix) + out[index] * mix).toInt().toShort()
                }
            }
            needsFade = false
        }
        System.arraycopy(out, 0, previousOutput, 0, cycleSamples)
        return real > 0 || (!ended && real < cycleSamples)
    }

    private fun playbackTargetFrames(): Int {
        // Hold 120ms for the first ten seconds of music, then release 5ms per played second.
        // LatencyRecoveryPolicy repays each small reduction with its existing 1ms overlaps;
        // removing the whole reserve at once would trigger a stale-PCM trim and an audible skip.
        val releaseSteps = (startupFramesPlayed / sampleRate - 10).coerceAtLeast(0)
        val startupMs = if (isMediaSink) (120L - releaseSteps * 5).coerceAtLeast(0) else 0L
        return maxOf(policy.targetFrames, (startupMs * sampleRate / 1000).toInt())
    }

    private fun discard(samples: Int) {
        val drop = samples.coerceAtMost(count) / channels * channels
        head = (head + drop) % ring.size
        count -= drop
        droppedFrames += drop / channels
        needsFade = true
        recovery.reset()
    }

    @Synchronized fun reset() {
        resetDiscardedFrames += count / channels
        head = 0; count = 0; started = false; firstDataMs = -1L; gapFrames = 0
        rebanking = false
        lastReadMs = -1L
        needsFade = true; ended = false
        lastGood.fill(0); previousOutput.fill(0)
        policy.resetArrival()
        recovery.reset()
    }
}
