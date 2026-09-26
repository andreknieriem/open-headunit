package com.andrerinas.openheadunit.decoder.audio

/** Output scheduling jitter only; network jitter is handled before samples reach this buffer. */
internal class OutputBufferPolicy(private val sampleRate: Int, private val burstFrames: Int) {
    var targetFrames = sampleRate * 20 / 1000
        private set
    private var previousXruns = 0
    private var lastChangeMs = -1L

    fun update(nowMs: Long, xruns: Int): Int {
        if (lastChangeMs < 0) { lastChangeMs = nowMs; previousXruns = xruns }
        if (xruns > previousXruns) {
            targetFrames = (targetFrames + burstFrames.coerceAtLeast(sampleRate / 100))
                .coerceAtMost(sampleRate * 60 / 1000)
            lastChangeMs = nowMs
        } else if (nowMs - lastChangeMs >= 10_000) {
            targetFrames = (targetFrames - sampleRate * 5 / 1000).coerceAtLeast(sampleRate * 20 / 1000)
            lastChangeMs = nowMs
        }
        previousXruns = xruns
        return targetFrames
    }
}
