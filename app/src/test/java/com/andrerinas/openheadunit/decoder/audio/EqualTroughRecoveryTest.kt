package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

/** Equal recurring troughs close a learning excursion; conserved PCM credit is untouched. */
class EqualTroughRecoveryTest {
    private fun time(index: Long, phase: Long): Long {
        val part = (index % 100).toInt()
        return phase + 20 + index / 100 * 2000 +
            if (part < 52) part * 1000L / 52 else 1000 + (part - 52) * 1000L / 48
    }

    @Test fun `an equal recurrent trough ages a one off prefix on either clock`() {
        for (mode in 0..2) for (phase in listOf(0L, 500L, 999L)) {
            val p = AdaptiveJitterPolicy(48000, 2)
            fun offer(now: Long, frames: Int) {
                if (mode != 1) p.onArrival(now, frames)
                if (mode != 0) p.onPcmDelivery(now, frames)
            }
            offer(phase, 14400)
            // 52 x 20ms in the first second, 48 x 20ms in the second: exactly
            // 2000ms of PCM per 2000ms, with no missing or duplicate input.
            for (index in 0L until 10_100L) offer(time(index, phase), 960)
            assertTrue("mode=$mode phase=$phase target=${p.targetFrames / 48.0}",
                p.targetFrames in 60 * 48..100 * 48)
        }
    }

    @Test fun `the real bank repays an old prefix across alternating epoch ranges`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        val prefix = ShortArray(14400 * 2) { 1000 }
        val packet = ShortArray(1920) { 1000 }
        val out = ShortArray(960)
        bank.noteArrival(0, 14400); bank.write(prefix, prefix.size, 0)
        var next = 0L
        var warmRebanks = 0L
        for (now in 0L..202_000L) {
            while (now >= time(next, 0)) {
                bank.noteArrival(now, 960); bank.write(packet, packet.size, now); next++
            }
            if (now % 10 == 0L) bank.render(out, now)
            if (now == 40_000L) warmRebanks = bank.rebanks
        }
        assertTrue("target=${bank.targetFrames() / 48.0}", bank.targetFrames() <= 100 * 48)
        assertTrue("depth=${bank.depthFrames() / 48.0}", bank.depthFrames() <= 120 * 48)
        assertEquals(0L, bank.droppedFrames)
        assertEquals(warmRebanks, bank.rebanks)
        bank.finish()
        var now = 202_010L
        repeat(200) { if (!bank.isIdle()) { bank.render(out, now); now += 10 } }
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.droppedFrames)
    }
}
