package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNativeScreenPolicyTest {
    private val begun = listOf(Phase.Opening, Phase.Pairing, Phase.Iap2, Phase.Authenticating, Phase.StartSent, Phase.NetworkUp, Phase.AirPlay, Phase.Streaming)
    private val prepare = listOf(Phase.Idle, Phase.AwaitReenumeration, Phase.AwaitSecondPermission, Phase.Ended("x"))

    @Test fun `the screen is raised once per end count, while a live session holds the bus`() {
        begun.forEach { assertTrue("$it", CarPlayNativeScreenPolicy.raise(it, live = true, ends = 0, raisedAt = null)) }
        begun.forEach { assertFalse("$it", CarPlayNativeScreenPolicy.raise(it, live = true, ends = 0, raisedAt = 0)) }
        prepare.forEach { assertFalse("$it", CarPlayNativeScreenPolicy.raise(it, live = true, ends = 0, raisedAt = null)) }
        assertFalse(CarPlayNativeScreenPolicy.raise(Phase.Streaming, live = false, ends = 1, raisedAt = 0))
    }

    @Test fun `a collector that skips Opening still raises at the first phase it sees`() {
        assertTrue(CarPlayNativeScreenPolicy.raise(Phase.NetworkUp, live = true, ends = 3, raisedAt = 2))
    }

    @Test fun `merged phases cannot hide a new session, because the end count moved`() {
        // The old session streamed at count 4 and was raised; Ended, Idle and Opening merged into Streaming of the next one.
        assertTrue(CarPlayNativeScreenPolicy.raise(Phase.Streaming, live = true, ends = 5, raisedAt = 4))
        assertFalse(CarPlayNativeScreenPolicy.raise(Phase.Streaming, live = true, ends = 4, raisedAt = 4))
    }

    @Test fun `the screen closes once the end count moves past the value it started with`() {
        assertFalse(CarPlayNativeScreenPolicy.finishOnEnds(startEnds = 7, ends = 7))
        assertTrue(CarPlayNativeScreenPolicy.finishOnEnds(startEnds = 7, ends = 8))
    }

    @Test fun `the screen closes on Ended and on an Idle that no live session backs`() {
        assertTrue(CarPlayNativeScreenPolicy.finish(Phase.Ended("user exit"), live = true))
        assertTrue(CarPlayNativeScreenPolicy.finish(Phase.Idle, live = false))
        assertFalse(CarPlayNativeScreenPolicy.finish(Phase.Idle, live = true))
        begun.forEach { assertFalse("$it", CarPlayNativeScreenPolicy.finish(it, live = true)) }
        assertFalse(CarPlayNativeScreenPolicy.finish(Phase.AwaitReenumeration, live = false))
    }

    @Test fun `a replayed attach shows Ended then Idle then Opening and each step is judged on its own`() {
        val seen = listOf(Phase.Ended("unplugged"), Phase.Idle, Phase.Opening)
        assertEquals(listOf(true, true, false), seen.map { CarPlayNativeScreenPolicy.finish(it, live = it == Phase.Opening) })
    }
}
