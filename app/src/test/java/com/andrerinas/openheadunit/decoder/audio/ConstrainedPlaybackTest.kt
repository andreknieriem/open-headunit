package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class ConstrainedPlaybackTest {
    @Test fun `decoded PCM delay raises the reserve even when encoded packets arrive on time`() {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        val packet = ShortArray(2048) { 12000 }
        buffer.noteArrival(0, 1024)
        buffer.write(packet, packet.size, 0)
        for (time in 21L..315L step 21) buffer.noteArrival(time, 1024)
        buffer.write(packet, packet.size, 320)
        assertTrue("Decoder supply delay was invisible: target=" + buffer.targetFrames(),
            buffer.targetFrames() >= 320 * 48)
    }

    @Test fun `an active one second outage teaches the bounded reserve instead of being mistaken for pause`() {
        val policy = AdaptiveJitterPolicy(48000, 2)
        policy.onArrival(0, 2048)
        policy.onArrival(1014, 2048)
        assertEquals(400 * 48, policy.targetFrames)
        val resumed = AdaptiveJitterPolicy(48000, 2)
        resumed.onArrival(0, 2048)
        resumed.resetArrival()
        resumed.onArrival(10_000, 2048)
        assertTrue(resumed.targetFrames < 100 * 48)
    }

    @Test fun `media preserves a one second catchup burst on top of the configured reserve`() {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = 16, isMediaSink = true)
        val reserve = ShortArray(400 * 48 * 2) { 1000 }
        val backlog = ShortArray(1000 * 48 * 2) { 2000 }
        buffer.noteArrival(0, 2048)
        buffer.write(reserve, reserve.size, 0)
        buffer.write(backlog, backlog.size, 1014)
        assertEquals(0L, buffer.droppedFrames)
        assertEquals(1400 * 48, buffer.depthFrames())
    }

    @Test fun `callback queue covers one mixer scheduling interval plus the next callback`() {
        for (burst in intArrayOf(96, 192, 240, 480, 960)) {
            for (callback in intArrayOf(burst, burst * 2)) {
                val minimum = CallbackBufferSizing.minimumFrames(burst, callback)
                val device = CallbackBufferSizing.deviceFrames(minimum, burst, callback)
                val queue = CallbackBufferSizing.queueFrames(minimum, device, burst, callback)
                assertTrue("burst=$burst callback=$callback queue=$queue", queue >= 480 + callback)
                assertTrue(queue >= callback * 2)
            }
        }
    }

    @Test fun `network and codec batching both settle without recurrent starvation`() {
        for (codecDelay in listOf(false, true)) for (batchMs in listOf(120, 240, 320)) {
            val buffer = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
            val packet = ShortArray(2048) { 12000 }
            val out = ShortArray(960)
            var arrived = 0
            var delivered = 0
            var warmRebanks = 0L
            for (now in 0L..60_000L) {
                if (codecDelay || now % batchMs == 0L) {
                    while (now >= arrived * 1024L * 1000 / 48000) {
                        buffer.noteArrival(now, 1024)
                        arrived++
                    }
                }
                if (now % batchMs == 0L) {
                    while (delivered < arrived) {
                        buffer.write(packet, packet.size, now)
                        delivered++
                    }
                }
                if (now % 10 == 0L) buffer.render(out, now)
                if (now == 10_000L) warmRebanks = buffer.rebanks
            }
            assertEquals("codec=$codecDelay batch=$batchMs", warmRebanks, buffer.rebanks)
            assertEquals(0L, buffer.droppedFrames)
            assertTrue(buffer.targetFrames() <= 400 * 48)
        }
    }

    @Test fun `over capacity input stays bounded and resumes with stereo aligned PCM`() {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = 16, isMediaSink = true)
        val twoSeconds = ShortArray(2000 * 48 * 2) { if (it % 2 == 0) 12000 else -12000 }
        buffer.noteArrival(0, 2048)
        buffer.write(twoSeconds, twoSeconds.size, 0)
        assertEquals(1500 * 48, buffer.depthFrames())
        assertEquals(500L * 48, buffer.droppedFrames)
        val out = ShortArray(960)
        assertTrue(buffer.render(out, 0))
        assertEquals(12000, out[958].toInt())
        assertEquals(-12000, out[959].toInt())
    }

    @Test fun `one second wireless outage recovers without another PCM discard`() {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        val packet = ShortArray(4096) { 12000 }
        val out = ShortArray(960)
        var packetIndex = 0
        var recoveredRebanks = 0L
        for (now in 0L..60_000L) {
            if (now !in 20_000L..21_013L) {
                while (now >= packetIndex * 2048L * 1000 / 48000) {
                    buffer.noteArrival(now, 2048)
                    buffer.write(packet, packet.size, now)
                    packetIndex++
                }
            }
            if (now % 10 == 0L) buffer.render(out, now)
            assertTrue(buffer.depthFrames() <= 1500 * 48)
            if (now == 25_000L) recoveredRebanks = buffer.rebanks
        }
        assertTrue("An outage longer than the reserve cannot be hidden", buffer.concealedFrames > 0)
        assertEquals(0L, buffer.droppedFrames)
        assertEquals(recoveredRebanks, buffer.rebanks)
        assertTrue(buffer.depthFrames() > 0)
    }
}
