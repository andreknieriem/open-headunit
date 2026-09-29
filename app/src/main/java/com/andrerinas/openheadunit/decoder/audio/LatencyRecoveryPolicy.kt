package com.andrerinas.openheadunit.decoder.audio

/** Repay lower targets and sustained backlog with small overlaps. A transient peak is not
 * stale PCM: only a full input/render window can authorize extra consumption. */
internal class LatencyRecoveryPolicy(private val sampleRate: Int) {
    private var previousTarget = 0
    private var previousPacket = 0
    private var budget = 0
    private var debt = 0
    private var excess = 0
    private var windowStart = -1L
    private var windowInput = 0L
    private var windowCycles = 0
    private var lastObservation = -1L
    private var minimumDepth = Int.MAX_VALUE
    private var maximumDepth = 0
    private var allowance = 0
    private var nextCorrection = 0L

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
        lastObservation = nowMs
        if (!canRecover) { debt = 0; resetWindow(); return }
        if (windowStart < 0) { windowStart = nowMs; windowInput = inputFrames }
        minimumDepth = minOf(minimumDepth, depth)
        maximumDepth = maxOf(maximumDepth, depth)
        windowCycles++
        if (nowMs - windowStart >= 500) {
            // HALs can drain several cycles together. Require render progress as well as
            // elapsed time, and use the trough so one decoder burst cannot authorize catch-up.
            if (inputFrames > windowInput && windowCycles >= 50) {
                allowance = (minimumDepth - reserve()).coerceAtLeast(0)
                // The budget describes a peak. Reconstruct at most one packet above the
                // observed trough, capped by the actual peak; an isolated decoder spike
                // must not be mistaken for persistent latency, nor leave a packet of debt.
                excess = (minOf(maximumDepth, minimumDepth + packetFrames) - budget).coerceAtLeast(0)
            } else {
                allowance = 0
                excess = 0
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
        return minOf(sampleRate / 1000, maxOf(debt, excess), allowance,
            (depth - reserve()).coerceAtLeast(0))
    }

    fun consumed(nowMs: Long, frames: Int) {
        if (frames <= 0) return
        debt = (debt - frames).coerceAtLeast(0)
        excess = (excess - frames).coerceAtLeast(0)
        allowance = (allowance - frames).coerceAtLeast(0)
        // Keep historical troughs in the same frame position as the current bank.
        if (minimumDepth != Int.MAX_VALUE) minimumDepth = (minimumDepth - frames).coerceAtLeast(0)
        maximumDepth = (maximumDepth - frames).coerceAtLeast(0)
        nextCorrection = nowMs + 100
    }

    // Keep a render cycle plus 5ms beyond the expected trough of the packet sawtooth.
    private fun reserve() = maxOf(sampleRate / 100, previousTarget - previousPacket) + sampleRate / 200

    fun reset() {
        previousTarget = 0; previousPacket = 0; budget = 0
        debt = 0; nextCorrection = 0L; lastObservation = -1L
        resetWindow()
    }

    private fun resetWindow() {
        allowance = 0; excess = 0; windowStart = -1L
        windowCycles = 0
        minimumDepth = Int.MAX_VALUE; maximumDepth = 0
    }
}
