package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HctProjectionStageTest {
    private class UiQueue : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    @Test fun `new lease publication before old cleanup preserves the new stage`() = runBlocking {
        val ui = UiQueue()
        val events = mutableListOf<String>()
        val stage = HctProjectionStage({ events.add("start") }, { events.add("end") }, ui)
        val oldEnd = CompletableDeferred<Unit>()
        val nextEnd = CompletableDeferred<Unit>()
        val old = launch(Dispatchers.Unconfined) { stage.serve { oldEnd.await() } }
        ui.drain()
        val next = launch(Dispatchers.Unconfined) { stage.serve { nextEnd.await() } }
        oldEnd.complete(Unit) // Queue the old finally behind the new publication.
        ui.drain()
        assertEquals(listOf("start", "start"), events)
        old.join()
        nextEnd.complete(Unit); ui.drain(); next.join()
        assertEquals(listOf("start", "start", "end"), events)
    }

    @Test fun `old cleanup before new publication cannot clear the later stage`() = runBlocking {
        val ui = UiQueue()
        val events = mutableListOf<String>()
        val stage = HctProjectionStage({ events.add("start") }, { events.add("end") }, ui)
        val oldEnd = CompletableDeferred<Unit>()
        val nextEnd = CompletableDeferred<Unit>()
        val old = launch(Dispatchers.Unconfined) { stage.serve { oldEnd.await() } }
        ui.drain(); oldEnd.complete(Unit)
        val next = launch(Dispatchers.Unconfined) { stage.serve { nextEnd.await() } }
        ui.drain()
        assertEquals(listOf("start", "end", "start"), events)
        old.join(); nextEnd.complete(Unit); ui.drain(); next.join()
        assertEquals(listOf("start", "end", "start", "end"), events)
    }

    @Test fun `cancellation before publication leaves the stage untouched`() = runBlocking {
        val ui = UiQueue()
        var updates = 0
        val stage = HctProjectionStage({ updates++ }, { updates++ }, ui)
        val job = launch(Dispatchers.Unconfined) { stage.serve { fail("cancelled") } }
        job.cancel(); ui.drain(); job.join()
        assertEquals(0, updates)
    }

    @Test fun `cancellation after publication cleans up even when IO never resumes`() = runBlocking {
        val ui = UiQueue()
        lateinit var job: Job
        var ended = 0
        val stage = HctProjectionStage({ job.cancel() }, { ended++ }, ui)
        job = launch(Dispatchers.Unconfined) { stage.serve { fail("cancelled before IO resumes") } }
        ui.drain(); job.join()
        assertEquals(1, ended)
    }
}
