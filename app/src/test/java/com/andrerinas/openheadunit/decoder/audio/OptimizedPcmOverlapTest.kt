package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class OptimizedPcmOverlapTest {
    @Test fun `exact search preserves shift match and attack across PCM extremes and wrap`() {
        val random = java.util.Random(1159)
        val standard = PcmOverlap()
        val fast = PcmOverlap(true)
        for (channels in 1..3) for (overlap in listOf(1, 17, 240, 241)) {
            for (maximum in listOf(0, 1, 2, 7, 48, 49)) repeat(20) { kind ->
                val ring = ShortArray((overlap + maximum + 17) * channels) { index ->
                    when (kind % 7) {
                        0 -> 0
                        1 -> if (index % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE
                        2 -> if (index == 30) Short.MAX_VALUE else 0
                        3 -> (sin(index / channels * 0.17) * if (index % channels == 0) 30000 else 23).toInt().toShort()
                        4 -> 12000
                        5 -> (random.nextInt(5) - 2).toShort()
                        else -> random.nextInt(65536).toShort()
                    }
                }
                val head = random.nextInt(ring.size / channels) * channels
                assertEquals("channels=$channels overlap=$overlap max=$maximum kind=$kind",
                    standard.shift(ring, head, channels, overlap, maximum),
                    fast.shift(ring, head, channels, overlap, maximum))
                assertEquals(standard.matched, fast.matched)
                assertEquals(standard.protectAttack, fast.protectAttack)
            }
        }
    }

    @Test fun `largest matching or tied shift wins over adjacent candidate`() {
        assertSelection(shortArrayOf(10, 11, 11, -10), 1, 3, 2, true)
        assertSelection(shortArrayOf(10, 20, 20, -10), 1, 3, 2, false)
        assertSelection(shortArrayOf(0, 10, 20, 30), 1, 3, 3, false)
        assertSelection(shortArrayOf(10, 11, 20, -10), 1, 3, 1, true)
        assertSelection(shortArrayOf(10, 15, 20, -10), 1, 3, 1, false)
        // Shift three has squared difference 2 and total energy 40: exactly 0.05.
        assertSelection(shortArrayOf(3, 4, 1, 2, 3, 1, 20), 3, 4, 3, true)
    }

    @Test fun `smooth and mismatched stereo preserve exact wrapped selection`() {
        val random = java.util.Random(1159001)
        val standard = PcmOverlap()
        val fast = PcmOverlap(true)
        for (overlap in listOf(32, 33, 63, 64, 65, 239, 240)) {
            for (maximum in listOf(2, 7, 48)) repeat(32) { iteration ->
                val channels = 2
                val frames = overlap + maximum + 11
                val ring = ShortArray(frames * channels)
                val head = random.nextInt(frames) * channels
                val frequency = 0.02 + random.nextDouble() * 0.2
                val attackFrame = random.nextInt(overlap + maximum)
                for (frame in 0 until frames) for (ch in 0 until channels) {
                    val sample = when (iteration % 8) {
                        0 -> (sin(frame * frequency) * if (ch == 0) 30000 else -30000).toInt()
                        1 -> if (ch == 0) (sin(frame * frequency) * 30000).toInt()
                            else random.nextInt(5) - 2
                        2 -> (sin(frame * if (ch == 0) frequency else frequency * 3) *
                            if (ch == 0) 30000 else 23).toInt()
                        3 -> (frame - frames / 2) * if (ch == 0) 80 else -1
                        4 -> if ((frame / 7 + ch) % 2 == 0) -32768 else 32767
                        5 -> random.nextInt(65536) - 32768
                        6 -> if (frame == attackFrame) (if (ch == 0) -32768 else 32767) else 0
                        else -> (sin(frame * frequency) * 25000).toInt() + random.nextInt(101) - 50
                    }
                    ring[(head + frame * channels + ch) % ring.size] = sample.toShort()
                }
                val context = "overlap=$overlap max=$maximum iteration=$iteration head=$head"
                assertEquals(context, standard.shift(ring, head, channels, overlap, maximum),
                    fast.shift(ring, head, channels, overlap, maximum))
                assertEquals(context, standard.matched, fast.matched)
                assertEquals(context, standard.protectAttack, fast.protectAttack)
            }
        }
    }

    private fun assertSelection(
        samples: ShortArray, overlap: Int, maximum: Int, expectedShift: Int, expectedMatch: Boolean
    ) {
        for (channels in 1..2) for (wrap in listOf(false, true)) {
            val ring = ShortArray((samples.size + 3) * channels)
            val head = if (wrap) ring.size - channels else 0
            for (frame in samples.indices) for (ch in 0 until channels) {
                val sample = samples[frame].toInt() * if (ch == 0) 1 else -1
                ring[(head + frame * channels + ch) % ring.size] = sample.toShort()
            }
            for (search in listOf(PcmOverlap(), PcmOverlap(true))) {
                assertEquals(expectedShift, search.shift(ring, head, channels, overlap, maximum))
                assertEquals(expectedMatch, search.matched)
                assertFalse(search.protectAttack)
            }
        }
    }
}
