package com.andrerinas.openheadunit.decoder.audio

/** Render-clock authorization for a native stream, in ready OUTPUT frames. Source-minus-output
 * is not a correction count: it includes engine lookahead. Keep it out of OHU's splice debt.
 * A permit is valid for at most 100 ms and 4,800 SOURCE frames of this segment. Consequently
 * a burst or a stalled renderer cannot keep using an old acceleration request indefinitely.
 * 1.01 is a requested tempo cap, not a promise about each engine output block's duration. */
internal class NativeTempoPolicy {
    private var segment = 0L
    private var windowStart = -1L
    private var lastObservation = -1L
    private var target = -1
    private var cycles = 0
    private var minimum = Int.MAX_VALUE
    private var eligible = false
    private var permitSegment = -1L
    private var expires = -1L
    private var sourceBudget = 0
    private var reserve = 0

    @Synchronized fun beginSegment() {
        segment++
        clear()
    }
    private fun clear() {
        windowStart = -1
        lastObservation = -1
        target = -1
        cycles = 0
        minimum = Int.MAX_VALUE
        eligible = false
        expires = -1
        sourceBudget = 0
    }

    @Synchronized fun observe(now: Long, targetFrames: Int, depth: Int, burstFrames: Int, active: Boolean) {
        if (!active || target != targetFrames || lastObservation < 0 || now - lastObservation !in 0..499) {
            clear()
            target = targetFrames
        }
        lastObservation = now
        reserve = targetFrames + 240
        if (!active || depth <= reserve) {
            eligible = false
            expires = -1
            sourceBudget = 0
        }
        if (windowStart < 0) windowStart = now
        minimum = minOf(minimum, depth)
        cycles++
        if (now - windowStart >= 500) {
            eligible = active && cycles >= 50 && minimum > targetFrames + maxOf(1440, burstFrames)
            windowStart = now
            cycles = 1
            minimum = depth
        }
        if (eligible && depth > reserve && now >= expires) {
            permitSegment = segment
            expires = now + 100
            sourceBudget = 4800
        }
    }

    @Synchronized fun tempo(now: Long, readyFrames: Int, sourceFrames: Int): Double {
        if (permitSegment != segment || now >= expires || readyFrames <= reserve ||
            sourceFrames > sourceBudget || sourceFrames <= 0) return 1.0
        sourceBudget -= sourceFrames
        return 1.01
    }
}
