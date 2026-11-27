package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFrameThrottleTest {

    @Test
    fun theFirstRequestPasses() {
        assertTrue(KeyFrameThrottle().tryAcquire(0))
    }

    @Test
    fun twoRequestsInsideTheWindowGiveOne() {
        val throttle = KeyFrameThrottle()
        assertTrue(throttle.tryAcquire(5_000))
        assertFalse(throttle.tryAcquire(5_999))
    }

    @Test
    fun twoRealRequestsOneWindowApartGiveTwo() {
        val throttle = KeyFrameThrottle()
        assertTrue(throttle.tryAcquire(5_000))
        assertTrue(throttle.tryAcquire(6_000))
    }

    @Test
    fun aHeldTrainGivesOneAWindowAndARefusedCallDoesNotMoveIt() {
        val throttle = KeyFrameThrottle()
        val passed = (0..30).map { it * 100L }.count { throttle.tryAcquire(it) }
        // Calls at 0, 100, ... 3000: the window opens at 0, 1000, 2000 and 3000.
        assertTrue(passed == 4)
    }

    @Test
    fun aReleasedSlotIsFreeAgain() {
        val throttle = KeyFrameThrottle()
        assertTrue(throttle.tryAcquire(100))
        throttle.release()
        assertTrue(throttle.tryAcquire(200))
    }
}
