package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTimingWindowTest {
    @Test fun `progress uses observation span and frames rather than write sample count`() {
        val w = AudioTimingWindow()
        w.output(1, 4800, 1000, 4458, 100)
        w.writeResult(960, false)
        w.writeResult(0, true)
        w.writeResult(-6, false)
        w.output(1, 5280, 1480, 4458, 1_000_000_100)
        val s = w.snapshot()
        assertTrue(s.continuous)
        assertEquals(1_000_000_000L, s.outputSpanNs)
        assertEquals(480L, s.writtenDelta)
        assertEquals(480L, s.headDelta)
        assertEquals(3800L, s.queueMin)
        assertEquals(3800L, s.queueMax)
        assertEquals(960L, s.acceptedShorts)
        assertEquals(3L, s.writes)
        assertEquals(1L, s.zeroWrites)
        assertEquals(1L, s.admissionRejected)
        assertEquals(1L, s.errors)
    }

    @Test fun `replacement flush and invalid positions cannot produce a playback rate`() {
        for (kind in 0..4) {
            val w = AudioTimingWindow()
            w.output(1, 100, 50, 80, 10)
            when (kind) {
                0 -> w.output(2, 110, 60, 80, 20) // New epoch, even with larger counters.
                1 -> w.output(1, 90, 40, 80, 20)
                2 -> w.output(1, 100, -1, 80, 20)
                3 -> w.output(1, 100, 101, 80, 20)
                4 -> w.output(1, 200, 60, 80, 20)
            }
            w.output(1, 120, 70, 80, 30)
            val s = w.snapshot()
            assertFalse("case $kind", s.continuous)
            assertEquals(-1L, s.headDelta)
            assertEquals(-1L, s.outputSpanNs)
        }
    }

    @Test fun `a snapshot clears every counter and leaves the previous snapshot stable`() {
        val w = AudioTimingWindow()
        val phase = AudioTimingWindow.Phase.RETRY_SLEEP
        w.record(phase, 2_000_000)
        w.record(phase, 5_000_000)
        w.output(1, 100, 50, 80, 10)
        w.writeResult(960, false)
        val first = w.snapshot()
        assertFalse(first.continuous) // One head observation cannot establish a rate.
        assertEquals(2L, first.counts[phase.ordinal])
        assertEquals(7_000_000L, first.totalNs[phase.ordinal])
        assertEquals(5_000_000L, first.maxNs[phase.ordinal])
        val empty = w.snapshot()
        assertEquals(0L, empty.counts.sum())
        assertEquals(0L, empty.acceptedShorts)
        assertEquals(0L, empty.writes)
        assertEquals(-1L, empty.queueMin)
        assertEquals(-1L, empty.queueMax)
        assertEquals(-1, empty.track)
        w.record(phase, 1)
        assertEquals(7_000_000L, first.totalNs[phase.ordinal])
    }
}
