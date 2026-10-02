package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class LongSupplyCreditTest {
    @Test fun `a balanced long delivery train retains credit for its scheduled gap`() {
        for (phase in listOf(0L, 500L, 999L)) {
            val policy = AdaptiveJitterPolicy(48000, 2)
            policy.onArrival(phase, 960); policy.onPcmDelivery(phase, 960)
            policy.onArrival(phase + 250, 960); policy.onPcmDelivery(phase + 250, 960)
            for (period in 0L..100L) {
                // 120 * 20ms of audio every 2400ms. The 18ms delivery spacing
                // builds enough credit for the final 258ms gap; no supply is missing.
                for (part in 0L until 120L) {
                    val now = phase + 330 + period * 2400 + part * 18
                    policy.onArrival(now, 960)
                    policy.onPcmDelivery(now, 960)
                }
            }
            assertTrue("phase=$phase target=${policy.targetFrames / 48.0}ms",
                policy.targetFrames in 258 * 48..300 * 48)
        }
    }

    @Test fun `credit survives a paced plateau within a long balanced train`() {
        for (phase in listOf(0L, 500L, 999L)) {
            val policy = AdaptiveJitterPolicy(48000, 2)
            for (period in 0L..50L) {
                val start = phase + period * 5400
                val times = (0L until 60L).map { start + it * 18 } +
                    (0L until 150L).map { start + 1080 + it * 20 } +
                    (0L until 60L).map { start + 4080 + it * 18 }
                for (now in times) {
                    policy.onArrival(now, 960)
                    policy.onPcmDelivery(now, 960)
                }
            }
            assertTrue("phase=$phase target=${policy.targetFrames / 48.0}ms",
                policy.targetFrames in 258 * 48..300 * 48)
        }
    }

    @Test fun `old catchup cannot hide a later gap after the target has recovered`() {
        for (pcmOnly in listOf(false, true)) {
            val policy = AdaptiveJitterPolicy(48000, 2)
            fun deliver(now: Long, frames: Int) {
                if (pcmOnly) policy.onPcmDelivery(now, frames) else policy.onArrival(now, frames)
            }
            deliver(0, 14400)
            for (now in 20L..90_000L step 20) deliver(now, 960)
            assertTrue(policy.targetFrames <= 80 * 48)
            deliver(90_300, 960)
            assertTrue("pcmOnly=$pcmOnly target=${policy.targetFrames / 48.0}ms",
                policy.targetFrames >= 300 * 48)
        }
    }
}
