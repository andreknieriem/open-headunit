package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class MusicStartupTest {
    private fun musicBuffer(multiplier: Int = 1) =
        AdaptivePcmBuffer(latencyMultiplier = multiplier, isMediaSink = true)

    @Test fun `cold link jitter does not interrupt the first ten seconds of music`() {
        val buffers = (1..4).map { burst ->
            val buffer = musicBuffer()
            play(buffer, 30_000, burst) { now -> now < 10_000 && now % 400 in 120L..184L }
            buffer
        }
        val details = buffers.mapIndexed { i, buffer ->
            "burst=${i + 1} gaps=${buffer.rebanks} concealed=${buffer.concealedFrames} dropped=${buffer.droppedFrames}"
        }.joinToString("; ")
        assertTrue(details, buffers.all { it.rebanks == 0L && it.concealedFrames == 0L && it.droppedFrames == 0L })
    }

    @Test fun `music waits for a usable opening bank instead of taking the short prompt escape`() {
        val buffer = musicBuffer()
        val packet = ShortArray(4096) { 12000 }
        buffer.noteArrival(0, 2048)
        buffer.write(packet, packet.size, 0)
        assertFalse(buffer.render(ShortArray(960), 100))
        assertFalse(buffer.render(ShortArray(960), 200))
        // An explicit end must still drain a clip shorter than the opening reserve immediately.
        buffer.finish()
        assertTrue(buffer.render(ShortArray(960), 201))
        assertEquals(0L, buffer.rebanks)
    }

    @Test fun `opening reserve is temporary and is repaid without cutting steady music`() {
        for (burst in 1..4) {
            val buffer = musicBuffer()
            val firstRender = play(buffer, 60_000, burst)
            assertTrue("first render $firstRender for burst $burst", firstRender in 80..130)
            assertEquals(0L, buffer.rebanks)
            assertEquals(0L, buffer.concealedFrames)
            assertEquals(0L, buffer.droppedFrames)
            assertTrue("opening latency must be repaid", buffer.compressedFrames > 0)
            assertTrue(buffer.targetFrames() in 2880..3840)
            assertTrue("depth=${buffer.depthFrames()}", buffer.depthFrames() < 4800)
        }
    }

    @Test fun `a short music clip without a stop still has a bounded opening wait`() {
        val buffer = musicBuffer()
        val out = ShortArray(960)
        assertFalse(buffer.render(out, 50_000)) // setup/warmup time must not expire the opening wait
        buffer.noteArrival(60_000, 480)
        buffer.write(ShortArray(960) { 12000 }, 960, 60_000)
        assertFalse(buffer.render(out, 61_499))
        assertTrue(buffer.render(out, 61_500))
        assertEquals(0L, buffer.concealedFrames)
    }

    @Test fun `explicit deep buffering and learned long gaps take precedence over startup reserve`() {
        val packet = ShortArray(4096) { 12000 }
        val out = ShortArray(960)
        val deep = musicBuffer(16)
        repeat(9) { deep.noteArrival(0, 2048); deep.write(packet, packet.size, 0) }
        assertFalse(deep.render(out, 400)) // 384ms cannot satisfy the user's 400ms setting
        deep.noteArrival(401, 2048)
        deep.write(packet, packet.size, 401)
        assertTrue(deep.render(out, 401))
        assertEquals(19200, deep.targetFrames())

        val adaptive = musicBuffer()
        adaptive.noteArrival(0, 2048)
        adaptive.write(packet, packet.size, 0)
        adaptive.noteArrival(400, 2048)
        adaptive.write(packet, packet.size, 400)
        assertTrue(adaptive.targetFrames() > 120 * 48)
        assertFalse(adaptive.render(out, 400))
    }

    @Test fun `pause and reset do not restart the initial music reserve`() {
        val buffer = musicBuffer()
        play(buffer, 60_000, 2)
        buffer.finish()
        val out = ShortArray(960)
        for (now in 60_010L..61_000L step 10) buffer.render(out, now, 960)
        assertTrue(buffer.isIdle())
        val packet = ShortArray(4096) { 12000 }
        for (now in longArrayOf(70_000, 80_000)) {
            if (now == 80_000L) buffer.reset()
            buffer.noteArrival(now, 2048)
            buffer.write(packet, packet.size, now)
            assertFalse(buffer.render(out, now, 960))
            buffer.noteArrival(now + 43, 2048)
            buffer.write(packet, packet.size, now + 43)
            assertTrue("a warm sink needs only its normal bank", buffer.render(out, now + 43, 960))
            assertTrue(buffer.targetFrames() < 3840)
        }
        assertEquals(0L, buffer.rebanks)
    }

    @Test fun `large output bursts preserve a delayed batch within physical capacity`() {
        val buffer = musicBuffer()
        play(buffer, 60_000, 4)
        val packet = ShortArray(4096) { 12000 }
        repeat(12) { buffer.noteArrival(60_010, 2048); buffer.write(packet, packet.size, 60_010) }
        val depth = buffer.depthFrames()
        assertTrue(buffer.render(ShortArray(960), 60_010, 1920))
        assertEquals(0L, buffer.droppedFrames)
        assertTrue(depth - buffer.depthFrames() in 480..528)
    }

    @Test fun `warm music resume respects learned reserve before opening a sparse first batch`() {
        val buffer = musicBuffer(2)
        play(buffer, 12_000, 1)
        val packet = ShortArray(2048) { 12000 }
        val out = ShortArray(960)
        // Learn a real supply gap, then let the old stream finish completely.
        buffer.noteArrival(12_450, 1024)
        buffer.write(packet, packet.size, 12_450)
        buffer.finish()
        for (now in 12_450L..14_000L step 10) buffer.render(out, now)
        assertTrue(buffer.isIdle())
        assertTrue(buffer.targetFrames() >= 300 * 48)
        val before = buffer.rebanks

        var firstRender = -1L
        for (elapsed in 0L..1200L step 10) {
            val now = 15_000L + elapsed
            // A short initial AAC batch, then sustained 400ms batches. The device probe
            // motivates this supply shape; it is not a recording of the phone's packets.
            val packets = when {
                elapsed == 0L -> 6
                elapsed % 400 == 0L -> 19
                else -> 0
            }
            repeat(packets) {
                buffer.noteArrival(now, 1024)
                buffer.write(packet, packet.size, now)
            }
            if (buffer.render(out, now) && firstRender < 0) firstRender = elapsed
        }
        assertTrue("warm resume must bank the learned reserve, first=$firstRender", firstRender >= 350)
        assertEquals("resume must not immediately starve", before, buffer.rebanks)
        assertEquals(0L, buffer.droppedFrames)
    }

    private fun play(buffer: AdaptivePcmBuffer, durationMs: Long, burst: Int,
                     stalled: (Long) -> Boolean = { false }): Long {
        val packet = ShortArray(4096) { 12000 }
        val out = ShortArray(960)
        var packetIndex = 0
        var firstRender = -1L
        for (now in 0L..durationMs) {
            if (!stalled(now)) {
                while (now >= packetIndex * 2048L * 1000 / 48000) {
                    buffer.noteArrival(now, 2048)
                    buffer.write(packet, packet.size, now)
                    packetIndex++
                }
            }
            if (now % (burst * 10) == 0L) repeat(burst) {
                if (buffer.render(out, now, burst * 480) && firstRender < 0) firstRender = now
            }
        }
        return firstRender
    }
}
