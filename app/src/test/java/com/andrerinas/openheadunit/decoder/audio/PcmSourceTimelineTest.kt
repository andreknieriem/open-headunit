package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PcmSourceTimelineTest {
    @Test fun `adjacent packet spans retain exact sample progression and reject unknown gaps`() {
        val map = PcmSourceTimeline(4)
        map.append(0, 240, 1_000_000, 1)
        map.append(240, 240, 1_005_000, 1)
        assertEquals(1_000_000L, map.sourceAt(0, 480))
        assertEquals(1L, map.generation)
        map.append(480, 480, Long.MIN_VALUE, 1)
        assertEquals(Long.MIN_VALUE, map.sourceAt(479, 2))
        map.append(960, 480, 1_020_000, 2)
        assertEquals(1_020_000L, map.sourceAt(960))
        assertEquals(2L, map.generation)
        assertEquals(Long.MIN_VALUE, map.sourceAt(Long.MAX_VALUE, 2))
    }

    @Test fun `metadata overflow is unknown not a fabricated oldest timestamp`() {
        val map = PcmSourceTimeline(2)
        repeat(3) { map.append(it * 480L, 480, it * 10_000L, 1) }
        assertEquals(Long.MIN_VALUE, map.sourceAt(0))
        assertEquals(20_000L, map.sourceAt(960))
        map.clear()
        assertEquals(Long.MIN_VALUE, map.sourceAt(960))
    }

    @Test fun `fractional packet and output boundaries retain one sample phase`() {
        val rate = 44100
        val input = PcmSourceTimeline(sampleRate = rate)
        val output = PcmSourceTimeline(sampleRate = rate)
        repeat(64) { packet ->
            val first = packet * 1024L
            input.append(first, 1024, 1_000_000 + first * 1_000_000 / rate, 1)
        }
        repeat(100) { cycle ->
            val first = cycle * 480L
            val source = input.sourceAt(first, 480)
            assertEquals(1_000_000 + first * 1_000_000 / rate, source)
            output.append(first, 480, source, input.generation)
            if (cycle > 0) {
                assertEquals(1_000_000 + (first - 1) * 1_000_000 / rate,
                    output.sourceAt(first - 1, 481))
            }
        }
    }

    @Test fun `a source jump beyond wire quantization stays discontinuous at an unaligned endpoint`() {
        val map = PcmSourceTimeline()
        map.append(0, 1024, 1_000_000, 1)
        map.append(1024, 1024, 1_021_331, 1) // Two microseconds behind the continuous phase.
        assertEquals(Long.MIN_VALUE, map.sourceAt(1023, 2))
        assertEquals(1_021_331L, map.sourceAt(1024))
        map.append(2048, 480, Long.MIN_VALUE, 1)
        map.append(2528, 480, 1_052_664, 1)
        assertEquals(Long.MIN_VALUE, map.sourceAt(2047, 482))
    }

    @Test fun `repeated AAC wire stamps advance by decoded frames including split outputs`() {
        val map = AacSourceTimeline()
        map.append(0, 1_000_000, 1, 0)
        map.append(21_333, 1_000_000, 1, 1)
        assertEquals(1_000_000L, map.decoded(0, 512, 48000))
        assertEquals(1_010_666L, map.decoded(0, 512, 48000))
        assertEquals(1_021_333L, map.decoded(21_333, 1024, 48000))
        assertEquals(1L, map.generation)
        map.append(42_666, 1_000_000, 1, 3) // AU 2 was shed; its duration is not invented.
        assertEquals(Long.MIN_VALUE, map.decoded(42_666, 1024, 48000))
        map.append(64_000, 2_000_000, 1, 4)
        assertEquals(2_000_000L, map.decoded(64_000, 1024, 48000))
    }

    @Test fun `unknown codec keys and rebuild cannot bridge missing decoded duration`() {
        val map = AacSourceTimeline(2)
        map.append(0, 100_000, 1, 0)
        assertEquals(100_000L, map.decoded(0, 1024, 48000))
        assertEquals(Long.MIN_VALUE, map.decoded(99, 1024, 48000))
        map.append(21_333, 100_000, 1, 1)
        assertEquals(Long.MIN_VALUE, map.decoded(21_333, 1024, 48000))
        map.clear()
        map.append(42_666, 100_000, 1, 2)
        assertEquals(Long.MIN_VALUE, map.decoded(42_666, 1024, 48000))
        map.append(64_000, 200_000, 2, 0)
        assertEquals(200_000L, map.decoded(64_000, 1024, 48000))
        assertEquals(2L, map.generation)
    }

    @Test fun `pending AAC before a shed AU cannot absorb the loss boundary of its survivor`() {
        val map = AacSourceTimeline()
        map.append(0, 1_000_000, 1, 0) // Earlier capture A.
        assertEquals(1_000_000L, map.decoded(0, 1024, 48000))
        // B was pending while C (sequence 2) was shed. B starts a new wire batch.
        map.append(21_333, 2_000_000, 1, 1)
        map.append(42_666, 2_000_000, 1, 3) // Surviving D shares B's stamp.
        assertEquals(2_000_000L, map.decoded(21_333, 1024, 48000))
        assertEquals(Long.MIN_VALUE, map.decoded(42_666, 1024, 48000))
        map.append(64_000, 3_000_000, 1, 4)
        assertEquals(3_000_000L, map.decoded(64_000, 1024, 48000))
    }

    @Test fun `disabled decoded output invalidates already submitted repeated stamp progression`() {
        val map = AacSourceTimeline()
        repeat(3) { map.append(it * 21_333L, 1_000_000, 1, it.toLong()) }
        assertEquals(1_000_000L, map.decoded(0, 1024, 48000))
        // AU 2 is played with AV disabled; no new ingress occurs during the toggle.
        map.outputBypassed()
        assertEquals(Long.MIN_VALUE, map.decoded(42_666, 1024, 48000))
        map.append(64_000, 2_000_000, 1, 3)
        assertEquals(2_000_000L, map.decoded(64_000, 1024, 48000))
    }

    @Test fun `disabled split output cannot bridge a hole within the same submitted AU`() {
        val map = AacSourceTimeline()
        map.append(0, 1_000_000, 1, 0)
        assertEquals(1_000_000L, map.decoded(0, 256, 48000))
        map.outputBypassed() // Next 256 decoded frames skipped while AV disabled.
        assertEquals(Long.MIN_VALUE, map.decoded(0, 512, 48000))
        map.append(21_333, 2_000_000, 1, 1)
        assertEquals(2_000_000L, map.decoded(21_333, 1024, 48000))
    }

    @Test fun `lost first AU of a new capture batch cannot reanchor its repeated survivor`() {
        val map = AacSourceTimeline()
        map.append(0, 1_000_000, 1, 0)
        assertEquals(1_000_000L, map.decoded(0, 1024, 48000))
        map.append(21_333, 2_000_000, 1, 2) // New-stamp AU 1 was shed, AU 2 repeats it.
        assertEquals(Long.MIN_VALUE, map.decoded(21_333, 1024, 48000))
        map.append(42_666, 3_000_000, 1, 3)
        assertEquals(3_000_000L, map.decoded(42_666, 1024, 48000))
    }

    @Test fun `losing initial queued input cannot establish a false first source anchor`() {
        val map = AacSourceTimeline()
        map.append(0, 1_000_000, 1, 1) // Sequence zero never reached the codec.
        assertEquals(Long.MIN_VALUE, map.decoded(0, 1024, 48000))
        map.append(21_333, 1_000_000, 1, 2)
        assertEquals(Long.MIN_VALUE, map.decoded(21_333, 1024, 48000))
        map.append(42_666, 2_000_000, 1, 3)
        assertEquals(2_000_000L, map.decoded(42_666, 1024, 48000))
    }

    @Test fun `source discontinuity remains visible and new generation revokes previous progression`() {
        val cursor = AudioSourceCursor()
        assertEquals(0L, cursor.advance(0, 480, 48000, 1))
        assertEquals(10_000L, cursor.advance(0, 480, 48000, 1))
        assertEquals(2_000_000L, cursor.advance(2_000_000, 480, 48000, 1))
        assertEquals(0L, cursor.advance(0, 480, 48000, 2))
        assertEquals(Long.MIN_VALUE, cursor.advance(Long.MAX_VALUE, 480, 48000, 2))
    }

    @Test fun `physical overflow follows retained suffix and fades concealment reset stay unknown`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2).also { it.sourceMappingEnabled = true }
        val out = ShortArray(960)
        val oversized = ShortArray(160_000)
        bank.write(oversized, oversized.size, 0, 1_000_000, 7)
        assertEquals(8_000L, bank.droppedFrames)
        assertEquals(1_166_666L, bank.firstSourceUs())
        bank.finish()
        bank.render(out, 0)
        assertEquals(Long.MIN_VALUE, bank.lastRenderSourceUs) // Overflow/start fade.
        bank.render(out, 10)
        assertEquals(1_176_666L, bank.lastRenderSourceUs)
        assertEquals(7L, bank.lastRenderGeneration)
        bank.reset()
        assertEquals(Long.MIN_VALUE, bank.firstSourceUs())
        bank.write(ShortArray(2880), 2880, 0, 2_000_000, 8)
        bank.render(out, 1000)
        bank.render(out, 1010)
        assertEquals(2_010_000L, bank.lastRenderSourceUs)
        bank.render(out, 1020)
        bank.render(out, 1030)
        assertEquals(Long.MIN_VALUE, bank.lastRenderSourceUs) // PLC has no original source.
    }
}
