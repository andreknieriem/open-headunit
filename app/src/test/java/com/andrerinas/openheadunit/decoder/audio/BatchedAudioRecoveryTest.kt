package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class BatchedAudioRecoveryTest {
    @Test fun `repeated starvation can exceed the normal budget but never the session limit`() {
        val policy = AdaptiveJitterPolicy(48000, 1)
        policy.onArrival(0, 2048)
        assertTrue(policy.targetFrames < 3840)
        repeat(100) { policy.onUnderrun((it + 1) * 100L) }
        assertEquals(400 * 48, policy.targetFrames)
    }

    @Test fun `regular late packet batches stop starving and discarding PCM after adaptation`() {
        // Enough PCM arrives for continuous playback, but TCP/scheduling groups its delivery.
        // A fixed 150ms ceiling used to trim every batch and starve before the next one arrived.
        for (batchMs in intArrayOf(160, 200, 240, 300)) {
            val buffer = AdaptivePcmBuffer(latencyMultiplier = 1)
            val packet = ShortArray(4096) { 12000 }
            val out = ShortArray(960)
            var packetIndex = 0
            var warmGaps = 0L
            var warmConcealed = 0L
            var warmDrops = 0L
            for (now in 0L..60_000L step 10) {
                if (now % batchMs == 0L) {
                    while (now >= packetIndex * 2048L * 1000L / 48000) {
                        buffer.noteArrival(now, 2048)
                        buffer.write(packet, packet.size, now)
                        packetIndex++
                    }
                }
                buffer.render(out, now)
                if (now == 10_000L) {
                    warmGaps = buffer.rebanks
                    warmConcealed = buffer.concealedFrames
                    warmDrops = buffer.droppedFrames
                }
            }
            assertEquals("${batchMs}ms batches must not repeatedly starve", warmGaps, buffer.rebanks)
            assertEquals("${batchMs}ms batches must not repeatedly conceal", warmConcealed, buffer.concealedFrames)
            assertEquals("${batchMs}ms batches must not repeatedly trim", warmDrops, buffer.droppedFrames)
            assertTrue(buffer.targetFrames() >= batchMs * 48)
            assertTrue(buffer.targetFrames() <= 400 * 48)
        }
    }

    @Test fun `a rebank waits for its raised target but a stopped short prompt still drains`() {
        val buffer = AdaptivePcmBuffer()
        buffer.noteArrival(0, 480)
        buffer.write(ShortArray(5760) { 1000 }, 5760, 0)
        val out = ShortArray(960)
        repeat(9) { buffer.render(out, it * 10L) } // exhaust PCM and 30ms concealment
        buffer.noteArrival(240, 2048)
        buffer.write(ShortArray(4096) { 2000 }, 4096, 240)
        assertFalse("the old 100ms short-prompt escape must not abort an adaptive rebank", buffer.render(out, 340))
        buffer.finish()
        assertTrue(buffer.render(out, 341))
    }

    @Test fun `a recovered link returns to its shallow target and repays excess buffered audio`() {
        val buffer = AdaptivePcmBuffer()
        val packet = ShortArray(4096) { 12000 }
        val out = ShortArray(960)
        var packetIndex = 0
        var recoveredGaps = 0L
        for (now in 0L..900_000L) {
            if (now > 10_000 || now % 300 == 0L) {
                while (now >= packetIndex * 2048L * 1000L / 48000) {
                    buffer.noteArrival(now, 2048)
                    buffer.write(packet, packet.size, now)
                    packetIndex++
                }
            }
            if (now % 10 == 0L) buffer.render(out, now)
            if (now == 15_000L) recoveredGaps = buffer.rebanks
        }
        assertEquals(recoveredGaps, buffer.rebanks)
        assertTrue(buffer.targetFrames() < 3840)
        assertTrue(buffer.depthFrames() < buffer.targetFrames() + 480)
        assertTrue(buffer.compressedFrames > 0)
    }
}
