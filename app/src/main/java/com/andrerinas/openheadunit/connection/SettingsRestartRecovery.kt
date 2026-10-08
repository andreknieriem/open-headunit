package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Save owns one route retry; the ordinary connection rules resume if it never starts. */
internal object SettingsRestartRecovery {
    // Legacy Self Mode can take 15–20 seconds to dial port 5288. Its 30-second allowance
    // also bounds Nearby's peer preference and Save's fallback timer. Native releases its
    // wake hold earlier, after the old transport retires, so the first retry need not wait.
    const val WINDOW_MS = 30_000L

    suspend fun run(
        remainingMs: Long,
        isCurrent: () -> Boolean,
        retry: suspend () -> Unit,
        resumeAutomatic: () -> Unit,
        route: String = "unspecified",
        awaitRetryCompletion: suspend () -> Unit = {},
        isRetryInFlight: () -> Boolean = { false },
    ) = coroutineScope {
        // Arm before retry: USB may return without a device, or the arbiter may refuse a dial.
        // The Save owner may include its own failed USB opens; unrelated terminals cannot
        // inherit permission merely because they have the same reason or last route.
        val fallback = launch {
            delay(remainingMs.coerceAtLeast(0L))
            // A USB open admitted before the deadline may still be running. Evaluate its
            // terminal result after it finishes; Connecting alone does not supersede Save.
            awaitRetryCompletion()
            val current = isCurrent()
            AppLog.i("SettingsRestart: route=$route fallback=${if (current) "run" else "skip_superseded"}")
            if (current) resumeAutomatic()
        }
        try {
            val current = isCurrent()
            AppLog.i("SettingsRestart: route=$route retry=${if (current) "run" else "skip_superseded"}")
            if (current) retry()
        } finally {
            if (!isCurrent() && !isRetryInFlight()) {
                AppLog.i("SettingsRestart: route=$route fallback=cancel_superseded")
                fallback.cancel()
            }
        }
    }
}
