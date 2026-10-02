package com.andrerinas.openheadunit.decoder.audio

/** Output buffers must be released while waiting for an input slot on old synchronous codecs. */
internal object AacSyncPump {
    const val INPUT_TIMEOUT_MS = 200L
    const val POLL_MS = 5L

    fun awaitInput(nowMs: () -> Long, isRunning: () -> Boolean,
                   drainOutput: () -> Unit, dequeueInput: (Long) -> Int): Int {
        val deadline = nowMs() + INPUT_TIMEOUT_MS
        while (isRunning()) {
            drainOutput()
            if (!isRunning()) return -1
            val remainingMs = (deadline - nowMs()).coerceAtLeast(0)
            val index = dequeueInput(minOf(POLL_MS, remainingMs) * 1000)
            if (index >= 0) return index
            if (nowMs() >= deadline) return -1
        }
        return -1
    }
}
