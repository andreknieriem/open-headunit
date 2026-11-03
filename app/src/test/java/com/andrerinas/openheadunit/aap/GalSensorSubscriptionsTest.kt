package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test

class GalSensorSubscriptionsTest {
    private fun policy() = GalSensorSubscriptions<String>().apply { advertise(setOf(1, 2)) }

    @Test fun `stop invalidates queued events and restart owns new events`() {
        val p = policy()
        assertTrue(p.request(1, 0))
        val old = p.enqueue(1, "old")!!
        assertTrue(p.request(1, -1))
        assertNull(p.enqueue(1, "stopped"))
        assertNull(p.take(old.ticket, 0).value)
        assertTrue(p.request(1, 0))
        val current = p.enqueue(1, "new")!!
        assertNull(p.take(old.ticket, 1).value)
        assertEquals("new", p.take(current.ticket, 1).value)
    }

    @Test fun `five uses five Hz and the throttled final sample is eventually delivered`() {
        val p = policy()
        p.request(1, 5)
        val first = p.enqueue(1, "initial")!!
        assertTrue(first.schedule)
        assertEquals("initial", p.take(first.ticket, 0).value)
        val queued = p.enqueue(1, "older")!!
        assertTrue(queued.schedule)
        assertEquals(195_000_000L, p.take(queued.ticket, 5_000_000).waitNs)
        assertFalse(p.enqueue(1, "latest")!!.schedule)
        assertEquals(1L, p.take(queued.ticket, 199_999_999).waitNs)
        assertEquals("latest", p.take(queued.ticket, 200_000_000).value)
        assertNull(p.take(queued.ticket, 400_000_000).value)
    }

    @Test fun `invalid requests preserve subscriptions and zero is not stop`() {
        val p = policy()
        p.request(1, 0)
        val queued = p.enqueue(1, "value")!!
        assertFalse(p.request(1, -2))
        assertFalse(p.request(3, 0))
        assertEquals("value", p.take(queued.ticket, 0).value)
        val again = p.enqueue(1, "again")!!
        assertEquals("again", p.take(again.ticket, 1).value)
        p.request(2, Long.MAX_VALUE)
        val fast = p.enqueue(2, "fast")!!
        assertEquals("fast", p.take(fast.ticket, 0).value)
        val stale = p.enqueue(1, "stale")!!
        p.stopAll()
        assertNull(p.take(stale.ticket, 2).value)
    }
}
