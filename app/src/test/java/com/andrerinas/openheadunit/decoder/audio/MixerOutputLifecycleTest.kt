package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.decoder.audio.MixerOutputLifecycle.Action.*
import org.junit.Assert.*
import org.junit.Test

class MixerOutputLifecycleTest {
    @Test fun `sink setup alone never starts an output`() {
        val lifecycle = MixerOutputLifecycle(false)
        for (now in 0L..5000L step 10) {
            assertEquals(WAIT, lifecycle.update(now, true, false, false, 80))
        }
    }

    @Test fun `playback request warms before PCM and arrival reuses the running output`() {
        val lifecycle = MixerOutputLifecycle(false)
        assertEquals(START, lifecycle.update(100, true, true, false, 80))
        for (now in 110L..290L step 10) {
            assertEquals(WRITE, lifecycle.update(now, true, true, false, 80))
        }
        assertEquals(WRITE, lifecycle.update(300, false, false, true, 80))
    }

    @Test fun `a request without data drains and parks even in static focus mode`() {
        for (keepActive in listOf(false, true)) {
            val lifecycle = MixerOutputLifecycle(keepActive)
            assertEquals(START, lifecycle.update(0, true, true, false, 80))
            assertEquals(WRITE, lifecycle.update(1000, true, false, false, 80))
            assertEquals(WRITE, lifecycle.update(1079, true, false, false, 80))
            assertEquals(PAUSE, lifecycle.update(1080, true, false, false, 80))
            assertEquals(WAIT, lifecycle.update(1090, true, false, false, 80))
        }
    }

    @Test fun `PCM without an advance request still starts immediately`() {
        val lifecycle = MixerOutputLifecycle(false)
        assertEquals(WAIT, lifecycle.update(0, true, false, false, 80))
        assertEquals(START, lifecycle.update(100, false, false, true, 80))
    }

    @Test fun `new preparation cancels an idle drain and never restarts a live output`() {
        val lifecycle = MixerOutputLifecycle(false)
        assertEquals(START, lifecycle.update(0, false, false, true, 80))
        assertEquals(WRITE, lifecycle.update(100, true, false, true, 80))
        assertEquals(WRITE, lifecycle.update(170, true, true, true, 80))
        assertEquals(WRITE, lifecycle.update(200, true, false, true, 80))
        assertEquals(WRITE, lifecycle.update(279, true, false, true, 80))
        assertEquals(PAUSE, lifecycle.update(280, true, false, true, 80))
        assertEquals(START, lifecycle.update(300, true, true, true, 80))
    }

    @Test fun `static focus retains its output after real playback`() {
        val lifecycle = MixerOutputLifecycle(true)
        assertEquals(START, lifecycle.update(0, false, false, true, 80))
        assertEquals(WRITE, lifecycle.update(100, true, false, true, 80))
        assertEquals(WRITE, lifecycle.update(10000, true, false, true, 80))
    }

    @Test fun `warming on silence preserves the first packet banking and does not train underruns`() {
        val buffer = AdaptivePcmBuffer()
        val out = ShortArray(960)
        for (now in 0L until 1000L step 10) assertFalse(buffer.render(out, now))
        buffer.noteArrival(1000, 2048)
        buffer.write(ShortArray(4096) { 1000 }, 4096, 1000)
        assertFalse(buffer.render(out, 1000))
        assertFalse(buffer.render(out, 1040))
        buffer.noteArrival(1043, 2048)
        buffer.write(ShortArray(4096) { 1000 }, 4096, 1043)
        assertTrue(buffer.render(out, 1050))
        assertEquals(0L, buffer.rebanks)
        assertEquals(0L, buffer.concealedFrames)
        assertTrue(buffer.targetFrames() in 2880..3840)
    }
}
