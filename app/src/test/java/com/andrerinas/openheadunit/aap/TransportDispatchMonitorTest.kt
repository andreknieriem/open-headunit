package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportDispatchMonitorTest {

    private val window = InboundRateMonitor.WINDOW_MS

    @Test
    fun `nothing is reported before the window closes`() {
        val m = TransportDispatchMonitor()
        assertNull(m.onDispatch(Channel.ID_VID, 5, 0L))
        assertNull(m.onDispatch(Channel.ID_AUD, 1, window - 1))
    }

    @Test
    fun `dispatch cost is split by channel class`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0L)
        m.onDispatch(Channel.ID_VID, 100, 10L)
        m.onDispatch(Channel.ID_AUD, 4, 20L)
        m.onDispatch(Channel.ID_CTR, 7, 30L)
        val r = m.onDispatch(Channel.ID_AU1, 3, window)
        assertNotNull(r)
        assertEquals(100L, r!!.videoMs)
        assertEquals(7L, r.audioMs)
        assertEquals(7L, r.otherMs)
    }

    @Test
    fun `auxiliary video dispatch time is billed to other`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID2, 0, 0L)
        val r = m.onDispatch(Channel.ID_VID2, 40, window)
        assertNotNull(r)
        assertEquals(0L, r!!.videoMs)
        assertEquals(40L, r.otherMs)
    }

    @Test
    fun `the video park reads as a block on VIDEO`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0L)
        m.onDispatch(Channel.ID_VID, 1000, 1000L) // a full WAIT_BUDGET_MS park
        val r = m.onDispatch(Channel.ID_AUD, 1, window)
        assertEquals(1000L, r!!.worstMs)
        assertEquals(Channel.ID_VID, r.worstChannel)
        assertEquals(1, r.blocks)
        assertFalse(r.healthy)
        assertTrue(r.toString().contains("longest=1000ms on VIDEO"))
    }

    @Test
    fun `a session that never parks reads healthy`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0L)
        repeat(50) { m.onDispatch(Channel.ID_VID, 1, it.toLong()) }
        val r = m.onDispatch(Channel.ID_AUD, 1, window)
        assertTrue(r!!.healthy)
        assertEquals(0, r.blocks)
        assertEquals(0, r.deadPercent)
    }

    @Test
    fun `the unread share is the whole of what the thread spent`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0L)
        m.onDispatch(Channel.ID_VID, window / 4, 100L)
        m.onDispatch(Channel.ID_AUD, window / 4, 200L)
        val r = m.onDispatch(Channel.ID_CTR, 0, window)
        assertEquals(50, r!!.deadPercent)
    }

    @Test
    fun `a negative duration cannot run the totals backwards`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0L)
        m.onDispatch(Channel.ID_VID, -5, 10L)
        val r = m.onDispatch(Channel.ID_VID, 0, window)
        assertEquals(0L, r!!.videoMs)
    }

    @Test
    fun `windows do not carry cost forward`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0L)
        m.onDispatch(Channel.ID_VID, 900, 10L)
        m.onDispatch(Channel.ID_VID, 0, window)
        val second = m.onDispatch(Channel.ID_VID, 2, window * 2)
        assertEquals(2L, second!!.videoMs)
        assertEquals(0, second.blocks)
    }

    @Test
    fun `reset forgets the session`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 900, 0L)
        m.reset()
        assertNull(m.onDispatch(Channel.ID_VID, 1, window * 5))
    }
}

/** Where the video park moved to once video got its own thread. */
class TransportDispatchMonitorBacklogTest {

    private val window = InboundRateMonitor.WINDOW_MS

    @Test
    fun `the deepest backlog over the window is what gets reported`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0, 1)
        m.onDispatch(Channel.ID_VID, 0, window / 2, 17)
        val r = m.onDispatch(Channel.ID_VID, 0, window, 3)
        assertNotNull(r)
        assertEquals(17, r!!.maxVideoQueue)
        assertTrue(r.toString().contains("videoQueue=17"))
    }

    @Test
    fun `a backlog does not carry into the next window`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0, 12)
        m.onDispatch(Channel.ID_VID, 0, window, 12)
        val second = m.onDispatch(Channel.ID_VID, 0, window * 2, 2)
        assertEquals(2, second!!.maxVideoQueue)
    }

    @Test
    fun `a window with no blocks and a backlog is still the healthy shape`() {
        // The demux does not remove the park, it moves it. A deep queue beside blocks=0 is the
        // result it is meant to produce, not a fault.
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0, 0)
        val r = m.onDispatch(Channel.ID_VID, 1, window, 20)
        assertTrue(r!!.healthy)
        assertEquals(20, r.maxVideoQueue)
    }

    @Test
    fun `shed is the count for this window, not the session`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0, 1, 0)
        val first = m.onDispatch(Channel.ID_VID, 0, window, 1, 4)
        assertEquals(4L, first!!.videoShed)
        assertTrue(first.toString().contains("videoShed=4"))

        val second = m.onDispatch(Channel.ID_VID, 0, window * 2, 1, 7)
        assertEquals(3L, second!!.videoShed)
    }

    @Test
    fun `a shed window is never healthy, however free the read thread was`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0, 1, 0)
        val r = m.onDispatch(Channel.ID_VID, 0, window, 1, 1)
        assertEquals(0, r!!.blocks)
        assertTrue(!r.healthy)
    }

    @Test
    fun `a restarted transport cannot report a negative shed`() {
        val m = TransportDispatchMonitor()
        m.onDispatch(Channel.ID_VID, 0, 0, 1, 9)
        val r = m.onDispatch(Channel.ID_VID, 0, window, 1, 0)
        assertEquals(0L, r!!.videoShed)
    }
}
