package com.andrerinas.openheadunit.decoder.audio

/** Same PCM16 score as PcmOverlap, with reusable channel energies and dot products.
 * sum((a-b)^2) = sum(a^2) + sum(b^2) - 2*sum(a*b). Products fit Int, but
 * sums must be Long (a squared sample difference can exceed signed Int).
 * Descending candidate order, strict tie comparison and the worst-channel score are
 * unchanged. No downmix, decimation or approximate candidate selection is involved.
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
        for (shift in maximum - 1 downTo 1) {
            worst = 0.0
            for (ch in 0 until channels) {
                val samples = scratch[ch]
                // Moving the advanced window one frame backward adds its new first sample
                // and removes its old last sample. Original energy stays constant.
                val added = samples[shift].toInt()
                val removed = samples[shift + overlapFrames].toInt()
                advancedEnergy[ch] += (added * added).toLong() - (removed * removed).toLong()
                var dot = 0L
                for (frame in 0 until overlapFrames)
                    dot += (samples[frame].toInt() * samples[frame + shift].toInt()).toLong()
                val energy = originalEnergy[ch] + advancedEnergy[ch]
                val error = if (energy == 0L) 0.0 else (energy - 2 * dot).toDouble() / energy
                worst = maxOf(worst, error)
            }
            if (worst <= 0.05) { matched = true; return shift }
            if (worst < bestError) { bestError = worst; best = shift }
        }
        protectAttack = attack && bestError > 0.5
        return best
    }
}
