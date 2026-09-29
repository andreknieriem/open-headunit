package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class ReviewedAudioRegressionTest {
    @Test fun `legacy default preserves a deep bank while explicit low latency remains available`() {
        val defaultMusic = AdaptivePcmBuffer(isMediaSink = true)
        val lowMusic = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        for (bank in listOf(defaultMusic, lowMusic)) {
            repeat(3) {
                bank.noteArrival(it * 43L, 2048)
                bank.write(ShortArray(4096) { 1000 }, 4096, it * 43L)
            }
        }
        assertEquals(19200, defaultMusic.targetFrames())
        assertFalse(defaultMusic.render(ShortArray(960), 100))
        assertEquals(5760, lowMusic.targetFrames())
        assertTrue(lowMusic.render(ShortArray(960), 100))
    }

    @Test fun `reviewed old tablet bursts keep every PCM frame within the one second ring`() {
        // Effective AudioTrack sizes and the 74ms, 78ms and 50ms drops from the PR logs.
        for ((outputFrames, extraFrames) in listOf(3343 to 3552, 3343 to 3776, 2229 to 2432)) {
            val bank = AdaptivePcmBuffer(latencyMultiplier = 16, isMediaSink = true)
            val frames = 19200 + extraFrames
            val source = ShortArray(frames * 2) {
                val value = (1000 + it / 2 % 10000).toShort()
                if (it % 2 == 0) value else (-value).toShort()
            }
            var written = 0
            var now = 0L
            while (written < frames) {
                val count = minOf(1024, frames - written)
                bank.noteArrival(now, count)
                val chunk = source.copyOfRange(written * 2, (written + count) * 2)
                bank.write(chunk, chunk.size, now)
                written += count
                now += 21
            }
            val out = ShortArray(960)
            assertEquals(0L, bank.droppedFrames)
            assertTrue(bank.render(out, now, outputFrames))
            assertEquals(frames - 480, bank.depthFrames())
            assertEquals(source[958], out[958])
            assertEquals(source[959], out[959])
            bank.finish()
            var emitted = 480
            while (bank.depthFrames() >= 480) {
                now += 10
                assertTrue(bank.render(out, now, outputFrames))
                emitted += 480
                assertEquals(source[emitted * 2 - 2], out[958])
                assertEquals(source[emitted * 2 - 1], out[959])
            }
            if (bank.depthFrames() > 0) bank.render(out, now + 10, outputFrames)
            assertEquals(0, bank.depthFrames())
            assertEquals(0L, bank.droppedFrames)
            assertEquals(0L, bank.compressedFrames)
            assertEquals(0L, bank.concealedFrames)
        }
    }

    @Test fun `persistent decoder backlog recovers gradually without discards or rebuffering`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16, isMediaSink = true)
        val out = ShortArray(960)
        val packet = ShortArray(960) { 1000 }
        bank.noteArrival(0, 480)
        bank.write(ShortArray(60000) { 1000 }, 60000, 0)
        var previousDepth = bank.depthFrames()
        for (now in 0L..30_000L step 10) {
            if (now > 0) { bank.noteArrival(now, 480); bank.write(packet, packet.size, now) }
            assertTrue(bank.render(out, now, 3343))
            val consumed = previousDepth + (if (now > 0) 480 else 0) - bank.depthFrames()
            assertTrue("must consume at most 10ms plus a 1ms overlap", consumed in 480..528)
            previousDepth = bank.depthFrames()
        }
        assertTrue(bank.depthFrames() < 23000)
        assertTrue(bank.compressedFrames > 0)
        assertEquals(0L, bank.droppedFrames)
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.concealedFrames)
    }
}
