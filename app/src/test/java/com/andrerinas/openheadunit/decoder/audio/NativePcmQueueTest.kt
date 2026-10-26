package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class NativePcmQueueTest {
    @Test fun `queue owns PCM and preserves stop markers through wrap and backpressure`() {
        val queue = NativePcmQueue(6)
        val pcm = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertTrue(queue.offer(pcm, 8, 10))
        pcm.fill(99)
        queue.stop()
        assertFalse(queue.offer(pcm, 8, 20))
        assertEquals(4L, queue.rejectedFrames)
        val out = ShortArray(4)
        val read = NativePcmQueue.Read()
        queue.poll(out, read)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), out)
        assertEquals(4, read.packetFrames)
        assertEquals(10L, read.arrivalMs)
        assertTrue(queue.offer(shortArrayOf(9, 10, 11, 12, 13, 14), 6, 30))
        queue.stop()
        queue.poll(out, read)
        assertEquals(0, read.packetFrames)
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), out)
        queue.poll(out, read); assertEquals(-1, read.frames)
        queue.poll(out, read); assertEquals(3, read.packetFrames)
        assertArrayEquals(shortArrayOf(9, 10, 11, 12), out)
        queue.poll(out, read); assertEquals(1, read.frames)
        assertEquals(13.toShort(), out[0]); assertEquals(14.toShort(), out[1])
        queue.poll(out, read); assertEquals(-1, read.frames)
        assertTrue(queue.isEmpty())
    }
    @Test fun `repeated stop coalesces and the last descriptor remains reserved for stop`() {
        val queue = NativePcmQueue()
        repeat(255) { assertTrue(queue.offer(shortArrayOf(1, 2), 2, 0)) }
        assertFalse(queue.offer(shortArrayOf(1, 2), 2, 0))
        repeat(1000) { queue.stop() }
        val out = ShortArray(2)
        val read = NativePcmQueue.Read()
        repeat(255) { queue.poll(out, read); assertEquals(1, read.frames) }
        queue.poll(out, read); assertEquals(-1, read.frames)
        assertTrue(queue.isEmpty())
    }
    @Test fun `subframe polls retain packet metadata PCM and ordered stop`() {
        for (capacity in 0..1) {
            val queue = NativePcmQueue(2)
            val pcm = shortArrayOf(1, -2, 3, -4)
            assertTrue(queue.offer(pcm, pcm.size, 123))
            queue.stop()
            val read = NativePcmQueue.Read()
            repeat(2) {
                queue.poll(ShortArray(capacity), read)
                assertEquals(0, read.frames)
                assertEquals(0, read.packetFrames)
                assertEquals(2, queue.depthFrames())
                assertFalse(queue.isEmpty())
            }
            val out = ShortArray(2)
            queue.poll(out, read)
            assertEquals(1, read.frames)
            assertEquals(2, read.packetFrames)
            assertEquals(123L, read.arrivalMs)
            assertArrayEquals(shortArrayOf(1, -2), out)
            queue.poll(out, read)
            assertEquals(1, read.frames)
            assertEquals(0, read.packetFrames)
            assertArrayEquals(shortArrayOf(3, -4), out)
            queue.poll(ShortArray(capacity), read)
            assertEquals(-1, read.frames)
            assertTrue(queue.isEmpty())
        }
    }
}
