package com.andrerinas.openheadunit.decoder.audio

/**
 * How much audio to hold before a sink may play, sized against the chunks the phone actually sends.
 *
 * A target that is not at least one arrival chunk above the drain quantum cannot hold: the level
 * troughs below the floor once per arrival and the sink re-banks forever, which is a dropout every
 * few hundred milliseconds on a link with no jitter at all. [holdsAgainst] is that invariant.
 */
object AudioJitterBufferPolicy {

    /** Start shallow, then let observed underruns deepen this session's cushion. */
    const val DEFAULT_MULTIPLIER = 2

    /** Depth to bank, in milliseconds of playback, at [ANCHOR_MULTIPLIER]. */
    const val TARGET_MS = 200L

    /**
     * The latency setting [TARGET_MS] is written for. The scaling anchor, and **not** the default:
     * the shipped default is [DEFAULT_MULTIPLIER].
     *
     * Named for a default once, which is the mistake this file is here not to repeat. 8 still means
     * 200 ms so a user who saved it keeps exactly what they had.
     */
    const val ANCHOR_MULTIPLIER = 8

    /**
     * Floor on the dial, for a stored setting that makes no sense. The per-channel invariant in
     * [targetFrames] is what actually stops a shallow pick being fragile.
     */
    const val MIN_TARGET_MS = 25L

    /** Slack above one arrival chunk, so the target absorbs jitter rather than only surviving. */
    const val JITTER_MARGIN_MS = 100L

    /**
     * Ceiling on the target, and on the dial. A channel whose chunks are large should not delay a
     * spoken prompt further than it must, and past this the audio is audibly behind the picture,
     * which is a worse fault than the one a deeper cushion fixes. Never applied below the
     * invariant: [holdsAgainst] wins.
     */
    const val MAX_TARGET_MS = 400L

    /**
     * Extra depth a re-bank takes over the opening bank. Sized over the longest disturbance
     * measured on the rig rather than guessed.
     */
    const val REBANK_MARGIN_MS = 150L

    /**
     * How much deeper each step of [deepenedTargetFrames] goes.
     *
     * One AAP audio message is 42.7 ms, so a step is a little over two of them: enough to change the
     * answer, small enough that a sink which needed one step does not overshoot into lag.
     */
    const val DEEPEN_STEP_MS = 100L

    /** Never bank more than this share of the buffer, so the next write always has room. */
    const val MAX_FILL_NUMERATOR = 3
    const val MAX_FILL_DENOMINATOR = 4

    /**
     * The cushion the latency setting asks for, in milliseconds.
     *
     * [targetFrames] still floors this by the arrival invariant, so a shallow pick cannot produce a
     * target that re-banks forever; the dial buys depth at the top rather than fragility at the
     * bottom.
     */
    fun targetMsFor(latencyMultiplier: Int): Long {
        val m = latencyMultiplier.coerceAtLeast(1)
        return (TARGET_MS * m / ANCHOR_MULTIPLIER).coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
    }

    /** Keep the existing deep settings while allowing the low-latency picks a smaller margin. */
    fun jitterMarginMsFor(latencyMultiplier: Int): Long =
        (JITTER_MARGIN_MS * latencyMultiplier.coerceIn(1, ANCHOR_MULTIPLIER) / ANCHOR_MULTIPLIER)
            .coerceAtLeast(10L)

    fun framesFor(sampleRateInHz: Int, ms: Long): Int =
        if (sampleRateInHz <= 0) 0 else (sampleRateInHz.toLong() * ms / 1000L).toInt()

    /** [chunkBytes] is PCM. An encoded chunk divided by a PCM frame's width is not a frame count. */
    fun arrivalChunkFrames(chunkBytes: Int, bytesPerFrame: Int): Int =
        if (bytesPerFrame > 0) (chunkBytes / bytesPerFrame).coerceAtLeast(1) else 1

    /**
     * Whether a target can survive steady state: one arrival interval drains one chunk's worth, so
     * the trough is [targetFrames] minus a chunk and it has to still serve a full drain.
     */
    fun holdsAgainst(targetFrames: Int, arrivalChunkFrames: Int, drainQuantumFrames: Int): Boolean =
        targetFrames - arrivalChunkFrames >= drainQuantumFrames

