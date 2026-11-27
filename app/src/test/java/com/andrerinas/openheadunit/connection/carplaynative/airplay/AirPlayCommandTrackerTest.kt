package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayCommandTrackerTest {

    @Test fun anAnswerNamesItsCommandAndCountsPerType() {
        val tracker = AirPlayCommandTracker()
        tracker.sent(1, "hidSendReport")
        tracker.sent(2, "forceKeyFrame")
        tracker.sent(3, "hidSendReport")
        assertEquals(1, tracker.answered(1)!!.count)
        assertEquals("forceKeyFrame", tracker.answered(2)!!.type)
        assertEquals(2, tracker.answered(3)!!.count)
    }

    @Test fun aCseqWeNeverSentOrAlreadyAnsweredGivesNothing() {
        val tracker = AirPlayCommandTracker()
        tracker.sent(5, "hidSendReport")
        assertNull(tracker.answered(6))
        assertEquals(1, tracker.answered(5)!!.count)
        assertNull(tracker.answered(5))
    }

    @Test fun onlyTheOldestUnansweredCommandsAreForgotten() {
        val tracker = AirPlayCommandTracker()
        for (n in 1..AirPlayCommandTracker.MAX_PENDING + 1) tracker.sent(n, "hidSendReport")
        assertNull(tracker.answered(1))
        assertEquals(1, tracker.answered(2)!!.count)
        assertEquals(2, tracker.answered(AirPlayCommandTracker.MAX_PENDING + 1)!!.count)
    }

    @Test fun theLogShowsTheFirstThreeAndEveryHundredth() {
        val tracker = AirPlayCommandTracker()
        val shown = (1..250).filter { n ->
            tracker.sent(n, "hidSendReport")
            tracker.answered(n)!!.shouldLog
        }
        assertEquals(listOf(1, 2, 3, 100, 200), shown)
        assertTrue(tracker.run { sent(1000, "forceKeyFrame"); answered(1000)!!.shouldLog })
        assertFalse(AirPlayCommandTracker.Answered("x", 4).shouldLog)
    }
}
