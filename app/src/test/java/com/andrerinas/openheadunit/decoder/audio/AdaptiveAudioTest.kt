package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AdaptiveAudioTest {
    @Test fun `steady 8192 byte wireless packets play for a minute without concealment or trimming`() {
        val buffer = AdaptivePcmBuffer()
        val packet = ShortArray(4096) { 12000 }
        val out = ShortArray(960)
        var packetIndex = 0
        for (now in 0L..60_000L) {
            if (now >= packetIndex * 2048L * 1000L / 48000) {
                buffer.noteArrival(now, 2048)
                buffer.write(packet, packet.size, now)
                packetIndex++
            }
            if (now % 10 == 0L) buffer.render(out, now)
        }
        assertEquals(0L, buffer.rebanks)
        assertEquals(0L, buffer.concealedFrames)
        assertEquals(0L, buffer.droppedFrames)
        assertTrue(buffer.targetFrames() in 2880..3840) // 60-80ms network target
        assertTrue(buffer.depthFrames() < 4800)
    }

    @Test fun `late TCP burst is bounded and recovers instead of retaining permanent delay`() {
        val buffer = AdaptivePcmBuffer()
        val packet = ShortArray(4096) { 12000 }
        val out = ShortArray(960)
        var packetIndex = 0
        for (now in 0L..2500L) {
            while (now >= packetIndex * 2048L * 1000L / 48000) {
                if (now in 400L..699L) break // hold TCP delivery, then release the entire backlog
                buffer.noteArrival(now, 2048)
                buffer.write(packet, packet.size, now)
                packetIndex++
            }
            if (now % 10 == 0L) buffer.render(out, now)
        }
        assertTrue(buffer.concealedFrames > 0)
        assertTrue(buffer.droppedFrames > 0)
        assertTrue(buffer.targetFrames() <= 7200)
        assertTrue(buffer.depthFrames() <= 7200 + 2048)
    }

    @Test fun `PLC ends after thirty milliseconds and does not replay indefinitely`() {
        val buffer = AdaptivePcmBuffer()
        buffer.noteArrival(0, 480)
        buffer.write(ShortArray(5760) { 10000 }, 5760, 0) // six 10ms blocks
        val out = ShortArray(960)
        repeat(6) { buffer.render(out, it * 10L) }
        buffer.render(out, 60)
        assertEquals(7000, out.last().toInt())
        buffer.render(out, 70)
        assertEquals(4000, out.last().toInt())
        buffer.render(out, 80)
        assertEquals(1500, out.last().toInt())
        buffer.render(out, 90)
        assertTrue(out.all { it == 0.toShort() })
        assertEquals(1440L, buffer.concealedFrames)
        assertEquals(1L, buffer.rebanks)
    }

    @Test fun `a stopped short prompt drains its final partial block without PLC`() {
        val buffer = AdaptivePcmBuffer()
        buffer.noteArrival(0, 240)
        buffer.write(ShortArray(480) { 10000 }, 480, 0)
        buffer.finish()
        val out = ShortArray(960)
        assertTrue(buffer.render(out, 1))
        assertEquals(10000, out[478].toInt())
        assertTrue(out.drop(480).all { it == 0.toShort() })
        assertEquals(0L, buffer.rebanks)
        assertEquals(0L, buffer.concealedFrames)
    }

    @Test fun `a prompt without a stop message still escapes preroll`() {
        val buffer = AdaptivePcmBuffer()
        buffer.noteArrival(0, 480)
        buffer.write(ShortArray(960) { 10000 }, 960, 0)
        val out = ShortArray(960)
        assertFalse(buffer.render(out, 99))
        assertTrue(buffer.render(out, 100))
    }

    @Test fun `dropping old PCM crossfades into the newest position with stereo alignment`() {
        val buffer = AdaptivePcmBuffer()
        buffer.noteArrival(0, 480)
        buffer.write(ShortArray(5760) { 10000 }, 5760, 0)
        val out = ShortArray(960)
        buffer.render(out, 0)
        buffer.noteArrival(10, 480)
        buffer.write(ShortArray(30000) { if (it % 2 == 0) -10000 else -5000 }, 30000, 10)
        buffer.render(out, 10)
        assertTrue(buffer.droppedFrames > 0)
        assertTrue(out[0] > 9000) // starts near the previous tail
        assertEquals(-10000, out[478].toInt())
        assertEquals(-5000, out[479].toInt())
        assertTrue(buffer.depthFrames() <= buffer.targetFrames())
    }

    @Test fun `oversized ingress remains bounded and retains newest complete frames`() {
        val buffer = AdaptivePcmBuffer()
        buffer.noteArrival(0, 2048)
        val data = ShortArray(120000) { if (it % 2 == 0) 1234 else -2345 }
        buffer.write(data, data.size, 0)
        assertEquals(48000, buffer.depthFrames())
        val out = ShortArray(960)
        buffer.render(out, 0)
        assertEquals(1234, out[958].toInt())
        assertEquals(-2345, out[959].toInt())
        assertTrue(buffer.droppedFrames >= 12000)
    }

    @Test fun `first underrun increases network target immediately and stable arrivals lower it slowly`() {
        val policy = AdaptiveJitterPolicy(48000)
        policy.onArrival(0, 2048)
        val original = policy.targetFrames
        policy.onUnderrun(1)
        assertEquals(original + 960, policy.targetFrames)
        var now = 43L
        while (now < 10_000) { policy.onArrival(now, 2048); now += 43 }
        assertEquals(original + 960, policy.targetFrames)
        policy.onArrival(now, 2048)
        assertEquals(original + 720, policy.targetFrames)
    }

    @Test fun `idle time and burst delivery do not inflate or instantly shrink the target`() {
        val policy = AdaptiveJitterPolicy(48000)
        policy.onArrival(0, 2048)
        val original = policy.targetFrames
        policy.onArrival(5000, 2048)
        assertEquals(original, policy.targetFrames)
        policy.onArrival(5100, 2048)
        val raised = policy.targetFrames
        assertTrue(raised > original)
        repeat(30) { policy.onArrival(5100, 2048) }
        assertEquals(raised, policy.targetFrames)
    }

    @Test fun `device buffer grows separately and returns to twenty milliseconds after stability`() {
        val policy = OutputBufferPolicy(48000, 192)
        assertEquals(960, policy.update(0, 0))
        assertEquals(1440, policy.update(100, 1))
        assertEquals(1440, policy.update(10099, 1))
        assertEquals(1200, policy.update(10100, 1))
        assertEquals(960, policy.update(20100, 1))
        repeat(20) { policy.update(20200L + it, it + 2) }
        assertEquals(2880, policy.targetFrames)
    }
}
