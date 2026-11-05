package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStopPolicyTest {

    private fun drive(meters: Double, atMs: Long, state: TripStopPolicy.State = TripStopPolicy.State()) =
        TripStopPolicy.onFix(state, meters, 15f, atMs).state

    @Test
    fun `standing still long enough after a real drive is a stop`() {
        var state = drive(600.0, 0)
        state = TripStopPolicy.onFix(state, 0.0, 0f, 1_000).state
        val result = TripStopPolicy.onFix(state, 0.0, 0f, 1_000 + TripStopPolicy.STOP_MS)
        assertTrue(result.stopped)
        assertEquals(0.0, result.state.drivenMeters, 0.0)
    }

    @Test
    fun `a red light is not a stop`() {
        var state = drive(600.0, 0)
        state = TripStopPolicy.onFix(state, 0.0, 0f, 1_000).state
        assertFalse(TripStopPolicy.onFix(state, 0.0, 0f, 20_000).stopped)
    }

    @Test
    fun `creeping in a parking lot does not count as a drive`() {
        var state = drive(100.0, 0)
        state = TripStopPolicy.onFix(state, 0.0, 0f, 1_000).state
        assertFalse(TripStopPolicy.onFix(state, 0.0, 0f, 60_000).stopped)
    }

    @Test
    fun `moving again resets the still timer`() {
        var state = drive(600.0, 0)
        state = TripStopPolicy.onFix(state, 0.0, 0f, 1_000).state
        state = drive(20.0, 20_000, state)
        state = TripStopPolicy.onFix(state, 0.0, 0f, 25_000).state
        assertFalse(TripStopPolicy.onFix(state, 0.0, 0f, 40_000).stopped)
    }

    @Test
    fun `it fires once per stop`() {
        var state = drive(600.0, 0)
        state = TripStopPolicy.onFix(state, 0.0, 0f, 1_000).state
        state = TripStopPolicy.onFix(state, 0.0, 0f, 31_000).state
        assertFalse(TripStopPolicy.onFix(state, 0.0, 0f, 90_000).stopped)
    }

    @Test
    fun `no speed is not standing still`() {
        var state = drive(600.0, 0)
        state = TripStopPolicy.onFix(state, 0.0, null, 1_000).state
        assertFalse(TripStopPolicy.onFix(state, 0.0, null, 60_000).stopped)
    }
}
