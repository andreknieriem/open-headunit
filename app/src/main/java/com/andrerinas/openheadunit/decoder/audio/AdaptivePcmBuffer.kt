package com.andrerinas.openheadunit.decoder.audio

/** A bounded network bank, independent of the device output buffer. One producer and one reader;
 * short synchronized copies also make reset safe during sink stop. All samples are interleaved. */
internal class AdaptivePcmBuffer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    latencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
    private val isMediaSink: Boolean = false,
    private val nativeTimeCorrection: Boolean = false
) {
    private val mediaPrerollDeadlineMs = 1500L
    private val cycleFrames = sampleRate / 100
    private val cycleSamples = cycleFrames * channels
    // Guidance can receive catch-up bursts too, even while its consumer is running.
    // Every sink needs room for a 1s burst plus its <=400ms reserve. Capacity is not
    // preroll: the playback target/deadline stay independent of this storage bound.
    private val capacityFrames = sampleRate * 3 / 2
    private val ring = ShortArray(capacityFrames * channels)
    private val lastGood = ShortArray(cycleSamples)
    private val previousOutput = ShortArray(cycleSamples)
    private val endingSample = ShortArray(channels)
    private var endingFrames = 0
    private val policy = AdaptiveJitterPolicy(sampleRate, latencyMultiplier)
    private val recovery = LatencyRecoveryPolicy(sampleRate)
    private val overlap = PcmOverlap(optimized = true)
    private var recoveryDeferrals = 0
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
    // A new prompt can arrive before a stopped tail has drained. Keep the closed prefix
    // in the same ring, but do not let its running state bypass the new prompt's preroll.
    // -1 means no boundary; zero still closes the last emitted block before new PCM arrives.
    private var closedPrefixSamples = -1
    private var nextSegmentFirstDataMs = -1L
    private var lastReadMs = -1L
    private var lastArrivalMs = -1L
    private var lastPcmMs = -1L
    private var pendingStarvation: Starvation? = null
    /** Capture at the first short read, before a later packet changes depth or gap maxima.
     * Output budget is capacity, not a measurement of queued hardware audio.
     */
    internal data class Starvation(
        val atMs: Long, val availableFrames: Int, val targetFrames: Int,
        val lastArrivalAgeMs: Long?, val lastPcmAgeMs: Long?, val outputBudgetFrames: Int
    )
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
    // Historical name: counts new underrun events, even if the next packet avoids a rebank.
    @Volatile var rebanks = 0L
        private set
    @Volatile var rebankEntries = 0L
        private set
    private var reserveWindowStartMs = -1L
    private var reserveLastRenderMs = -1L
    private var pendingStoppedReserve: ReserveWindow? = null
    private var skippedReserveWindows = 0L
    private var reserveCycles = 0
    private var reserveMinimumFrames = Int.MAX_VALUE
    private var reserveMaximumFrames = 0

    /** Post-render depths over a bounded observation window, not a sample of packet peaks.
     * Cumulative event totals distinguish isolated short shortages from actual refill waits.
     * This measures the receiver bank only; it does not establish an audible glitch or clock ppm.
     */
    internal data class ReserveWindow(
        val windowMs: Long, val cycles: Int, val minimumFrames: Int, val maximumFrames: Int,
        val depthFrames: Int, val targetFrames: Int, val underrunEvents: Long,
        val rebankEntries: Long, val concealedFrames: Long, val compressedFrames: Long,
        val stopped: Boolean = false, val skippedWindows: Long = 0
    )

    // Continuous playback needs one summary per minute; Stop still flushes short prompts.
    // Keep extrema for the whole window so slower logging cannot hide a brief low reserve.
    @Synchronized fun takeReserveWindow(nowMs: Long): ReserveWindow? {
        pendingStoppedReserve?.let {
            pendingStoppedReserve = null
            val snapshot = it.copy(skippedWindows = skippedReserveWindows)
            skippedReserveWindows = 0
            return snapshot
        }
        if (reserveWindowStartMs < 0 || nowMs - reserveWindowStartMs < 60_000 || reserveCycles == 0) return null
        val snapshot = reserveSnapshot(nowMs)
        clearReserveWindow()
        return snapshot
    }

    private fun reserveSnapshot(nowMs: Long, stopped: Boolean = false) =
        ReserveWindow((nowMs - reserveWindowStartMs).coerceAtLeast(0), reserveCycles,
            reserveMinimumFrames, reserveMaximumFrames, count / channels, playbackTargetFrames(),
            rebanks, rebankEntries, concealedFrames, compressedFrames, stopped)

    private fun clearReserveWindow() {
        reserveWindowStartMs = -1
        reserveLastRenderMs = -1
        reserveCycles = 0
        reserveMinimumFrames = Int.MAX_VALUE
        reserveMaximumFrames = 0
    }

    @Volatile var concealedFrames = 0L
        private set
    @Volatile var droppedFrames = 0L
        private set
    @Volatile var compressedFrames = 0L
        private set

    @Synchronized fun noteArrival(nowMs: Long, frames: Int) {
        if (ended) {
            policy.resetArrival()
            lastPcmMs = -1L
            if (count > 0 || started) {
                closedPrefixSamples = count
                nextSegmentFirstDataMs = -1L
            }
        }
        ended = false
        lastArrivalMs = nowMs
        policy.onArrival(nowMs, frames)
    }
    @Synchronized fun finish() {
        ended = true
        // All queued segments are now complete and may drain together, even below target.
        closedPrefixSamples = -1
        nextSegmentFirstDataMs = -1L
        // Preserve even a short prompt's live depths before its intentional drain reaches
        // zero. One pending slot bounds storage; report skipped summaries rather than
        // silently replacing the first observation when several Stops beat the log poll.
        if (reserveCycles > 0) {
            if (pendingStoppedReserve == null)
                pendingStoppedReserve = reserveSnapshot(reserveLastRenderMs, stopped = true)
            else skippedReserveWindows++
        }
        clearReserveWindow()
    }
    @Synchronized fun targetFrames(): Int = playbackTargetFrames()
    @Synchronized fun maxArrivalGapMs(): Long = policy.largestArrivalGapMs
    @Synchronized fun maxPcmGapMs(): Long = policy.largestPcmGapMs
    @Synchronized fun depthFrames(): Int = count / channels
    @Synchronized fun takeOverflow(): Overflow? = pendingOverflow.also { pendingOverflow = null }
    @Synchronized fun takeStarvation(): Starvation? = pendingStarvation.also { pendingStarvation = null }
    // An empty network rebank is still a live stream. Parking the device here adds a
    // pause/flush/play cycle to every late packet burst.
    @Synchronized fun isIdle(): Boolean =
        count == 0 && endingFrames == 0 && (ended || (firstDataMs < 0 && !rebanking)) && !started

    /** Native output has already spent CPU and can contain a final prompt tail. Apply
     * backpressure to its worker instead of overwriting earlier ready PCM on a burst. */
    @Synchronized fun writeIfFits(data: ShortArray, length: Int, nowMs: Long): Boolean {
        require(length in 0..data.size && length % channels == 0)
        if (length > ring.size - count) return false
        write(data, length, nowMs)
        return true
    }

    @Synchronized fun write(data: ShortArray, length: Int, nowMs: Long) {
        val aligned = length.coerceAtMost(data.size) / channels * channels
        writeFrom(aligned, nowMs) { sourceOffset, destinationOffset, samples ->
            System.arraycopy(data, sourceOffset, ring, destinationOffset, samples)
        }
    }

    @Synchronized fun writeIfFits(data: TimeStretchBlock, length: Int, nowMs: Long): Boolean {
        require(length in 0..data.size && length % channels == 0)
        if (length > ring.size - count) return false
        writeFrom(length, nowMs) { sourceOffset, destinationOffset, samples ->
            data.get(sourceOffset, ring, destinationOffset, samples)
        }
        return true
    }

    // Copy ready PCM while holding the bank monitor, never run native DSP under it.
    // All sources share the same delivery, overflow and segment accounting.
    private inline fun writeFrom(aligned: Int, nowMs: Long, copy: (Int, Int, Int) -> Unit) {
        if (aligned == 0) return
        lastPcmMs = nowMs
        if (closedPrefixSamples >= 0 && nextSegmentFirstDataMs < 0) nextSegmentFirstDataMs = nowMs
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
        copy(skip, tail, first)
        if (keep > first) copy(skip + first, 0, keep - first)
        count += keep
        offeredFrames += aligned / channels
        inputFrames += keep / channels
    }

    // Only the first shortage can still be avoided without changing PLC/refill semantics.
    // Stop and a closing prefix are deliberate ends, not missing input to wait for.
    @Synchronized fun wouldConceal(): Boolean =
        started && !ended && closedPrefixSamples < 0 && gapFrames == 0 && count < cycleSamples

    /** Ready-output control must not treat preroll, a closed prefix or PLC as stable playback. */
    @Synchronized fun canTimeCorrect(): Boolean = timeCorrectionActive()

    private fun timeCorrectionActive(): Boolean =
        started && !ended && closedPrefixSamples < 0 && gapFrames == 0 && !needsFade

    // Bank -> policy is the sole nested lock order. Keep the snapshot/decision atomic,
    // without allocating per block; native DSP always runs after this monitor is released.
    @Synchronized fun observeTimeCorrection(policy: NativeTempoPolicy, nowMs: Long, burstFrames: Int) =
        policy.observe(nowMs, playbackTargetFrames(), count / channels, burstFrames, timeCorrectionActive())

    @Synchronized fun timeCorrectionTempo(policy: NativeTempoPolicy, nowMs: Long, sourceFrames: Int): Double =
        policy.tempo(nowMs, playbackTargetFrames(), count / channels, sourceFrames, timeCorrectionActive())

    var lastRenderRealFrames = 0
        private set

    /** Renders one 10ms block. Returns true when there is real or concealed audio. */
    @Synchronized fun render(out: ShortArray, nowMs: Long, outputBurstFrames: Int = cycleFrames): Boolean {
        lastRenderRealFrames = 0
        require(out.size >= cycleSamples)
        java.util.Arrays.fill(out, 0, cycleSamples, 0.toShort())
        val target = playbackTargetFrames()
        val waitMs = prerollWaitMs(target)
        if (closedPrefixSamples >= 0 &&
            ((count - closedPrefixSamples) / channels >= target ||
                (nextSegmentFirstDataMs >= 0 && nowMs - nextSegmentFirstDataMs >= waitMs))) {
            // Ready input can follow the closed tail seamlessly, in original sample order.
            closedPrefixSamples = -1
            firstDataMs = nextSegmentFirstDataMs
            nextSegmentFirstDataMs = -1L
        }
        val drainingPrefix = closedPrefixSamples >= 0
        if (!started) {
            // A short navigation prompt must play even if it can never fill the network target.
            // Media resuming after Stop still needs its learned reserve. The short-prompt
            // escape can otherwise start a sparse first batch and immediately starve again.
            // The same rule applies after starvation, while an explicit end drains below it.
            if (!drainingPrefix && (count == 0 ||
                    (!ended && count / channels < target && nowMs - firstDataMs < waitMs))) {
                if (endingFrames == 0) return false
                renderEnding(out, 0)
                System.arraycopy(out, 0, previousOutput, 0, cycleSamples)
                return true
            }
            // Ready PCM takes precedence over a synthetic ending. A new Start with too
            // little PCM still gets its normal preroll while the old ramp finishes.
            endingFrames = 0
            started = true
            rebanking = false
            needsFade = true
        }

        // Packets arrive in bursts and the device can drain several mixer cycles at once.
        // Both create normal depth peaks; allow for them before repaying sustained backlog.
        val outputSlack = (outputBurstFrames - cycleFrames).coerceAtLeast(0)
        val slack = maxOf(sampleRate * 30 / 1000, policy.largestChunkFrames - cycleFrames + outputSlack)
        val canRecover = !nativeTimeCorrection && !ended && !drainingPrefix && gapFrames == 0 && !needsFade
        recovery.observe(nowMs, target, policy.largestChunkFrames, slack, count / channels,
            inputFrames, canRecover)
        // A late decoder batch is still playable PCM, even at a fixed latency setting.
        // Only write-side physical overflow may discard whole frames. Sustained excess
        // latency is repaid below in small overlaps after a stable rendering window.

        val proposal = if (canRecover) recovery.correction(nowMs, count / channels) else 0
        var catchUp = overlap.shift(ring, head, channels, cycleFrames / 2,
            minOf(proposal, (count / channels - cycleFrames).coerceAtLeast(0)))
        if (catchUp > 0 && overlap.protectAttack && recoveryDeferrals < 3) {
            // Let an isolated attack pass without splitting its peak. Three render cycles
            // is the limit: a continuous click train must not disable recovery forever.
            recoveryDeferrals++
            catchUp = 0
        } else if (catchUp > 0 || proposal == 0) recoveryDeferrals = 0
        val skipped = catchUp * channels
        val real = minOf(count, cycleSamples, if (drainingPrefix) closedPrefixSamples else count)
        lastRenderRealFrames = real / channels
        val closesPrefix = drainingPrefix && real == closedPrefixSamples
        val readHead = (head + skipped) % ring.size
        val first = minOf(real, ring.size - readHead)
        System.arraycopy(ring, readHead, out, 0, first)
        System.arraycopy(ring, 0, out, first, real - first)
        if (catchUp > 0) {
            // Match the offset before overlapping for 5ms. The rest keeps its original
            // sample spacing; all channels use the same shift to preserve stereo timing.
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
        if (drainingPrefix) closedPrefixSamples -= real
        // Unmatched noise retains the old 100ms spacing; short failed matches must not
        // turn a 5ms crossfade dip into modulation on every 10ms render cycle.
        recovery.consumed(nowMs, catchUp, if (overlap.matched) catchUp else sampleRate / 1000)
        startupFramesPlayed += real / channels
        if (real > 0) lastReadMs = nowMs

        val recovering = gapFrames > 0
        if (real == cycleSamples) {
            System.arraycopy(out, 0, lastGood, 0, cycleSamples)
            gapFrames = 0
        } else if (ended || closesPrefix) {
            // Sink Stop is a deliberate end, not packet loss. Drain even a partial final block.
            gapFrames = 0
            lastGood.fill(0)
            recovery.reset()
        } else {
            recovery.reset()
            if (gapFrames == 0) {
                if (pendingStarvation == null) {
                    pendingStarvation = Starvation(nowMs, real / channels, target,
                        lastArrivalMs.takeIf { it >= 0 }?.let { (nowMs - it).coerceAtLeast(0) },
                        lastPcmMs.takeIf { it >= 0 }?.let { (nowMs - it).coerceAtLeast(0) }, outputBurstFrames)
                }
                policy.onUnderrun(nowMs)
                rebanks++
            }
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
                rebankEntries++
                firstDataMs = if (count > 0) nowMs else -1L
                gapFrames = 0
                needsFade = true
                lastGood.fill(0)
            }
        }

        // Blend a new live position into the tail that was actually heard. This also fades back
        // from PLC and fades initial playback in, without adding a block of latency.
        if (needsFade || recovering || (real < cycleSamples && !ended && !closesPrefix)) {
            val fadeFrames = cycleFrames / 2
            val liveFrames = if (ended || closesPrefix) minOf(fadeFrames, real / channels) else fadeFrames
            for (frame in 0 until liveFrames) {
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
        var endingRendered = false
        if (closesPrefix || (ended && count == 0)) {
            // Stop is input completion, not a command to truncate the last word. Preserve
            // every real sample, then append a 5ms ramp from the actual final output sample.
            // The ramp can cross a render boundary; pending frames keep focus/drain accounting
            // active until it is emitted. Synthetic samples never count as consumed PCM.
            for (ch in 0 until channels) endingSample[ch] = if (real > 0)
                out[real - channels + ch] else previousOutput[cycleSamples - channels + ch]
            endingFrames = if (endingSample.any { it != 0.toShort() }) cycleFrames / 2 else 0
            started = false
            firstDataMs = if (closesPrefix) nextSegmentFirstDataMs else -1L
            closedPrefixSamples = -1
            nextSegmentFirstDataMs = -1L
            endingRendered = renderEnding(out, real / channels) > 0
            lastGood.fill(0)
        }
        // Observe every rendered cycle. Sampling once per log line can repeatedly hit a
        // packet peak and conceal the gradual loss of reserve between capture batches.
        if (!ended && !drainingPrefix) {
            if (reserveWindowStartMs < 0) reserveWindowStartMs = nowMs
            reserveLastRenderMs = nowMs
            reserveCycles++
            reserveMinimumFrames = minOf(reserveMinimumFrames, count / channels)
            reserveMaximumFrames = maxOf(reserveMaximumFrames, count / channels)
        }
        System.arraycopy(out, 0, previousOutput, 0, cycleSamples)
        return real > 0 || endingRendered || (!ended && !closesPrefix && real < cycleSamples)
    }

    private fun prerollWaitMs(target: Int): Long = when {
        isMediaSink && startupFramesPlayed == 0L -> maxOf(prerollDeadlineMs, mediaPrerollDeadlineMs)
        isMediaSink || rebanking || policy.hasCarriedReserve ->
            maxOf(prerollDeadlineMs, target * 1000L / sampleRate + 50)
        else -> prerollDeadlineMs
    }

    private fun renderEnding(out: ShortArray, offsetFrames: Int): Int {
        val frames = minOf(endingFrames, cycleFrames - offsetFrames)
        repeat(frames) { frame ->
            endingFrames--
            for (ch in 0 until channels) {
                out[(offsetFrames + frame) * channels + ch] =
                    (endingSample[ch].toInt() * endingFrames / (cycleFrames / 2)).toShort()
            }
        }
        return frames
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
        if (closedPrefixSamples >= 0) {
            closedPrefixSamples = (closedPrefixSamples - drop).coerceAtLeast(0)
            if (closedPrefixSamples == 0) {
                closedPrefixSamples = -1
                started = false
                firstDataMs = nextSegmentFirstDataMs
                nextSegmentFirstDataMs = -1L
            }
        }
        droppedFrames += drop / channels
        needsFade = true
        recovery.reset()
    }

    @Synchronized fun reset() {
        clearReserveWindow()
        pendingStoppedReserve = null
        skippedReserveWindows = 0
        pendingStarvation = null
        lastArrivalMs = -1L; lastPcmMs = -1L
        resetDiscardedFrames += count / channels
        head = 0; count = 0; started = false; firstDataMs = -1L; gapFrames = 0
        rebanking = false
        lastReadMs = -1L
        needsFade = true; ended = false
        closedPrefixSamples = -1; nextSegmentFirstDataMs = -1L
        endingFrames = 0
        recoveryDeferrals = 0
        lastGood.fill(0); previousOutput.fill(0)
        policy.resetArrival(retainReserve = false)
        recovery.reset()
    }
}
