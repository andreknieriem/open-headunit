package com.andrerinas.openheadunit.aap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One service-owned retry timer. Scheduling and cancellation run on the service's main scope. */
internal class AutomaticReconnect(
    private val scope: CoroutineScope,
    private val currentState: () -> Any,
    private val isStopping: () -> Boolean,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun schedule(endedSession: Any, delayMs: Long, reconnect: () -> Unit) {
        // An old teardown must not replace the retry already scheduled for a newer session.
        if (currentState() !== endedSession || isStopping()) return
        cancel()
        job = scope.launch {
            wait(delayMs)
            // A newer attempt may connect and end during this delay. "Not connected" alone
            // would accept it, even if the StateFlow collector skipped the intermediate states.
            if (!isStopping() && currentState() === endedSession) reconnect()
        }
    }
}
