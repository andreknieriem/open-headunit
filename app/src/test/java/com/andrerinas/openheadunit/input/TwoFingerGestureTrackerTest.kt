package com.andrerinas.openheadunit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerGestureTrackerTest {
    private fun tracker(
        x1: Float = 20f, y1: Float = 300f, x2: Float = 220f, y2: Float = 350f, density: Float = 1f
    ) = TwoFingerGestureTracker(x1, y1, x2, y2, width = 1200f, touchSlop = 10f, density = density)

    private fun centre() = tracker(x1 = 400f, x2 = 600f)

    @Test fun downwardSwipeStartsInAnEdgeZone() {
        val tracker = tracker()
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN, tracker.move(30f, 400f, 230f, 450f))
        assertFalse(tracker.canLongPress)
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN,
            tracker(x1 = 1180f, x2 = 1000f).move(1180f, 400f, 1000f, 450f))
    }

    @Test fun downwardSwipeFromTheMiddleOfTheScreenIsNeverAGesture() {
        assertNull(centre().move(410f, 400f, 610f, 450f))
        assertNull(centre().move(400f, 900f, 600f, 950f))
    }

    @Test fun bothFingersMustSwipeDown() {
        assertNull(tracker().move(20f, 400f, 220f, 350f))
        assertNull(tracker().move(20f, 300f, 220f, 450f))
        assertNull(tracker().move(20f, 400f, 220f, 250f))
    }

    @Test fun upwardAndDiagonalMovementDoNotTriggerDownwardSwipe() {
        assertNull(tracker().move(20f, 200f, 220f, 250f))
        assertNull(tracker().move(120f, 400f, 320f, 450f))
    }

    @Test fun distanceMustExceedTheSwipeThreshold() {
        assertNull(tracker().move(20f, 380f, 220f, 430f))
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN, tracker().move(20f, 381f, 220f, 431f))
    }

    @Test fun minorJitterAllowsLongPressButMovementOfEitherFingerCancelsIt() {
        val stationary = tracker()
        assertTrue(stationary.canLongPress)
        assertNull(stationary.move(23f, 304f, 217f, 346f))
        assertTrue(stationary.canLongPress)

        val firstMoves = tracker()
        firstMoves.move(31f, 300f, 220f, 350f)
        assertFalse(firstMoves.canLongPress)

        val secondMoves = tracker()
        secondMoves.move(20f, 300f, 220f, 361f)
        assertFalse(secondMoves.canLongPress)
        secondMoves.move(20f, 300f, 220f, 350f)
        assertFalse(secondMoves.canLongPress)
    }

    @Test fun horizontalSwipesRequireTheCorrespondingEdge() {
        assertEquals(TwoFingerGestureTracker.Swipe.LEFT_EDGE,
            tracker(x1 = 20f, x2 = 60f).move(230f, 300f, 270f, 350f))
        assertEquals(TwoFingerGestureTracker.Swipe.RIGHT_EDGE,
            tracker(x1 = 1180f, x2 = 1140f).move(970f, 300f, 930f, 350f))
        assertNull(centre().move(610f, 300f, 810f, 350f))
        assertNull(centre().move(190f, 300f, 390f, 350f))
    }

    @Test fun aTouchInTheMiddleCanNeverBecomeAGesture() {
        for (mask in 0..15) {
            assertFalse(centre().mayBecome(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0))
        }
    }

    @Test fun theLeftZoneStartsAnyEnabledActionThatCanStartThere() {
        val t = tracker(x1 = 20f, x2 = 60f)
        assertTrue(t.mayBecome(left = true, right = false, down = false, longPress = false))
        assertTrue(t.mayBecome(left = false, right = false, down = true, longPress = false))
        assertTrue(t.mayBecome(left = false, right = false, down = false, longPress = true))
        assertFalse(t.mayBecome(left = false, right = true, down = false, longPress = false))
        assertFalse(t.mayBecome(left = false, right = false, down = false, longPress = false))
    }

    @Test fun theRightZoneMirrorsTheLeft() {
        val t = tracker(x1 = 1180f, x2 = 1140f)
        assertTrue(t.mayBecome(left = false, right = true, down = false, longPress = false))
        assertTrue(t.mayBecome(left = false, right = false, down = true, longPress = false))
        assertTrue(t.mayBecome(left = false, right = false, down = false, longPress = true))
        assertFalse(t.mayBecome(left = true, right = false, down = false, longPress = false))
    }

    @Test fun inEdgeZoneIsTrueOnlyNearAnEdge() {
        assertFalse(centre().inEdgeZone)
        assertTrue(tracker(x1 = 20f).inEdgeZone)
        assertTrue(tracker(x1 = 1180f, x2 = 1000f).inEdgeZone)
    }

    @Test fun movingTwoFingersDownFromTheCentreReturnsNothing() {
        assertNull(centre().move(400f, 500f, 600f, 550f))
        assertEquals(TwoFingerGestureTracker.Swipe.DOWN, tracker().move(20f, 500f, 220f, 550f))
    }

    @Test fun theRailIconColumnIsInsideTheLeftZoneAtEveryDensity() {
        for (density in listOf(1f, 1.5f, 2f)) {
            assertTrue(tracker(x1 = 60f, x2 = 260f, density = density).inEdgeZone)
        }
    }

    private fun trackerAtRight() = tracker(x1 = 1150f, y1 = 300f, x2 = 1100f, y2 = 300f)

    @Test fun aPinchGivesUpOnceTheSpreadChanges() {
        val t = trackerAtRight()
        t.move(1150f, 300f, 950f, 300f)
        assertFalse(t.canStillFire(left = true, right = true, down = false, longPress = false))
    }

    @Test fun twoFingersMovingTogetherKeepTheSwipeAlive() {
        val t = trackerAtRight()
        assertEquals(TwoFingerGestureTracker.Swipe.RIGHT_EDGE, t.move(850f, 300f, 800f, 300f))
        assertTrue(t.canStillFire(left = true, right = true, down = false, longPress = false))
    }

    @Test fun aLongPressOnlyGivesUpWhenAFingerMoves() {
        val t = tracker()
        t.move(50f, 300f, 220f, 350f)
        assertFalse(t.canStillFire(left = false, right = false, down = false, longPress = true))
    }

    @Test fun aDownSwipeGivesUpWhenTheFingersMoveUp() {
        val t = tracker()
        t.move(20f, 270f, 220f, 320f)
        assertFalse(t.canStillFire(left = false, right = false, down = true, longPress = false))
    }

    @Test fun anEdgeSwipeGivesUpWhenItDriftsSidewaysOrBackwards() {
        val t = tracker()
        t.move(20f, 450f, 220f, 500f)
        assertFalse(t.canStillFire(left = true, right = false, down = false, longPress = false))
        val back = tracker(x1 = 60f, x2 = 260f)
        back.move(30f, 300f, 230f, 350f)
        assertFalse(back.canStillFire(left = true, right = false, down = false, longPress = false))
    }
}
