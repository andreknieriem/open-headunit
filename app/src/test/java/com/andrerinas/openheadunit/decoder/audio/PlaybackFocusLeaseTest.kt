package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PlaybackFocusLeaseTest {
    private val owner = Any()
    private fun lease() = PlaybackFocusLease().apply { register(4, owner); register(5, owner) }
    private fun PlaybackFocusLease.activity(channel: Int, nowMs: Long) = activity(channel, owner, nowMs)

    @Test fun `replacement retires old demand and rejects delayed old activity`() {
        val lease = lease()
        lease.complete(lease.activity(5, 0)!!)
        val old = lease.snapshot().getValue(5)
        val nextOwner = Any()
        val release = lease.register(5, nextOwner)!!
        assertTrue(lease.acceptsRelease(release))
        assertNull(lease.activity(5, owner, 1))
        assertNull(lease.retire(5, owner))
        val next = lease.activity(5, nextOwner, 2)!!
        assertFalse(lease.acceptsRelease(release))
        assertFalse(lease.release(5, old))
        assertTrue(lease.complete(next))
        assertSame(nextOwner, lease.snapshot().getValue(5).owner)
    }

    @Test fun `retirement cannot remove another channel or a replacement's demand`() {
        val lease = lease()
        lease.complete(lease.activity(4, 0)!!)
        lease.activity(5, 1)
        assertNull(lease.retire(4, owner))
        assertTrue(lease.canRender(1))
        assertEquals(setOf(5), lease.snapshot().keys)
        val nextOwner = Any()
        lease.register(4, nextOwner)
        lease.activity(4, nextOwner, 2)
        assertNull(lease.retire(4, owner))
        assertEquals(setOf(4, 5), lease.snapshot().keys)
        assertTrue(lease.canRender(2))
    }
    @Test fun `sleep release cannot cancel a newer successfully acquired lease`() {
        val lease = lease()
        lease.complete(lease.activity(4, 0)!!)
        val release = lease.clear()
        assertTrue(lease.acceptsRelease(release))
        val next = lease.activity(4, 10)!!
        lease.complete(next)
        assertFalse(lease.acceptsRelease(release))
        assertTrue(lease.canRender(10))
    }
    @Test fun `late PCM invalidates a queued release and waits for focus after a quiet park`() {
        val lease = lease()
        val first = lease.activity(5, 0)!!
        assertFalse(lease.canRender(0))
        assertTrue(lease.complete(first))
        val old = lease.snapshot().getValue(5)
        lease.activity(5, 10)
        assertFalse(lease.release(5, old))
        assertTrue(lease.canRender(10))
        assertTrue(lease.release(5, lease.snapshot().getValue(5)))
        val late = lease.activity(5, 2000)!!
        assertFalse(lease.canRender(2000))
        assertTrue(lease.complete(late))
        assertTrue(lease.canRender(2001))
    }

    @Test fun `connection teardown invalidates both queued and in flight focus acquisitions`() {
        val lease = lease()
        val request = lease.activity(4, 0)!!
        lease.close()
        assertFalse(lease.accepts(request))
        assertFalse(lease.complete(request))
        assertNull(lease.activity(4, 100))
        assertFalse(lease.canRender(1000))
    }

    @Test fun `stalled main thread permits audio after a bounded wait and invalidates late acquire`() {
        val lease = lease()
        val request = lease.activity(5, 0)!!
        assertFalse(lease.canRender(499))
        assertTrue(lease.canRender(500))
        assertFalse(lease.accepts(request))
        assertFalse(lease.complete(request))
    }

    @Test fun `one channel cannot abandon focus held for another`() {
        val lease = lease()
        lease.complete(lease.activity(4, 0)!!)
        lease.activity(5, 1)
        assertFalse(lease.release(5, lease.snapshot().getValue(5)))
        assertTrue(lease.canRender(1))
        assertTrue(lease.release(4, lease.snapshot().getValue(4)))
    }
}
