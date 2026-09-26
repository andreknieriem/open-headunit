package com.andrerinas.openheadunit.decoder.audio

/** Output scheduling jitter only; network jitter is handled before samples reach this buffer. */
internal class OutputBufferPolicy(
    sampleRate: Int,
    private val burstFrames: Int,
    minimumFrames: Int = sampleRate * 20 / 1000
) {
    init { require(sampleRate > 0 && burstFrames > 0 && minimumFrames > 0) }
    private val floorFrames = ((minimumFrames.toLong() + burstFrames - 1) / burstFrames * burstFrames).toInt()
    // A device with 20ms bursts already needs 60ms for two HAL bursts plus staging. The
    // old 60ms ceiling left it no response to an xrun. Keep two growth steps above its floor.
    val maximumFrames = maxOf(floorFrames + 2 * burstFrames, sampleRate * 60 / 1000 / burstFrames * burstFrames)
    var stableFloorFrames = floorFrames
        private set
    var targetFrames = floorFrames
        private set
    private var previousXruns = 0
    private var lastChangeMs = -1L
    private var reducedFromFrames = 0
    private var reducedAtMs = -1L

    fun update(nowMs: Long, xruns: Int): Int {
        if (lastChangeMs < 0) { lastChangeMs = nowMs; previousXruns = xruns }
        if (xruns > previousXruns) {
            // A failed downward probe is evidence that the previous stable depth was needed.
            // Remember it for this output's lifetime instead of producing a gap every 10s.
            if (reducedFromFrames > 0 && nowMs - reducedAtMs in 0..30_000L) {
                stableFloorFrames = maxOf(stableFloorFrames, reducedFromFrames)
            }
            reducedFromFrames = 0
            targetFrames = maxOf(targetFrames + burstFrames, stableFloorFrames).coerceAtMost(maximumFrames)
            lastChangeMs = nowMs
        } else if (xruns < previousXruns) {
            // A replaced/restarted stream's counter is not evidence of a stable interval.
            lastChangeMs = nowMs
            reducedFromFrames = 0
        } else if (nowMs - lastChangeMs >= 10_000) {
            val smaller = (targetFrames - burstFrames).coerceAtLeast(stableFloorFrames)
            if (smaller < targetFrames) {
                reducedFromFrames = targetFrames
                reducedAtMs = nowMs
                targetFrames = smaller
            }
            lastChangeMs = nowMs
        }
        previousXruns = xruns
        return targetFrames
    }
}
