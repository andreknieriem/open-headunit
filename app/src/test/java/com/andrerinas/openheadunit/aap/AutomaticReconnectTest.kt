package com.andrerinas.openheadunit.aap

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticReconnectTest {
    private class Ended(val clean: Boolean = false) {
        override fun equals(other: Any?) = other is Ended && clean == other.clean
        override fun hashCode() = clean.hashCode()
    }

    @Test fun newerEqualDisconnectedStateInvalidatesOlderTimer() = runBlocking {
        var state: Any = Ended()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val retry = AutomaticReconnect(this, { state }, { false }, { release.await() })
        retry.schedule(state, 2000) { calls++ }
        yield()
        state = Ended()
        release.complete(Unit)
        yield()
        assertEquals(0, calls)
    }

    @Test fun latestTimerWinsAndStaleScheduleDoesNotCancelIt() = runBlocking {
        val first = Ended()
        var state: Any = first
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val retry = AutomaticReconnect(this, { state }, { false }, { release.await() })
        retry.schedule(first, 2000) { calls.add("old") }
        yield()
        state = Ended()
        retry.schedule(state, 2000) { calls.add("current") }
        retry.schedule(first, 2000) { calls.add("stale") }
        release.complete(Unit)
        yield()
        assertEquals(listOf("current"), calls)
    }

    @Test fun stoppingAndExplicitCancellationPreventRetry() = runBlocking {
        val state = Ended()
        val release = CompletableDeferred<Unit>()
        var stopping = false
        var calls = 0
        val retry = AutomaticReconnect(this, { state }, { stopping }, { release.await() })
        retry.schedule(state, 2000) { calls++ }
        yield()
        stopping = true
        release.complete(Unit)
        yield()
        assertEquals(0, calls)
        stopping = false
        retry.schedule(state, 2000) { calls++ }
        retry.cancel()
        yield()
        assertEquals(0, calls)
    }
}
