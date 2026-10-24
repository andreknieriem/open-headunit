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
}
