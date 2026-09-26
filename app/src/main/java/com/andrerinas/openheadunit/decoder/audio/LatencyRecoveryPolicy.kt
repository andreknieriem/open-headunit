package com.andrerinas.openheadunit.decoder.audio

/** Repay decreases in the network target only when a full observation window has spare PCM.
 * The minimum depth over 500ms filters packet sawtooth; at most 1ms is removed every 100ms. */
internal class LatencyRecoveryPolicy(private val sampleRate: Int) {
    private var previousTarget = 0
    private var debt = 0
    private var windowStart = -1L
    private var minimumDepth = Int.MAX_VALUE
    private var allowance = 0
    private var nextCorrection = 0L

    fun correction(nowMs: Long, target: Int, packetFrames: Int, depth: Int): Int {
        if (previousTarget > target) debt = (debt + previousTarget - target).coerceAtMost(sampleRate)
        if (target > previousTarget) resetObservation()
        previousTarget = target
        if (windowStart < 0) windowStart = nowMs
        minimumDepth = minOf(minimumDepth, depth)
        // Keep a render cycle plus 5ms beyond the expected trough of the packet sawtooth.
        val reserve = maxOf(sampleRate / 100, target - packetFrames) + sampleRate / 200
        if (nowMs - windowStart >= 500) {
            allowance = (minimumDepth - reserve).coerceAtLeast(0)
            minimumDepth = depth
            windowStart = nowMs
        }
        if (nowMs < nextCorrection || debt == 0) return 0
        val frames = minOf(sampleRate / 1000, debt, allowance,
            (depth - maxOf(reserve, sampleRate / 100)).coerceAtLeast(0))
        if (frames > 0) {
            debt -= frames
            allowance -= frames
            nextCorrection = nowMs + 100
        }
        return frames
    }

    fun reset() { previousTarget = 0; resetObservation() }
    private fun resetObservation() {
        debt = 0; allowance = 0; windowStart = -1L; minimumDepth = Int.MAX_VALUE
        nextCorrection = 0L
    }
}
