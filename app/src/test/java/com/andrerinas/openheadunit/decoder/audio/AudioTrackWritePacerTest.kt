package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTrackWritePacerTest {
    @Test fun `full ring retries are bounded by reserve and reset immediately on progress`() {
        val pacer = AudioTrackWritePacer()
        val delays = (1..6).map { pacer.full(4800); pacer.delayMs }
        assertEquals(listOf(1L, 2L, 4L, 4L, 4L, 4L), delays)
        pacer.full(384)
        assertEquals(2L, pacer.delayMs)
        pacer.full(48)
        assertEquals(1L, pacer.delayMs)
        pacer.reset()
        assertEquals(1L, pacer.delayMs)
        pacer.full(4800)
        assertEquals(1L, pacer.delayMs)
    }

    @Test fun `retry loop preserves partial tail and cancellation during backed off wait`() {
        val pcm = shortArrayOf(1, 2, 3, 4, 5, 6)
        val accepted = ArrayList<Short>()
        val pacer = AudioTrackWritePacer()
        var calls = 0
        val sleeps = ArrayList<Long>()
        assertEquals(6, AudioWriteLoop.writeFully(6, { true }, { offset, remaining ->
            calls++
            if (calls in 2..5) { pacer.full(4800); 0 } else {
                pacer.reset()
                val n = minOf(remaining, 2)
                accepted.addAll(pcm.drop(offset).take(n))
                n
            }
        }, {}, { sleeps += pacer.delayMs }))
        assertEquals(pcm.toList(), accepted)
        assertEquals(listOf(1L, 2L, 4L, 4L), sleeps)
        var running = true
        assertEquals(0, AudioWriteLoop.writeFully(6, { running }, { _, _ -> 0 }, {}, { running = false }))
    }
}
