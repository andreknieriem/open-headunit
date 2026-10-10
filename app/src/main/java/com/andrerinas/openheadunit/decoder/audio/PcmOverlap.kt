package com.andrerinas.openheadunit.decoder.audio

/** Chooses a forward splice within the recovery policy's existing consumption budget.
 * Compare channels separately: a quiet or opposite-polarity stereo channel must not be
 * hidden by a louder channel or by cancellation in a mono downmix. No PCM is discarded
 * here; the caller accounts for the selected shift only after rendering the overlap. */
internal class PcmOverlap {
    var matched = false
        private set
    var protectAttack = false
        private set

    fun shift(ring: ShortArray, head: Int, channels: Int, overlapFrames: Int, maximum: Int): Int {
        matched = false
        protectAttack = false
        if (maximum <= 0) return 0
        var best = 1
        var bestError = Double.POSITIVE_INFINITY
        var attack = false
        // Prefer useful progress when several offsets match. A fixed 1ms shift can be
        // half a tone's period; blending that offset would almost erase the tone.
        for (shift in maximum downTo 1) {
            var worstChannel = 0.0
            for (ch in 0 until channels) {
                var difference = 0L
                var energy = 0L
                var originalEnergy = 0L
                var advancedEnergy = 0L
                var originalPeak = 0L
                var advancedPeak = 0L
                var originalIndex = head + ch
                var advancedIndex = head + shift * channels + ch
                if (advancedIndex >= ring.size) advancedIndex -= ring.size
                for (frame in 0 until overlapFrames) {
                    val a = ring[originalIndex].toLong()
                    val b = ring[advancedIndex].toLong()
                    difference += (a - b) * (a - b)
                    val aa = a * a
                    val bb = b * b
                    energy += aa + bb
                    if (shift == maximum) {
                        originalEnergy += aa
                        advancedEnergy += bb
                        originalPeak = maxOf(originalPeak, aa)
                        advancedPeak = maxOf(advancedPeak, bb)
                    }
                    originalIndex += channels
                    advancedIndex += channels
                    if (originalIndex >= ring.size) originalIndex -= ring.size
                    if (advancedIndex >= ring.size) advancedIndex -= ring.size
                }
                val error = if (energy == 0L) 0.0 else difference.toDouble() / energy
                worstChannel = maxOf(worstChannel, error)
                if (shift == maximum && (originalPeak * overlapFrames > 64 * originalEnergy ||
                        advancedPeak * overlapFrames > 64 * advancedEnergy)) attack = true
            }
            // For equal-energy signals this corresponds to correlation >= 0.95.
            // Silence matches too; this criterion never gates or mutes quiet input.
            if (worstChannel <= 0.05) { matched = true; return shift }
            if (worstChannel < bestError) { bestError = worstChannel; best = shift }
        }
        // Noise and transients may have no close match. The least mismatched bounded
        // splice still makes progress, but its rate depends on the selected offset; the
        // bank gives these less reliable blends a full recovery quantum between attempts.
        // A narrow, poorly matched attack can lose its peak in a blend. The bank may
        // defer a few render cycles so it passes intact, but must eventually make progress.
        protectAttack = attack && bestError > 0.5
        return best
    }
}
