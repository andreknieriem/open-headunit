package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Save owns one route retry; the ordinary connection rules resume if it never starts. */
internal object SettingsRestartRecovery {
    // Legacy Self Mode can take 15–20 seconds to dial port 5288. Keep its existing 30-second
    // allowance, while bounding both the Native wake hold and Nearby's peer preference.
    const val WINDOW_MS = 30_000L

    suspend fun run(
        remainingMs: Long,
        isCurrent: () -> Boolean,
        retry: suspend () -> Unit,
        resumeAutomatic: () -> Unit,
    ) = coroutineScope {
        // Arm before retry: USB may return without a device, or the arbiter may refuse a dial.
        // Identity belongs to this Disconnected instance, not just its reason or last route.
        val fallback = launch {
            delay(remainingMs.coerceAtLeast(0L))
            if (isCurrent()) resumeAutomatic()
        }
        try {
            if (isCurrent()) retry()
        } finally {
            if (!isCurrent()) fallback.cancel()
        }
    }
}
