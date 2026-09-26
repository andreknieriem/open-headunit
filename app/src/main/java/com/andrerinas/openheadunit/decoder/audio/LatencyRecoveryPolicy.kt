package com.andrerinas.openheadunit.decoder.audio

/** Target recovery and the temporary hard-trim limit are separate: aging a packet estimate
 * must not discard PCM that fitted the previous budget. Only actual extra consumption repays
 * recovery; unused trim headroom expires after a full window of normal input and rendering. */
internal class LatencyRecoveryPolicy(private val sampleRate: Int) {
    private var previousTarget = 0
    private var previousPacket = 0
    private var budget = 0
    var trimLimit = 0
        private set
    private var debt = 0
    private var guardWork = 0
    private var windowStart = -1L
    private var windowInput = 0L
    private var windowCycles = 0
    private var lastObservation = -1L
    private var minimumDepth = Int.MAX_VALUE
    private var maximumDepth = 0
    private var allowance = 0
    private var nextCorrection = 0L

    /** Must run before the caller tests the hard-trim limit. Input is a cumulative PCM count. */
    fun observe(nowMs: Long, target: Int, packetFrames: Int, slack: Int, depth: Int,
                inputFrames: Long, canRecover: Boolean = true) {
        val newBudget = (target.toLong() + slack).coerceIn(0, sampleRate.toLong()).toInt()
        if (target > previousTarget) debt = 0
        else debt = (debt + previousTarget - target).coerceAtMost(sampleRate)
        if (target != previousTarget || packetFrames != previousPacket || newBudget != budget ||
            lastObservation < 0 || nowMs - lastObservation !in 0..499) resetWindow()
        previousTarget = target
        previousPacket = packetFrames
        budget = newBudget
        // Never grant extra headroom merely because a new burst increased depth. Repeated
        // policy changes take a maximum, not a sum, so the grace cannot accumulate.
        trimLimit = maxOf(trimLimit, budget)
        lastObservation = nowMs
        if (!canRecover) { debt = 0; resetWindow(); return }
        if (windowStart < 0) { windowStart = nowMs; windowInput = inputFrames }
        minimumDepth = minOf(minimumDepth, depth)
        maximumDepth = maxOf(maximumDepth, depth)
        windowCycles++
        if (nowMs - windowStart >= 500) {
            // Some HALs drain many 10ms cycles together. Require actual render progress,
            // rather than imposing one device's maximum interval between mixer wakeups.
            if (inputFrames > windowInput && windowCycles >= 50) {
                allowance = (minimumDepth - reserve()).coerceAtLeast(0)
                guardWork = (minOf(maximumDepth, trimLimit) - budget).coerceAtLeast(0)
                if (maximumDepth <= budget) trimLimit = budget
            } else {
                allowance = 0
                guardWork = 0
            }
            minimumDepth = depth
            maximumDepth = depth
            windowStart = nowMs
            windowInput = inputFrames
            windowCycles = 1
        }
    }

    /** A proposal only; settle it after the ring actually consumes these extra frames. */
    fun correction(nowMs: Long, depth: Int): Int {
        if (nowMs < nextCorrection) return 0
        return minOf(sampleRate / 1000, maxOf(debt, guardWork), allowance,
            (depth - reserve()).coerceAtLeast(0))
    }

    fun consumed(nowMs: Long, frames: Int) {
        if (frames <= 0) return
        debt = (debt - frames).coerceAtLeast(0)
        guardWork = (guardWork - frames).coerceAtLeast(0)
        allowance = (allowance - frames).coerceAtLeast(0)
        trimLimit = maxOf(budget, trimLimit - frames)
        // Put earlier depth observations in the same frame position as the current bank.
        // Otherwise a peak sampled before catch-up could charge for the same PCM again.
        if (minimumDepth != Int.MAX_VALUE) minimumDepth = (minimumDepth - frames).coerceAtLeast(0)
        maximumDepth = (maximumDepth - frames).coerceAtLeast(0)
        nextCorrection = nowMs + 100
    }

    // Keep a render cycle plus 5ms beyond the expected trough of the packet sawtooth.
    private fun reserve() = maxOf(sampleRate / 100, previousTarget - previousPacket) + sampleRate / 200

    fun reset() {
        previousTarget = 0; previousPacket = 0; budget = 0; trimLimit = 0
        debt = 0; nextCorrection = 0L; lastObservation = -1L
        resetWindow()
    }

    private fun resetWindow() {
        allowance = 0; guardWork = 0; windowStart = -1L
        windowCycles = 0
        minimumDepth = Int.MAX_VALUE; maximumDepth = 0
    }
}
