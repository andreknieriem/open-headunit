package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCycleLeverTest {

    @Test
    fun `the first claim wins and the second is refused`() {
        val lever = FocusCycleLever()
        assertTrue(lever.tryClaim())
        assertFalse(lever.tryClaim())
        assertTrue(lever.isHeld)
    }

    @Test
    fun `releasing lets the next cycle through`() {
        val lever = FocusCycleLever()
        assertTrue(lever.tryClaim())
        lever.release()
        assertFalse(lever.isHeld)
        assertTrue(lever.tryClaim())
    }

    @Test
    fun `releasing twice is safe`() {
        // A cycle can be completed early by a settle path and then again by its own delayed regain.
        val lever = FocusCycleLever()
        lever.tryClaim()
        lever.release()
        lever.release()
        assertTrue(lever.tryClaim())
    }

    @Test
    fun `releasing what was never claimed does not open a second cycle`() {
        val lever = FocusCycleLever()
        lever.release()
        assertTrue(lever.tryClaim())
        assertFalse(lever.tryClaim())
    }

    @Test
    fun `only one of many concurrent claims succeeds`() {
        // The two callers live on different handlers - the transport's send thread and the
        // projection activity's watchdog - so this is a real race, not a theoretical one.
        val lever = FocusCycleLever()
        val winners = java.util.concurrent.atomic.AtomicInteger(0)
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                if (lever.tryClaim()) winners.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        org.junit.Assert.assertEquals(1, winners.get())
    }
}
