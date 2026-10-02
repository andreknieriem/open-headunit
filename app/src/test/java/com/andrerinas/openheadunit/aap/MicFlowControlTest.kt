package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test

class MicFlowControlTest {
    @Test fun `response precedes data and acks release only the matching session window`() {
        val sent = mutableListOf<Int>()
        lateinit var flow: MicFlowControl<Int>
        flow = MicFlowControl { token, frame -> if (flow.claim(token, frame)) sent.add(frame) }
        val token = flow.begin(7, 2)
        repeat(4) { flow.offer(it) }
        assertTrue(sent.isEmpty())
        flow.activate(token)
        assertEquals(listOf(0, 1), sent)
        assertFalse(flow.acknowledge(6, 1))
        assertFalse(flow.acknowledge(7, 3))
        assertFalse(flow.acknowledge(7, 0))
        assertTrue(flow.acknowledge(7, 1))
        assertEquals(listOf(0, 1, 2), sent)
        assertTrue(flow.acknowledge(7, 2))
        assertEquals(listOf(0, 1, 2, 3), sent)
    }

    @Test fun `bounded pending queue never displaces or reorders speech`() {
        val sent = mutableListOf<Int>()
        lateinit var flow: MicFlowControl<Int>
        flow = MicFlowControl { token, frame -> if (flow.claim(token, frame)) sent.add(frame) }
        flow.activate(flow.begin(1, 1))
        flow.offer(0)
        repeat(MicFlowControl.MAX_PENDING_FRAMES) { assertEquals(MicFlowControl.Offer.ACCEPTED, flow.offer(it + 1)) }
        assertEquals(MicFlowControl.Offer.OVERFLOW, flow.offer(99))
        assertEquals(listOf(1, 2, 3, 4), flow.close())
        assertFalse(flow.acknowledge(1, 1))
        assertEquals(MicFlowControl.Offer.CLOSED, flow.offer(6))
    }

    @Test fun `reopening invalidates old send tasks and stale acks`() {
        val flow = MicFlowControl<Int> { _, _ -> }
        val old = flow.begin(1, 2)
        flow.activate(old)
        flow.offer(1)
        flow.close()
        val fresh = flow.begin(2, 2)
        assertFalse(flow.isCurrent(old))
        assertTrue(flow.isCurrent(fresh))
        assertFalse(flow.acknowledge(1, 1))
    }
}
