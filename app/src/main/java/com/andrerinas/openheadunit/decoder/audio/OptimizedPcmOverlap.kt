package com.andrerinas.openheadunit.decoder.audio

/** Same PCM16 score as PcmOverlap, with reusable channel energies and exact differences.
 * The largest candidate retains its direct dot-product fast path. Remaining candidates
 * use nonnegative squared differences so a partial sum can safely reject a poor splice.
 * Shift one seeds the bound, but matching candidates and equal scores still prefer the
 * largest shift. No downmix, decimation or approximate candidate selection is involved.
 * The caller restricts this path to the mixer's <=240-frame, <=48-shift mono/stereo
 * windows; other formats use the reference search. Scratch belongs to one bank owner.
 */
internal class OptimizedPcmOverlap {
    var matched = false
        private set
    var protectAttack = false
        private set
    private val originalEnergy = LongArray(2)
    private val advancedEnergy = LongArray(2)
    private val scratch = Array(2) { ShortArray(288) }

    fun shift(ring: ShortArray, head: Int, channels: Int, overlapFrames: Int, maximum: Int): Int {
        matched = false
        protectAttack = false
        var attack = false
        var worst = 0.0
        // Evaluate the largest candidate directly. Silence and immediately matching
        // periodic input return without copying samples or preparing the remaining search.
        for (ch in 0 until channels) {
            var ai = head + ch
            var bi = head + maximum * channels + ch
            if (bi >= ring.size) bi -= ring.size
            var ea = 0L
            var eb = 0L
            var dot = 0L
            var pa = 0
            var pb = 0
            repeat(overlapFrames) {
                val a = ring[ai].toInt()
                val b = ring[bi].toInt()
                val aa = a * a
                val bb = b * b
                ea += aa.toLong()
                eb += bb.toLong()
                dot += (a * b).toLong()
                pa = maxOf(pa, aa)
                pb = maxOf(pb, bb)
                ai += channels
                bi += channels
                if (ai >= ring.size) ai -= ring.size
                if (bi >= ring.size) bi -= ring.size
            }
            originalEnergy[ch] = ea
            advancedEnergy[ch] = eb
            if (pa.toLong() * overlapFrames > 64 * ea || pb.toLong() * overlapFrames > 64 * eb)
                attack = true
            val energy = ea + eb
            val error = if (energy == 0L) 0.0 else (energy - 2 * dot).toDouble() / energy
            worst = maxOf(worst, error)
        }
        if (worst <= 0.05) { matched = true; return maximum }
        var best = maximum
        var bestError = worst
        if (maximum > 1) for (ch in 0 until channels) {
            var index = head + ch
            for (frame in 0 until overlapFrames + maximum) {
                scratch[ch][frame] = ring[index]
                index += channels
                if (index >= ring.size) index -= ring.size
            }
        }
        if (maximum > 1) {
            // Adjacent samples often provide a tight bound for smooth, nonperiodic PCM.
            // Do not return a seed match yet: a larger matching shift takes precedence.
            worst = 0.0
            for (ch in 0 until channels) {
                val samples = scratch[ch]
                val added = samples[overlapFrames].toInt()
                val removed = samples[0].toInt()
                val energy = 2 * originalEnergy[ch] +
                    (added * added).toLong() - (removed * removed).toLong()
                val difference = squaredDifference(samples, overlapFrames, 1, energy,
                    Double.POSITIVE_INFINITY)
                val error = if (energy == 0L) 0.0 else difference.toDouble() / energy
                worst = maxOf(worst, error)
            }
            if (worst < bestError) { bestError = worst; best = 1 }
        }
        for (shift in maximum - 1 downTo 2) {
            // Advance every channel's energy even when another channel rejects a candidate.
            for (ch in 0 until channels) {
                val samples = scratch[ch]
                val added = samples[shift].toInt()
                val removed = samples[shift + overlapFrames].toInt()
                advancedEnergy[ch] += (added * added).toLong() - (removed * removed).toLong()
            }
            worst = 0.0
            val cutoff = maxOf(0.05, bestError)
            var rejected = false
            for (ch in 0 until channels) {
                val energy = originalEnergy[ch] + advancedEnergy[ch]
                val difference = squaredDifference(scratch[ch], overlapFrames, shift, energy, cutoff)
                if (difference < 0L) { rejected = true; break }
                val error = if (energy == 0L) 0.0 else difference.toDouble() / energy
                worst = maxOf(worst, error)
                if (worst > cutoff) { rejected = true; break }
            }
            if (rejected) continue
            if (worst <= 0.05) { matched = true; return shift }
            if (worst < bestError || (worst == bestError && shift > best)) {
                bestError = worst
                best = shift
            }
        }
        matched = bestError <= 0.05
        protectAttack = !matched && attack && bestError > 0.5
        return best
    }

    /** A negative result rejects the candidate; all completed sums are exact PCM16 sums. */
    private fun squaredDifference(
        samples: ShortArray, frames: Int, shift: Int, energy: Long, cutoff: Double
    ): Long {
        var difference = 0L
        var frame = 0
        // Loose bounds on unmatched/noisy input seldom prune early. Score those in one
        // pass rather than adding repeated bound checks to the reference-sized work.
        val chunkSize = if (cutoff < 0.5) 32 else frames
        while (frame < frames) {
            val end = minOf(frame + chunkSize, frames)
            while (frame < end) {
                val delta = (samples[frame].toInt() - samples[frame + shift].toInt()).toLong()
                difference += delta * delta
                frame++
            }
            // Division matches the final score's rounding; strict comparison preserves
            // both the match threshold and equal-score candidates. Zero energy is silence.
            if (frame < frames && energy != 0L && difference.toDouble() / energy > cutoff)
                return -1L
        }
        return difference
    }
}
