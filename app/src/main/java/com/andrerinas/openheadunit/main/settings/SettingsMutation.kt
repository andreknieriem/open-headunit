package com.andrerinas.openheadunit.main.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Main-confined, per-view ownership of settings writes. Preparation must not change settings. */
internal class SettingsMutation(private val scope: CoroutineScope) {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun <T> start(
        prepare: suspend () -> T,
        apply: (T) -> Unit,
        onFailure: (Exception) -> Unit,
    ): Job {
        cancel()
        val owner = generation
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val prepared = prepare()
                // Ownership, actual preference/mirror commits, and completion effects share this
                // non-suspending Main turn. A cancelled blocking read can only return unused data.
                if (isActive && generation == owner) apply(prepared)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isActive && generation == owner) onFailure(error)
            }
        }
        job = next
        next.start()
        return next
    }
}
