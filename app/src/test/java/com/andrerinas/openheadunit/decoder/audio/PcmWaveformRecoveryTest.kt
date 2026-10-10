package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PcmWaveformRecoveryTest {
    private fun tone(frames: Int, start: Int = 0, frequency: Double = 500.0, amplitude: Int = 256) =
        ShortArray(frames * 2) {
            val value = (sin(2 * PI * frequency * (start + it / 2) / 48000) * amplitude).roundToInt()
            (if (it % 2 == 0) value else -value).toShort()
        }

    @Test fun `half-period cancellation is avoided for quiet stereo music while backlog is repaid`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1, isMediaSink = true)
        val out = ShortArray(960)
        var supplied = 0
        var corrections = 0
        var minimumRatio = 1.0
        var previousSample = 0
        var maximumStep = 0
        repeat(3500) { cycle ->
            val now = cycle * 10L
            while (supplied < now * 48 + 7680) {
                val packet = tone(2048, supplied)
                bank.noteArrival(now, 2048)
                bank.write(packet, packet.size, now)
                supplied += 2048
            }
            val before = bank.compressedFrames
            assertTrue(bank.render(out, now))
            if (bank.compressedFrames > before) {
                corrections++
                val rms = sqrt((100 until 140).sumOf { out[it * 2].toDouble().pow(2) } / 40)
                minimumRatio = minOf(minimumRatio, rms / (256 / sqrt(2.0)))
            }
            for (frame in 0 until 480) {
                val sample = out[frame * 2].toInt()
                maximumStep = maxOf(maximumStep, abs(sample - previousSample))
                previousSample = sample
                assertEquals(0, sample + out[frame * 2 + 1])
            }
        }
        assertTrue("corrections=$corrections", corrections > 20)
        // The old fixed half-period shift reached 0.075. Natural phase variation across
        // this short measurement window is allowed; a near-silent middle is not.
        assertTrue("minimum RMS ratio=$minimumRatio", minimumRatio > 0.8)
        assertTrue("maximum adjacent step=$maximumStep", maximumStep <= 20)
        assertTrue("repaid=${bank.compressedFrames}", bank.compressedFrames > 4000)
        assertTrue("remaining=${bank.depthFrames()}", bank.depthFrames() < 5000)
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.concealedFrames)
        assertEquals(0L, bank.droppedFrames)
    }

    @Test fun `matching considers quiet channels and ring wrap without changing stereo offset`() {
        val source = ShortArray(800 * 2) { index ->
            val frequency = if (index % 2 == 0) 500.0 else 997.0
            val amplitude = if (index % 2 == 0) 24000 else 128
            (sin(2 * PI * frequency * (index / 2) / 48000) * amplitude).roundToInt().toShort()
        }
        val wrapped = ShortArray(source.size)
        val head = source.size - 100
        source.indices.forEach { wrapped[(head + it) % source.size] = source[it] }
        val shift = PcmOverlap().shift(source, 0, 2, 240, 48)
        assertEquals(shift, PcmOverlap().shift(wrapped, head, 2, 240, 48))
        assertTrue(shift in 1..48)
        for (ch in 0..1) {
            var difference = 0.0
            var energy = 0.0
            repeat(240) { frame ->
                val a = source[frame * 2 + ch].toDouble()
                val b = source[(frame + shift) * 2 + ch].toDouble()
                difference += (a - b).pow(2)
                energy += a * a + b * b
            }
            assertTrue("channel=$ch error=${difference / energy}", difference / energy <= 0.05)
        }
        assertEquals(48, PcmOverlap().shift(ShortArray(1600), 0, 2, 240, 48))
        assertEquals(0, PcmOverlap().shift(source, 0, 2, 240, 0))
    }

    @Test fun `unmatched noise still repays sustained rate skew without overflow or concealment`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1, isMediaSink = true)
        val random = java.util.Random(1234)
        val out = ShortArray(960)
        var supplied = 0
        var lastCorrection = -100L
        var minimumRmsRatio = 1.0
        repeat(12_000) { cycle ->
            val now = cycle * 10L
            // A sender 500ppm faster than the output, plus an opening bank.
            while (supplied < now * 48.024 + 7680) {
                val packet = ShortArray(4096) { (random.nextInt(2049) - 1024).toShort() }
                bank.noteArrival(now, 2048)
                bank.write(packet, packet.size, now)
                supplied += 2048
            }
            val before = bank.compressedFrames
            assertTrue(bank.render(out, now))
            if (bank.compressedFrames > before) {
                assertTrue("dense unmatched corrections at $now", now - lastCorrection >= 100)
                lastCorrection = now
                val rms = sqrt((60 until 180).sumOf { out[it * 2].toDouble().pow(2) } / 120)
                minimumRmsRatio = minOf(minimumRmsRatio, rms / (1024 / sqrt(3.0)))
            }
            assertTrue(out.all { it.toInt() in -1024..1024 })
        }
        assertTrue(bank.compressedFrames > 2880)
        assertTrue("remaining=${bank.depthFrames()}", bank.depthFrames() < 6000)
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.concealedFrames)
        assertEquals(0L, bank.droppedFrames)
        // Linear blending of unmatched noise can still dip, but never repeat every
        // render and never approach the near-zero cancellation reproduced with a tone.
        assertTrue("noise RMS ratio=$minimumRmsRatio", minimumRmsRatio > 0.5)
    }

    @Test fun `an isolated attack passes intact before recovery resumes`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        val control = AdaptivePcmBuffer(latencyMultiplier = 16)
        val initial = ShortArray(30_000 * 2)
        control.noteArrival(0, 480)
        control.write(initial, initial.size, 0)
        initial[(51 * 480 + 120) * 2] = 1000
        initial[(51 * 480 + 120) * 2 + 1] = -500
        bank.noteArrival(0, 480)
        bank.write(initial, initial.size, 0)
        val packet = ShortArray(960)
        val out = ShortArray(960)
        val controlOut = ShortArray(960)
        for (now in 0L..2000L step 10) {
            if (now > 0) {
                for (buffer in listOf(bank, control)) {
                    buffer.noteArrival(now, 480); buffer.write(packet, packet.size, now)
                }
            }
            val before = bank.compressedFrames
            val controlBefore = control.compressedFrames
            assertTrue(bank.render(out, now))
            assertTrue(control.render(controlOut, now))
            if (now == 510L) {
                // Same timing/depth without the attack authorizes correction now. This
                // distinguishes a real deferral from simply having no recovery proposal.
                assertTrue(control.compressedFrames > controlBefore)
                assertEquals(before, bank.compressedFrames)
                assertEquals(1000, out[240].toInt())
                assertEquals(-500, out[241].toInt())
            }
            if (now == 520L) assertTrue(bank.compressedFrames > 0)
        }
        assertTrue(bank.compressedFrames > 0)
        assertEquals(0L, bank.rebanks)
    }

    @Test fun `repeated high crest attacks cannot defer recovery indefinitely under rate skew`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1, isMediaSink = true)
        val out = ShortArray(960)
        var supplied = 0
        repeat(12_000) { cycle ->
            val now = cycle * 10L
            while (supplied < now * 48.024 + 7680) {
                val packet = ShortArray(4096) {
                    if ((supplied + it / 2) % 480 == 120) 1000 else 0
                }
                bank.noteArrival(now, 2048)
                bank.write(packet, packet.size, now)
                supplied += 2048
            }
            assertTrue(bank.render(out, now))
        }
        assertTrue(bank.compressedFrames > 2880)
        assertTrue("remaining=${bank.depthFrames()}", bank.depthFrames() < 6000)
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.concealedFrames)
        assertEquals(0L, bank.droppedFrames)
    }
}
