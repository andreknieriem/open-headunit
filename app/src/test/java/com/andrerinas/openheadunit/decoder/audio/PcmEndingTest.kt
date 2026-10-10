package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PcmEndingTest {
    @Test fun `a stopped tail preserves real samples and reaches zero across every block boundary`() {
        for (extra in listOf(1, 120, 239, 240, 241, 300, 479, 480, 481)) {
            val frames = 480 + extra
            val bank = AdaptivePcmBuffer(latencyMultiplier = 1)
            val input = ShortArray(frames * 2) { if (it % 2 == 0) 12000 else -6000 }
            bank.write(input, input.size, 0)
            bank.finish()
            val result = mutableListOf<Short>()
            var cycles = 0
            while (!bank.isIdle() && cycles < 10) {
                val block = ShortArray(960)
                assertTrue(bank.render(block, cycles++ * 10L))
                result.addAll(block.toList())
            }
            assertTrue("tail did not terminate: $extra", bank.isIdle())
            // Initial playback has its existing fade-in. Ending must not fade valid PCM.
            for (i in 480 * 2 until input.size) assertEquals("extra=$extra sample=$i", input[i], result[i])
            assertTrue("tail missing for $extra", result.size >= (frames + 240) * 2)
            for (ch in 0..1) {
                var previous = input[input.size - 2 + ch].toInt()
                for (frame in frames until result.size / 2) {
                    val value = result[frame * 2 + ch].toInt()
                    assertTrue("end jump extra=$extra frame=$frame", abs(value - previous) <= 50)
                    assertTrue(abs(value) <= abs(previous))
                    previous = value
                }
                assertEquals(0, previous)
            }
            assertEquals(0L, bank.concealedFrames)
            assertEquals(0L, bank.compressedFrames)
            assertEquals(0L, bank.droppedFrames)
        }
    }

    @Test fun `late PCM takes priority over a pending synthetic tail`() {
        for (newArrival in listOf(false, true)) {
            val bank = AdaptivePcmBuffer(latencyMultiplier = 1)
            bank.write(ShortArray(959 * 2) { if (it % 2 == 0) 12000 else -6000 }, 959 * 2, 0)
            bank.finish()
            val out = ShortArray(960)
            bank.render(out, 0)
            bank.render(out, 10) // one tail frame fits; the rest must not delay new PCM
            val previous = out.copyOfRange(958, 960)
            assertFalse(bank.isIdle())
            if (newArrival) bank.noteArrival(20, 480)
            bank.write(ShortArray(960) { if (it % 2 == 0) 6000 else -3000 }, 960, 20)
            bank.finish()
            assertTrue(bank.render(out, 20))
            assertEquals(0, bank.depthFrames())
            assertEquals(-3000, out.last().toInt())
            for (ch in 0..1) {
                val target = if (ch == 0) 6000 else -3000
                val allowed = (abs(target - previous[ch].toInt()) + 239) / 240
                assertTrue(abs(out[ch].toInt() - previous[ch].toInt()) <= allowed)
            }
            repeat(3) { bank.render(out, 30 + it * 10L) }
            assertTrue(bank.isIdle())
            assertEquals(0L, bank.concealedFrames)
        }
    }

    @Test fun `new arrival without decoded PCM does not lose the pending ending`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1)
        bank.write(ShortArray(960) { 12000 }, 960, 0)
        bank.finish()
        val out = ShortArray(960)
        bank.render(out, 0)
        bank.noteArrival(10, 480)
        assertTrue(bank.render(out, 10))
        assertTrue(out[0].toInt() in 1..12000)
        assertEquals(0, out.last().toInt())
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.concealedFrames)
    }

    @Test fun `new playback below preroll keeps its reserve while the old ramp finishes`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1, isMediaSink = true)
        bank.write(ShortArray(960) { 12000 }, 960, 0)
        bank.finish()
        val out = ShortArray(960)
        bank.render(out, 0)
        bank.noteArrival(10, 480)
        bank.write(ShortArray(960) { 6000 }, 960, 10)
        assertTrue(bank.render(out, 10)) // the old ending, not the new undersupplied music
        assertEquals(480, bank.depthFrames())
        assertEquals(0, out.last().toInt())
        assertFalse(bank.render(out, 20))
        assertEquals(480, bank.depthFrames())
        assertEquals(0L, bank.concealedFrames)
        val ready = ShortArray(bank.targetFrames() * 2) { 6000 }
        bank.write(ready, ready.size, 30)
        assertTrue(bank.render(out, 30))
        assertEquals(6000, out.last().toInt())
    }

    @Test fun `reset discards only the synthetic ending state and silent endings stay silent`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1)
        bank.write(ShortArray(960) { 12000 }, 960, 0)
        bank.finish()
        bank.render(ShortArray(960), 0)
        bank.reset()
        val out = ShortArray(960)
        assertTrue(bank.isIdle())
        assertFalse(bank.render(out, 10))
        assertTrue(out.all { it == 0.toShort() })
        bank.write(ShortArray(120), 120, 20)
        bank.finish()
        assertTrue(bank.render(out, 20))
        assertTrue(bank.isIdle())
        assertTrue(out.all { it == 0.toShort() })
    }

    @Test fun `Stop received after the last full render still closes from the audible sample`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 1)
        bank.write(ShortArray(960) { 12000 }, 960, 0)
        val out = ShortArray(960)
        assertTrue(bank.render(out, 500))
        assertEquals(12000, out.last().toInt())
        bank.finish()
        assertTrue(bank.render(out, 510))
        assertEquals(11950, out.first().toInt())
        assertEquals(0, out[478].toInt())
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.concealedFrames)
    }
}
