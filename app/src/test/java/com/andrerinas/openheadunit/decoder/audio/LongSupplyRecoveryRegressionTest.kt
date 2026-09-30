package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

/** Deterministic software schedules; not a recording of the phone or the speaker. */
class LongSupplyRecoveryRegressionTest {
    private fun train(count: Int, phase: Long, extraPrefix: Boolean = false): Int {
        val p = AdaptiveJitterPolicy(48000, 2)
        if (extraPrefix) {
            p.onArrival(phase, 14400); p.onPcmDelivery(phase, 14400)
        } else {
            p.onArrival(phase, 960); p.onPcmDelivery(phase, 960)
            p.onArrival(phase + 250, 960); p.onPcmDelivery(phase + 250, 960)
        }
        val period = count * 20L
        for (cycle in 0L..100L) for (part in 0 until count) {
            // Exactly 20ms/call on average; accumulate 240ms gradually, then spend it.
            val offset = part * (period - 240) / count
            val now = phase + 330 + cycle * period + offset
            p.onArrival(now, 960); p.onPcmDelivery(now, 960)
        }
        return p.targetFrames
    }

    @Test fun `long trains do not depend on a fixed epoch lifetime`() {
        for (count in listOf(120, 240, 480, 1200)) for (phase in listOf(0L, 500L, 999L)) {
            val target = train(count, phase)
            assertTrue("count=$count phase=$phase target=${target / 48.0}",
                target in 258 * 48..300 * 48)
        }
    }

    @Test fun `old catchup is not refreshed by a later long balanced train`() {
        for (count in listOf(120, 240, 480, 1200)) for (phase in listOf(0L, 999L)) {
            val target = train(count, phase, true)
            assertTrue("count=$count phase=$phase target=${target / 48.0}",
                target in 258 * 48..300 * 48)
        }
    }

    @Test fun `old catchup cannot mask a later large gap on either observation clock`() {
        for (ingress in listOf(false, true)) {
            val p = AdaptiveJitterPolicy(48000, 2)
            p.onArrival(0, 14400); p.onPcmDelivery(0, 14400)
            for (now in 20L..90_000L step 20) {
                p.onArrival(now, 960); p.onPcmDelivery(now, 960)
            }
            assertTrue(p.targetFrames <= 80 * 48)
            if (ingress) p.onArrival(90_300, 960) else p.onPcmDelivery(90_300, 960)
            assertTrue("ingress=$ingress target=${p.targetFrames}", p.targetFrames >= 300 * 48)
            assertTrue(p.targetFrames <= 400 * 48)
        }
    }

    @Test fun `long balanced train also retains PCM and settles in the real bank`() {
        for (count in listOf(120, 480, 1200)) for (phase in listOf(0L, 500L, 999L)) {
            val period = count * 20L
            val end = phase + 330 + 101 * period
            val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
            val packet = ShortArray(1920) { 12000 }; val out = ShortArray(960)
            var next = 0L; var afterWarmup = 0L
            for (now in 0L..end) {
                if (now == phase || now == phase + 250) {
                    bank.noteArrival(now, 960); bank.write(packet, packet.size, now)
                }
                while (now >= phase + 330 + next / count * period + next % count * (period - 240) / count) {
                    bank.noteArrival(now, 960); bank.write(packet, packet.size, now); next++
                }
                if (now % 10 == 0L) bank.render(out, now)
                if (now == 30_000L) afterWarmup = bank.rebanks
            }
            assertEquals(0L, bank.droppedFrames)
            assertEquals("count=$count phase=$phase", afterWarmup, bank.rebanks)
            assertTrue(bank.targetFrames() in 258 * 48..300 * 48)
            bank.finish()
            var time = end + 10
            repeat(200) { if (!bank.isIdle()) { bank.render(out, time); time += 10 } }
            assertTrue(bank.isIdle())
            assertEquals(0L, bank.droppedFrames)
        }
    }
}
