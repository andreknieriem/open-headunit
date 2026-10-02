package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class GuidanceBurstCapacityTest {
    @Test fun `guidance retains a catchup burst while consuming a learned reserve`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        // A supply outage teaches the existing 400ms target, independently of capacity.
        bank.noteArrival(0, 3072)
        bank.noteArrival(650, 3072)
        assertEquals(19200, bank.targetFrames())
        bank.write(ShortArray(19200 * 2) { 12000 }, 19200 * 2, 650)
        val out = ShortArray(960)
        assertTrue(bank.render(out, 650))
        // 16kHz mono's 1024-frame chunks convert to 3072 output frames. A one-second
        // catch-up can overlap the reserve despite the consumer having run just 6ms ago.
        // This is a controlled burst envelope, not the device's exact packet trace.
        repeat(16) {
            bank.noteArrival(656, 3072)
            bank.write(ShortArray(6144) { 12000 }, 6144, 656)
        }
        assertEquals(0L, bank.droppedFrames)
        assertEquals(19200, bank.targetFrames())
        assertEquals(19200 - 480 + 16 * 3072, bank.depthFrames())
        bank.finish()
        var cycles = 0
        while (!bank.isIdle() && cycles < 200) {
            bank.render(out, 660 + cycles * 10L)
            cycles++
        }
        assertTrue(bank.isIdle())
        assertEquals(142, cycles)
        assertEquals(0L, bank.compressedFrames)
        assertEquals(0L, bank.concealedFrames)
    }
}
