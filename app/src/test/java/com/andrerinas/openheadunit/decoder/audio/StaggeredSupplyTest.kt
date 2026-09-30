package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

/** Synthetic producer schedules, including catch-up; no acoustic latency claim. */
class StaggeredSupplyTest {
    @Test fun `a real bank recovers after staggered ingress or PCM catchup`() {
        for (spacing in listOf(1L, 4L, 8L)) {
            for (splitIngress in listOf(false, true)) for (splitPcm in listOf(false, true)) {
                val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
                val full = ShortArray(3840) { 1000 }
                val half = ShortArray(1920) { 1000 }
                val out = ShortArray(960)
                var arrival = 0L
                var decoded = 0L
                fun at(index: Long, split: Boolean): Long {
                    val group = if (split) index / 2 else index
                    return group / 2 * 80 + (group % 2) * 20 +
                        if (split) (index % 2) * spacing else 0
                }
                for (now in 0L..90_000L) {
                    if (now !in 10_000L..10_249L) {
                        while (at(arrival, splitIngress) <= now) {
                            bank.noteArrival(now, if (splitIngress) 960 else 1920)
                            arrival++
                        }
                        while (at(decoded, splitPcm) <= now) {
                            val data = if (splitPcm) half else full
                            bank.write(data, data.size, now)
                            decoded++
                        }
                    }
                    if (now % 10 == 0L) bank.render(out, now)
                }
                val context = "spacing=$spacing ingress=$splitIngress pcm=$splitPcm"
                assertTrue("$context target=${bank.targetFrames()}", bank.targetFrames() <= 85 * 48)
                assertTrue("$context depth=${bank.depthFrames()}", bank.depthFrames() <= 110 * 48)
                assertEquals("$context dropped", 0L, bank.droppedFrames)
                assertEquals("$context rebanks", 1L, bank.rebanks)
            }
        }
    }

    @Test fun `fragmenting a short guidance prefix does not move its startup target`() {
        for (spacing in listOf(0L, 1L, 4L, 8L)) {
            val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = false)
            val chunk = ShortArray(1920) { 1000 }
            val out = ShortArray(960)
            for (part in 0L..2L) {
                bank.noteArrival(part * spacing, 960)
                bank.write(chunk, chunk.size, part * spacing)
            }
            assertTrue("spacing=$spacing target=${bank.targetFrames()}", bank.render(out, 2 * spacing))
            assertEquals(0L, bank.rebanks)
        }
    }

    @Test fun `a single catchup ages out even when later pacing never drains virtual credit`() {
        for (phase in listOf(0L, 990L, 999L, 1000L)) {
            val policy = AdaptiveJitterPolicy(48000, 2)
            policy.onArrival(phase, 14400); policy.onPcmDelivery(phase, 14400)
            for (now in phase + 20..phase + 90_000 step 20) {
                policy.onArrival(now, 960); policy.onPcmDelivery(now, 960)
            }
            assertTrue("phase=$phase target=${policy.targetFrames}", policy.targetFrames <= 80 * 48)
        }
    }

    @Test fun `submillisecond frame remainders do not turn pacing into repeated disturbances`() {
        for (rate in listOf(16000, 44100, 48000)) {
            val policy = AdaptiveJitterPolicy(rate, 2)
            policy.onUnderrun(0)
            for (packet in 0L..8000L) {
                val now = packet * 1024 * 1000 / rate
                policy.onArrival(now, 1024)
                policy.onPcmDelivery(now, 720)
                policy.onPcmDelivery(now + 1, 304)
            }
            val upperFrames = maxOf(rate * 80 / 1000, 1024 + rate * 25 / 1000 + 2)
            assertTrue("rate=$rate target=${policy.targetFrames}", policy.targetFrames <= upperFrames)
        }
    }
}
