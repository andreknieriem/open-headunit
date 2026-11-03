package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothAdapterStartup.Result
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothAdapterStartup.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothAdapterStartupTest {
    @Test fun `an already enabled adapter needs no enable request`() = runTest {
        assertEquals(Result.READY, BluetoothAdapterStartup.awaitOn(27, { State.ON }) {
            fail("must not request enable on an enabled adapter")
            false
        })
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `an accepted enable request waits until the adapter reports ON`() = runTest {
        var state = State.OFF
        var requests = 0
        launch { kotlinx.coroutines.delay(1_000); state = State.ON }
        assertEquals(Result.READY, BluetoothAdapterStartup.awaitOn(27, { state }) {
            requests++
            state = State.TURNING_ON
            true
        })
        assertEquals(1, requests)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test fun `an accepted enable request alone is not success`() = runTest {
        var requests = 0
        assertEquals(Result.TIMED_OUT, BluetoothAdapterStartup.awaitOn(27, { State.OFF }) {
            requests++
            true
        })
        assertEquals(3, requests)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `a refused request is retried with a fixed budget`() = runTest {
        val times = mutableListOf<Long>()
        assertEquals(Result.REFUSED, BluetoothAdapterStartup.awaitOn(27, { State.OFF }) {
            times += testScheduler.currentTime
            false
        })
        assertEquals(listOf(0L, 5_000L, 10_000L), times)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `a transition already in progress is allowed to finish`() = runTest {
        var state = State.TURNING_ON
        launch { kotlinx.coroutines.delay(500); state = State.ON }
        assertEquals(Result.READY, BluetoothAdapterStartup.awaitOn(32, { state }) {
            fail("must not interrupt a transition")
            false
        })
    }

    @Test fun `an adapter turning off is enabled only after reaching OFF`() = runTest {
        var state = State.TURNING_OFF
        var enableAt = -1L
        launch { kotlinx.coroutines.delay(1_000); state = State.OFF }
        assertEquals(Result.READY, BluetoothAdapterStartup.awaitOn(27, { state }) {
            assertEquals(State.OFF, state)
            enableAt = testScheduler.currentTime
            state = State.ON
            true
        })
        assertEquals(1_000L, enableAt)
    }

    @Test fun `Android 13 and later never use the legacy enable API`() = runTest {
        for (sdk in listOf(33, 34, 36)) {
            assertEquals(Result.UNSUPPORTED, BluetoothAdapterStartup.awaitOn(sdk, {
                fail("must not inspect an unsupported adapter")
                State.OFF
            }) { fail("must not request enable on Android 13+"); false })
        }
    }

    @Test fun `an unavailable adapter returns without changing any radio`() = runTest {
        assertEquals(Result.UNAVAILABLE, BluetoothAdapterStartup.awaitOn(27, { State.UNAVAILABLE }) {
            fail("must not enable an unavailable adapter")
            false
        })
    }

    @Test fun `cancelling a startup prevents later requests or a ready callback`() = runTest {
        var requests = 0
        var completed = false
        val job = launch {
            BluetoothAdapterStartup.awaitOn(27, { State.OFF }) { requests++; true }
            completed = true
        }
        runCurrent()
        advanceTimeBy(1_000)
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, requests)
        assertFalse(completed)
    }
}
