package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class FinalMessageDeliveryTest {
    @Test fun finalMessageRunsAheadOfPendingTraffic() {
        val queue = ArrayDeque<Runnable>()
        val writes = mutableListOf<String>()
        queue.add(Runnable { writes.add("ACK") })
        val result = FinalMessageDelivery.send(false, {
            queue.addFirst(it)
            queue.removeFirst().run()
            true
        }, { writes.add("ByeBye"); true })
        assertEquals(FinalMessageDelivery.Result.SENT, result)
        assertEquals(listOf("ByeBye"), writes)
        queue.removeFirst().run()
        assertEquals(listOf("ByeBye", "ACK"), writes)
    }

    @Test fun alreadyOnWriterDoesNotQueueAndWaitForItself() {
        assertEquals(FinalMessageDelivery.Result.SENT,
            FinalMessageDelivery.send(true, { fail("self queue"); false }, { true }))
    }

    @Test fun stalledWriterDoesNotHoldShutdownIndefinitely() {
        assertEquals(FinalMessageDelivery.Result.TIMED_OUT,
            FinalMessageDelivery.send(false, { true }, { fail("write not run"); false }, 0))
    }

    @Test fun rejectedQueueAndFailedWriteRemainDistinct() {
        assertEquals(FinalMessageDelivery.Result.REJECTED,
            FinalMessageDelivery.send(false, { false }, { fail(); false }))
        assertEquals(FinalMessageDelivery.Result.FAILED,
            FinalMessageDelivery.send(false, { it.run(); true }, { false }))
    }

    @Test fun transportExceptionCompletesTheWait() {
        assertEquals(FinalMessageDelivery.Result.FAILED,
            FinalMessageDelivery.send(false, { it.run(); true }, { throw java.io.IOException("closed") }))
    }

    @Test fun interruptionPreservesTheCancellationSignal() {
        try {
            Thread.currentThread().interrupt()
            assertEquals(FinalMessageDelivery.Result.INTERRUPTED,
                FinalMessageDelivery.send(false, { true }, { true }))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
