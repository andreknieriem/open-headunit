package com.andrerinas.openheadunit.secondscreen

import com.andrerinas.openheadunit.secondscreen.StreamBacklog.Chunk
import com.andrerinas.openheadunit.secondscreen.StreamBacklog.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBacklogTest {

    private fun key(size: Int = 10) = Chunk(ByteArray(size), keyframe = true)
    private fun delta(size: Int = 10) = Chunk(ByteArray(size), keyframe = false)

    @Test
    fun `a new receiver waits for a keyframe before taking anything`() {
        val backlog = StreamBacklog(maxUnits = 10, maxBytes = 1000)
        assertEquals(Offer.SKIPPED_UNTIL_KEYFRAME, backlog.offer(delta()))
        assertEquals(Offer.QUEUED, backlog.offer(key()))
        assertEquals(Offer.QUEUED, backlog.offer(delta()))
        assertEquals(2, backlog.size)
    }

    @Test
    fun `too many units empties the queue and waits for the next keyframe`() {
        val backlog = StreamBacklog(maxUnits = 2, maxBytes = 1000)
        backlog.offer(key())
        backlog.offer(delta())
        assertEquals(Offer.OVERFLOWED, backlog.offer(delta()))
        assertEquals(0, backlog.size)
        assertTrue(backlog.awaitingKeyframe)
        assertEquals(Offer.SKIPPED_UNTIL_KEYFRAME, backlog.offer(delta()))
    }

    @Test
    fun `a keyframe that overflows the queue starts it again`() {
        val backlog = StreamBacklog(maxUnits = 10, maxBytes = 25)
        backlog.offer(key())
        backlog.offer(delta())
        assertEquals(Offer.OVERFLOWED, backlog.offer(key(size = 20)))
        assertEquals(1, backlog.size)
        assertFalse(backlog.awaitingKeyframe)
    }

    @Test
    fun `parameter sets pass while waiting, and the keyframe after them still ends the wait`() {
        val backlog = StreamBacklog(maxUnits = 10, maxBytes = 1000)
        assertEquals(Offer.QUEUED, backlog.offer(Chunk(ByteArray(8), keyframe = false, config = true)))
        assertTrue(backlog.awaitingKeyframe)
        assertEquals(Offer.SKIPPED_UNTIL_KEYFRAME, backlog.offer(delta()))
        assertEquals(Offer.QUEUED, backlog.offer(key()))
        assertEquals(2, backlog.size)
    }

    @Test
    fun `polling returns units in order and frees their bytes`() {
        val backlog = StreamBacklog(maxUnits = 10, maxBytes = 30)
        val first = key(size = 20)
        backlog.offer(first)
        backlog.offer(delta())
        assertEquals(first, backlog.poll())
        assertEquals(Offer.QUEUED, backlog.offer(delta(size = 20)))
    }
}
