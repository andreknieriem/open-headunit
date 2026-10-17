package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class PresentationScheduler {
    data class Task(val runnable: Runnable, val delay: Long)
    val tasks = mutableListOf<Task>()
    val errors = mutableListOf<Exception>()
    fun queue() = PresentationQueue(
        post = { r, delay -> tasks.add(Task(r, delay)); true },
        cancel = { r -> tasks.removeAll { it.runnable === r } },
        onError = { errors.add(it) },
    )
    fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).runnable.run() }
}

class PresentationQueueTest {
    @Test fun burstKeepsOneLatestTaskPerSlotAndOriginalDeadline() {
        val scheduler = PresentationScheduler()
        val queue = scheduler.queue()
        val received = mutableListOf<Int>()
        repeat(10000) { n ->
            queue.submit(PresentationQueue.Slot.NAV_BROADCAST, 1000L + n) { received.add(n) }
        }
        assertEquals(1, scheduler.tasks.size)
        assertEquals(1000L, scheduler.tasks.single().delay)
        scheduler.drain()
        assertEquals(listOf(9999), received)
    }

    @Test fun retireCancelsEvenAlreadyDequeuedWorkAndIsIdempotent() {
        val scheduler = PresentationScheduler()
        val queue = scheduler.queue()
        val received = mutableListOf<String>()
        queue.submit(PresentationQueue.Slot.METADATA) { received.add("old") }
        val dequeued = scheduler.tasks.removeAt(0).runnable
        queue.close { received.add("cleanup") }
        queue.close { received.add("duplicate") }
        queue.submit(PresentationQueue.Slot.PLAYBACK) { received.add("late") }
        dequeued.run()
        scheduler.drain()
        assertEquals(listOf("cleanup"), received)
    }

    @Test fun cleanupPrecedesReplacementSessionIncludingDelayedNavigation() {
        val scheduler = PresentationScheduler()
        val old = scheduler.queue()
        val calls = mutableListOf<String>()
        old.submit(PresentationQueue.Slot.NAV_BROADCAST, 1000) { calls.add("stale") }
        old.close { calls.add("cancel old notification") }
        val replacement = scheduler.queue()
        replacement.submit(PresentationQueue.Slot.NAV_NOTIFICATION) { calls.add("new notification") }
        scheduler.drain()
        assertEquals(listOf("cancel old notification", "new notification"), calls)
    }

    @Test fun exceptionDoesNotPoisonOtherSlotsOrCleanup() {
        val scheduler = PresentationScheduler()
        val queue = scheduler.queue()
        var updated = false
        queue.submit(PresentationQueue.Slot.METADATA) { error("Binder failure") }
        queue.submit(PresentationQueue.Slot.PLAYBACK) { updated = true }
        scheduler.drain()
        assertTrue(updated)
        assertEquals(1, scheduler.errors.size)
        queue.close { error("cleanup failure") }
        scheduler.drain()
        assertEquals(2, scheduler.errors.size)
    }

    @Test fun rejectedPostDoesNotLeaveSlotLatched() {
        var accept = false
        val tasks = mutableListOf<Runnable>()
        var ran = false
        val queue = PresentationQueue({ r, _ -> if (accept) tasks.add(r) else false }, {}, { throw it })
        queue.submit(PresentationQueue.Slot.PLAYBACK) { fail("Rejected work ran") }
        accept = true
        queue.submit(PresentationQueue.Slot.PLAYBACK) { ran = true }
        tasks.single().run()
        assertTrue(ran)
    }

    @Test fun stalledDisplayDoesNotBlockProducerOrRetirement() {
        val ui = Executors.newSingleThreadExecutor()
        val reader = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val queue = PresentationQueue({ r, _ -> ui.execute(r); true }, {}, { throw it })
        try {
            queue.submit(PresentationQueue.Slot.METADATA) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            reader.submit {
                repeat(10000) { queue.submit(PresentationQueue.Slot.PLAYBACK) { fail("Retired update ran") } }
                queue.close { cleaned.countDown() }
            }.get(2, TimeUnit.SECONDS)
            assertEquals(1L, cleaned.count)
            release.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            reader.shutdownNow()
            ui.shutdownNow()
        }
    }
}
