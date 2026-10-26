package com.andrerinas.openheadunit.decoder.audio

/** Render-clock control in ready OUTPUT frames, never source-minus-output (engine lookahead).
 * Sustained surplus/shortage requests 0.8..1.2 playback tempo; ordinary packet oscillations
 * remain at unity. Each permit expires after 100ms or 5,760 SOURCE frames and has a separate
 * output-correction budget. A qualified surplus waits for fresh render credit instead
 * of converting the rest of a burst at unity. Invalid/stale proof returns to unity. */
internal class NativeTempoPolicy {
    companion object { const val WAIT_FOR_RENDER = 0.0 }
    private var segment = 0L
    private var windowStart = -1L
    private var lastObservation = -1L
    private var target = -1
    private var cycles = 0
    private var minimum = Int.MAX_VALUE
    private var eligible = false
    private var maximum = 0
    private var requestedTempo = 1.0
    private var permittedTempo = 1.0
    private var lastTempo = 1.0
    private var correctionBudget = 0.0
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
        maximum = 0
        eligible = false
        requestedTempo = 1.0
        revoke()
    }

    private fun revoke() {
        expires = -1
        sourceBudget = 0
        correctionBudget = 0.0
        lastTempo = 1.0
    }
    @Synchronized fun observe(now: Long, targetFrames: Int, depth: Int, burstFrames: Int, active: Boolean) {
        if (!active || target != targetFrames || lastObservation < 0 || now - lastObservation !in 0..499) {
            clear()
            target = targetFrames
        }
        lastObservation = now
        reserve = targetFrames + 240
        if (!active) return
        if (windowStart < 0) windowStart = now
        minimum = minOf(minimum, depth)
        maximum = maxOf(maximum, depth)
        cycles++
        val elapsed = now - windowStart
        if (elapsed >= 500 && (cycles >= 50 || elapsed >= 1000)) {
            val high = targetFrames + maxOf(1440, burstFrames)
            val low = (targetFrames - 1440).coerceAtLeast(0)
            requestedTempo = when {
                cycles < 50 -> 1.0
                minimum > high -> 1.0 + ((minimum - high) / 24000.0).coerceAtMost(0.2)
                maximum < low -> 1.0 - ((low - maximum) / 24000.0).coerceAtMost(0.2)
                else -> 1.0
            }
            eligible = requestedTempo != 1.0
            windowStart = now
            cycles = 1
            minimum = depth
            maximum = depth
        }
        if (!eligible || (requestedTempo > 1.0 && depth <= reserve) ||
            (requestedTempo < 1.0 && depth >= targetFrames)) {
            eligible = false
            revoke()
        } else if (now >= expires) {
            // A direction reversal starts from neutral, never from the previous opposite rate.
            if ((requestedTempo - 1.0) * (lastTempo - 1.0) < 0.0) lastTempo = 1.0
            permitSegment = segment
            permittedTempo = requestedTempo
            expires = now + 100
            sourceBudget = 5760
            correctionBudget = if (permittedTempo > 1.0) (depth - reserve).toDouble()
                else (targetFrames - depth).toDouble()
        }
    }

    @Synchronized fun tempo(now: Long, targetFrames: Int, readyFrames: Int, sourceFrames: Int,
                            active: Boolean): Double {
        // Ingress and render can change eligibility before the next observation.
        if (!active || targetFrames != target || sourceFrames <= 0) {
            clear()
            return 1.0
        }
        // Exhausted authorization is not proof that the remaining burst needs unity.
        // Hold this source block only while fresh playback and live surplus remain.
        // A stale render observation releases this permit wait. Expiry is checked at
        // the decision point; renewal preserves the actual source-clock slew.
        if (eligible && permitSegment == segment && permittedTempo > 1.0 && readyFrames > reserve &&
            now - lastObservation in 0..99 &&
            (now >= expires || sourceFrames > sourceBudget || correctionBudget <= 0.0))
            return WAIT_FOR_RENDER
        if (permitSegment != segment || now >= expires || sourceFrames > sourceBudget ||
            (permittedTempo > 1.0 && readyFrames <= reserve) ||
            (permittedTempo < 1.0 && readyFrames >= target)) {
            // Keep the expiry: exhausting the source budget must not let the next render
            // observation renew this permit early.
            lastTempo = 1.0
            return 1.0
        }
        sourceBudget -= sourceFrames
        // At most 0.02 per 480 source frames: a full 20% change takes at least 100ms of source.
        val step = sourceFrames / 24000.0
        var next = if (lastTempo < permittedTempo) minOf(permittedTempo, lastTempo + step)
            else maxOf(permittedTempo, lastTempo - step)
        val available = minOf(correctionBudget, if (next > 1.0)
            (readyFrames - reserve).coerceAtLeast(0).toDouble()
            else (target - readyFrames).coerceAtLeast(0).toDouble())
        // Clamp the extra/missing OUTPUT duration of this source block, not its source length.
        next = if (next > 1.0) minOf(next, sourceFrames / maxOf(1.0, sourceFrames - available))
            else maxOf(next, sourceFrames / (sourceFrames + available))
        correctionBudget = (available -
            kotlin.math.abs(sourceFrames - sourceFrames / next)).coerceAtLeast(0.0)
        lastTempo = next
        return next
    }
}