    /**
     * Frames to bank before playing, for a sink draining [drainQuantumFrames] at a time out of a
     * buffer of [capacityFrames]. Zero or negative inputs fall back to the time-based figure.
     */
    fun targetFrames(
        sampleRateInHz: Int,
        arrivalChunkFrames: Int,
        drainQuantumFrames: Int,
        capacityFrames: Int,
        latencyMultiplier: Int = ANCHOR_MULTIPLIER
    ): Int {
        if (sampleRateInHz <= 0) return 1
        val byTime = framesFor(sampleRateInHz, targetMsFor(latencyMultiplier))
        val chunk = arrivalChunkFrames.coerceAtLeast(0)
        val drain = drainQuantumFrames.coerceAtLeast(0)
        // One chunk is what a steady stream troughs by, the drain is what a cycle still has to
        // serve, and the margin is the only part that is jitter rather than arithmetic.
        val byArrival = chunk + drain + framesFor(sampleRateInHz, jitterMarginMsFor(latencyMultiplier))
        var target = maxOf(byTime, byArrival)
        // A channel sending 256 ms chunks cannot be banked in 200 ms, but it should not be banked
        // in a second either. The invariant is restored underneath the ceiling.
        target = minOf(target, framesFor(sampleRateInHz, MAX_TARGET_MS)).coerceAtLeast(chunk + drain)
        if (capacityFrames > 0) {
            // A buffer too small to hold the invariant is a configuration to report, not to work
            // around: capacityIsSufficient() is what says so.
            val byBuffer = capacityFrames / MAX_FILL_DENOMINATOR * MAX_FILL_NUMERATOR
            target = minOf(target, byBuffer)
        }
        return target.coerceAtLeast(1)
    }

    /**
     * Frames to bank when a sink that was already playing has to bank again.
     *
     * Deeper than the opening bank on purpose. Measured: a re-bank of 200 ms against a disturbance
     * of 203 to 233 ms underran again inside the same window, two to four times over. The opening
     * bank is a person waiting to hear something; a re-bank is a gap they have already heard.
     */
    fun rebankTargetFrames(
        targetFrames: Int,
        sampleRateInHz: Int,
        capacityFrames: Int,
        latencyMultiplier: Int = ANCHOR_MULTIPLIER
    ): Int {
        if (sampleRateInHz <= 0) return targetFrames.coerceAtLeast(1)
        val marginMs = REBANK_MARGIN_MS * latencyMultiplier.coerceIn(1, ANCHOR_MULTIPLIER) / ANCHOR_MULTIPLIER
        val wanted = minOf(
            targetFrames + framesFor(sampleRateInHz, marginMs),
            framesFor(sampleRateInHz, MAX_TARGET_MS)
        )
        val byBuffer = if (capacityFrames > 0) {
            capacityFrames / MAX_FILL_DENOMINATOR * MAX_FILL_NUMERATOR
        } else {
            wanted
        }
        return minOf(wanted, byBuffer).coerceAtLeast(targetFrames.coerceAtLeast(1))
    }

    /**
     * The target after [steps] deepenings, for a sink that keeps re-banking at the depth it was
     * given. See [AudioUnderrunRecoveryPolicy.shouldDeepen] for when that is asked.
     *
     * Bounded by [MAX_TARGET_MS] and by the fill share, so a sink can climb out of a cushion that
     * was too shallow and can never climb into audio that is behind the picture. At the shipped
     * deep end the target already sits on the ceiling.
     */
    fun deepenedTargetFrames(
        targetFrames: Int,
        sampleRateInHz: Int,
        capacityFrames: Int,
        steps: Int,
        latencyMultiplier: Int = ANCHOR_MULTIPLIER
    ): Int {
        if (sampleRateInHz <= 0 || steps <= 0) return targetFrames.coerceAtLeast(1)
        val stepMs = DEEPEN_STEP_MS * latencyMultiplier.coerceIn(1, ANCHOR_MULTIPLIER) / ANCHOR_MULTIPLIER
        val wanted = targetFrames + framesFor(sampleRateInHz, stepMs * steps)
        var capped = minOf(wanted, framesFor(sampleRateInHz, MAX_TARGET_MS))
        if (capacityFrames > 0) {
            capped = minOf(capped, capacityFrames / MAX_FILL_DENOMINATOR * MAX_FILL_NUMERATOR)
        }
        return capped.coerceAtLeast(targetFrames.coerceAtLeast(1))
    }

    /**
     * Whether [capacityFrames] can hold a target that holds at all. A buffer this small is a
     * configuration to report, not one to work around.
     */
    fun capacityIsSufficient(
        capacityFrames: Int,
        arrivalChunkFrames: Int,
        drainQuantumFrames: Int
    ): Boolean {
        if (capacityFrames <= 0) return false
        val byBuffer = capacityFrames / MAX_FILL_DENOMINATOR * MAX_FILL_NUMERATOR
        return holdsAgainst(byBuffer, arrivalChunkFrames, drainQuantumFrames)
    }
}
