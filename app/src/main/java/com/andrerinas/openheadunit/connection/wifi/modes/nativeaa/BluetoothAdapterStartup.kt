package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import kotlinx.coroutines.delay

/** Bring the selected Android radio up before opening RFCOMM; an accepted request is not ON. */
internal object BluetoothAdapterStartup {
    const val LAST_SUPPORTED_SDK = 32
    private const val POLL_MS = 250L
    private const val RETRY_MS = 5_000L
    private const val TIMEOUT_MS = 20_000L
    private const val MAX_REQUESTS = 3

    enum class State { OFF, TURNING_ON, ON, TURNING_OFF, UNAVAILABLE }
    enum class Result { READY, REFUSED, TIMED_OUT, UNSUPPORTED, UNAVAILABLE }

    fun supported(sdkInt: Int): Boolean = sdkInt <= LAST_SUPPORTED_SDK

    /**
     * Only OFF accepts an enable request. Wait out a transition, retry a refused request within
     * a fixed budget, and propagate cancellation so a stopped wireless mode never starts later.
     * State and request functions belong to the same adapter, resolved by system service name.
     */
    suspend fun awaitOn(
        sdkInt: Int,
        readState: () -> State,
        requestEnable: () -> Boolean,
    ): Result {
        if (!supported(sdkInt)) return Result.UNSUPPORTED
        var waitedMs = 0L
        var requests = 0
        var nextRequestMs = 0L
        var accepted = false
        while (true) {
            val state = readState()
            if (state == State.ON) return Result.READY
            if (state == State.UNAVAILABLE) return Result.UNAVAILABLE
            if (waitedMs >= TIMEOUT_MS) {
                return if (requests > 0 && !accepted) Result.REFUSED else Result.TIMED_OUT
            }
            if (state == State.OFF && requests < MAX_REQUESTS && waitedMs >= nextRequestMs) {
                accepted = requestEnable() || accepted
                requests++
                nextRequestMs = waitedMs + RETRY_MS
            }
            delay(POLL_MS)
            waitedMs += POLL_MS
        }
    }
}
