package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ConflatedReconnectTest {
    private class QueuedMain : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private fun checkSchedule(observeIntermediate: Boolean, attempts: Int, finishLive: Boolean = false) {
        val main = QueuedMain()
        val scope = CoroutineScope(SupervisorJob() + main)
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        val deadline = CompletableDeferred<Unit>()
        var callbacks = 0
        var retries = 0
        val retry = AutomaticReconnect(scope, { states.value }, { false }, { deadline.await() })
        try {
            scope.launch {
                states.collect { state ->
                    retry.cancel()
                    if (state is ConnectionState.Disconnected) {
                        callbacks++
                        retry.schedule(state, 2000) { retries++ }
                    }
                }
            }
            main.drain() // Previous disconnect arms its timer.
            repeat(attempts) {
                states.value = ConnectionState.Connecting
                if (observeIntermediate) main.drain()
                states.value = ConnectionState.Connected
                states.value = ConnectionState.StartingTransport
                states.value = ConnectionState.Error("Handshake failed")
                states.value = ConnectionState.Disconnected()
                if (observeIntermediate) main.drain()
            }
            if (finishLive) states.value = ConnectionState.TransportStarted
            main.drain()
            assertEquals(if (finishLive) 1 else if (observeIntermediate) attempts + 1 else 2, callbacks)
            // Publishing the same terminal instance must not arm another timer.
            states.value = states.value
            main.drain()
            deadline.complete(Unit)
            main.drain()
            assertEquals(if (finishLive) 0 else 1, retries)
        } finally {
            scope.cancel()
            main.drain()
        }
    }

    @Test fun fastFailureStillSchedulesItsOwnRetry() = checkSchedule(false, 1)
    @Test fun observedFailureReplacesPreviousTimer() = checkSchedule(true, 1)
    @Test fun manyConflatedFailuresScheduleOnlyLatestRetry() = checkSchedule(false, 20)
    @Test fun manyObservedFailuresScheduleOnlyLatestRetry() = checkSchedule(true, 20)
    @Test fun successfulConnectionSuppressesAllOldRetries() = checkSchedule(false, 20, finishLive = true)
}
