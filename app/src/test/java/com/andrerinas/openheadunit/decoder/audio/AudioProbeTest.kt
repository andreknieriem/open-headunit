package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioProbeTest {
    @Test fun `full recorder drops diagnostics without overwriting unread frames`() {
        val probe = AudioProbe(1, "test")
        repeat(6) {
            probe.add(AudioProbe.Counter.OFFERED)
            probe.publish(it.toLong())
        }
        val frames = mutableListOf<Long>()
        probe.drain { frames.add(it.values[AudioProbe.Counter.OFFERED.ordinal]) }
        assertEquals(listOf(1L, 2L, 3L, 4L), frames)
        assertEquals(2L, probe.dropped)
        probe.close(10)
        probe.close(11)
        probe.drain {
            assertEquals(7L, it.sequence)
            assertEquals(6L, it.values[AudioProbe.Counter.OFFERED.ordinal])
            assertEquals(2L, it.dropped)
            assertEquals(1, it.boundary)
        }
    }

    @Test fun `lost final publication is still observable after close`() {
        val probe = AudioProbe(1, "test")
        repeat(4) { probe.publish(it.toLong()) }
        probe.close(4)
        assertTrue(probe.closed)
        assertEquals(1L, probe.dropped)
    }

    @Test fun `publication transfers coherent slots across threads`() {
        val probe = AudioProbe(1, "test")
        val producer = Thread {
            repeat(100_000) {
                probe.set(AudioProbe.Counter.OFFERED, it.toLong())
                probe.set(AudioProbe.Counter.REAL_RENDERED, it.toLong() * 2)
                probe.publish(it.toLong())
            }
            probe.close(100_000)
        }
        var lastSequence = 0L
        var read = 0
        fun drain() = probe.drain {
            assertTrue(it.sequence > lastSequence)
            lastSequence = it.sequence
            assertEquals(it.values[AudioProbe.Counter.OFFERED.ordinal] * 2,
                it.values[AudioProbe.Counter.REAL_RENDERED.ordinal])
            read++
        }
        producer.start()
        while (!probe.closed) { drain(); Thread.yield() }
        producer.join()
        drain()
        assertTrue(read > 0)
    }

    @Test fun `bank instrumentation preserves samples and balances reset and overflow`() {
        val probe = AudioProbe(1, "bank")
        val plain = AdaptivePcmBuffer(isMediaSink = true)
        val measured = AdaptivePcmBuffer(isMediaSink = true, probe = probe)
        val input = ShortArray(4096) { ((it * 193) % 32767).toShort() }
        val a = ShortArray(960)
        val b = ShortArray(960)
        fun checkLedger() {
            measured.sampleDiagnostics(boundary = 3)
            probe.drain { slot ->
                fun value(key: AudioProbe.Counter) = slot.values[key.ordinal]
                assertEquals(value(AudioProbe.Counter.OFFERED),
                    value(AudioProbe.Counter.REAL_RENDERED) + value(AudioProbe.Counter.COMPRESSED) +
                    value(AudioProbe.Counter.DROPPED) + value(AudioProbe.Counter.RESET_DISCARDED) +
                    value(AudioProbe.Counter.DEPTH))
            }
        }
        for (tick in 0..1000) {
            val now = tick * 10L
            if (tick % 73 == 0) { plain.reset(); measured.reset() }
            if (tick % 47 == 0) { plain.finish(); measured.finish() }
            if (tick % 3 == 0) repeat(if (tick % 99 == 0) 40 else 1) {
                plain.write(input, input.size, now)
                measured.write(input, input.size, now)
            }
            assertEquals(plain.render(a, now), measured.render(b, now))
            assertArrayEquals(a, b)
            checkLedger()
        }
    }

    @Test fun `diagnostics do not change recovery decisions through resets and gaps`() {
        val plain = LatencyRecoveryPolicy(48000)
        val measured = LatencyRecoveryPolicy(48000, AudioProbe(1, "test"))
        for (tick in 0..3000) {
            if (tick % 311 == 0) { plain.reset(); measured.reset() }
            val now = tick * 10L + (tick / 401) * 1000L
            val target = if (tick % 137 < 70) 4800 else 9600
            val depth = (tick * 173) % 20000
            val eligible = tick % 67 > 5
            for (policy in listOf(plain, measured)) {
                policy.observe(now, target, 2048, 1568, depth, tick * 480L, eligible)
            }
            val expected = plain.correction(now, depth)
            assertEquals(expected, measured.correction(now, depth))
            plain.consumed(now, expected)
            measured.consumed(now, expected)
        }
    }
}
