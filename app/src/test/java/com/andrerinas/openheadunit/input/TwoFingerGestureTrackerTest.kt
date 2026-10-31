package com.andrerinas.openheadunit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerGestureTrackerTest {
    private fun tracker(x1: Float = 400f, y1: Float = 300f, x2: Float = 600f, y2: Float = 350f) =
        TwoFingerGestureTracker(x1, y1, x2, y2, width = 1200f, touchSlop = 10f, downSwipeDistance = 80f)

    @Test fun downwardSwipeStartsInTheMiddleOfTheScreen() {
        val tracker = tracker()
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN, tracker.move(410f, 400f, 610f, 450f))
        assertFalse(tracker.canLongPress)
    }

    @Test fun downwardSwipeCanAlsoStartNearTheBottomOrSide() {
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN,
            tracker(y1 = 800f, y2 = 850f).move(400f, 900f, 600f, 950f))
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN,
            tracker(x1 = 20f, x2 = 70f).move(20f, 400f, 70f, 450f))
    }

    @Test fun bothFingersMustSwipeDown() {
        assertNull(tracker().move(400f, 400f, 600f, 350f))
        assertNull(tracker().move(400f, 300f, 600f, 450f))
        assertNull(tracker().move(400f, 400f, 600f, 250f))
    }

    @Test fun upwardAndDiagonalMovementDoNotTriggerDownwardSwipe() {
        assertNull(tracker().move(400f, 200f, 600f, 250f))
        assertNull(tracker().move(500f, 400f, 700f, 450f))
    }

    @Test fun distanceMustExceedTheSwipeThreshold() {
        assertNull(tracker().move(400f, 380f, 600f, 430f))
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN, tracker().move(400f, 381f, 600f, 431f))
    }

    @Test fun minorJitterAllowsLongPressButMovementOfEitherFingerCancelsIt() {
        val stationary = tracker()
        assertTrue(stationary.canLongPress)
        assertNull(stationary.move(403f, 304f, 597f, 346f))
        assertTrue(stationary.canLongPress)

        val firstMoves = tracker()
        firstMoves.move(411f, 300f, 600f, 350f)
        assertFalse(firstMoves.canLongPress)

        val secondMoves = tracker()
        secondMoves.move(400f, 300f, 600f, 361f)
        assertFalse(secondMoves.canLongPress)
        secondMoves.move(400f, 300f, 600f, 350f)
        assertFalse(secondMoves.canLongPress)
    }

    @Test fun horizontalSwipesRequireTheCorrespondingEdge() {
        assertEquals(TwoFingerGestureTracker.Swipe.LEFT_EDGE,
            tracker(x1 = 20f, x2 = 60f).move(230f, 300f, 270f, 350f))
        assertEquals(TwoFingerGestureTracker.Swipe.RIGHT_EDGE,
            tracker(x1 = 1180f, x2 = 1140f).move(970f, 300f, 930f, 350f))
        assertNull(tracker().move(610f, 300f, 810f, 350f))
        assertNull(tracker().move(190f, 300f, 390f, 350f))
    }
}
