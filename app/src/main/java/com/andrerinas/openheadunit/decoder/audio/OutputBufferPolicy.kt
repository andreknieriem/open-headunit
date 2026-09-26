package com.andrerinas.openheadunit.decoder.audio

/** Output scheduling jitter only; network jitter is handled before samples reach this buffer. */
internal class OutputBufferPolicy(
    sampleRate: Int,
    private val burstFrames: Int,
    minimumFrames: Int = sampleRate * 20 / 1000
) {
    init { require(sampleRate > 0 && burstFrames > 0 && minimumFrames > 0) }
    private val floorFrames = ((minimumFrames.toLong() + burstFrames - 1) / burstFrames * burstFrames).toInt()
    private val ceilingFrames = maxOf(floorFrames, sampleRate * 60 / 1000 / burstFrames * burstFrames)
    var targetFrames = floorFrames
        private set
    private var previousXruns = 0
    private var lastChangeMs = -1L

    fun update(nowMs: Long, xruns: Int): Int {
        if (lastChangeMs < 0) { lastChangeMs = nowMs; previousXruns = xruns }
        if (xruns > previousXruns) {
            targetFrames = (targetFrames + burstFrames).coerceAtMost(ceilingFrames)
            lastChangeMs = nowMs
        } else if (xruns < previousXruns) {
            // A replaced/restarted stream's counter is not evidence of a stable interval.
            lastChangeMs = nowMs
        } else if (nowMs - lastChangeMs >= 10_000) {
            targetFrames = (targetFrames - burstFrames).coerceAtLeast(floorFrames)
            lastChangeMs = nowMs
        }
        previousXruns = xruns
        return targetFrames
    }
}
