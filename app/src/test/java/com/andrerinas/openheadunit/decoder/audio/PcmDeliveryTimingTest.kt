package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PcmDeliveryTimingTest {
    @Test fun `variable delivery and twenty millisecond output bursts settle after warmup`() {
        for (renderBurst in intArrayOf(1, 2, 4)) {
            val buffer = AdaptivePcmBuffer()
            val packet = ShortArray(4096) { 12000 }
            val out = ShortArray(960)
            var packetIndex = 0
            var rebanks = 0L
            var dropped = 0L
            for (now in 0L..60_000L) {
                // Delivery alternates between closely spaced packets and 120ms stalls.
                val stalled = now % 1000 in 400L..519L
                if (!stalled) {
                    while (now >= packetIndex * 2048L * 1000 / 48000) {
                        buffer.noteArrival(now, 2048)
                        buffer.write(packet, packet.size, now)
                        packetIndex++
                    }
                }
                if (now % (renderBurst * 10) == 0L) repeat(renderBurst) { buffer.render(out, now) }
                if (now == 10_000L) { rebanks = buffer.rebanks; dropped = buffer.droppedFrames }
            }
            assertEquals("burst=$renderBurst target=${buffer.targetFrames()}", rebanks, buffer.rebanks)
            assertEquals("burst=$renderBurst must not discard a changing delivery peak", dropped, buffer.droppedFrames)
        }
    }
}
