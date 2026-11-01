package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.TwoFingerGestureTracker.Enabled
import com.andrerinas.openheadunit.input.TwoFingerGestureTracker.Tap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerGestureTrackerTest {
    private val normalDp = 64f

    private fun tracker(
        x1: Float = 20f, y1: Float = 300f, x2: Float = 220f, y2: Float = 350f,
        density: Float = 1f, zoneDp: Float = normalDp, downMs: Long = 1000L
    ) = TwoFingerGestureTracker(x1, y1, x2, y2, width = 1200f, touchSlop = 10f, density = density,
        edgeZoneDp = zoneDp, secondDownMs = downMs)

    private fun centre() = tracker(x1 = 400f, x2 = 600f)

    private fun enabled(
        left: Boolean = false, right: Boolean = false, longPress: Boolean = false,
        leftTap: Boolean = false, rightTap: Boolean = false
    ) = Enabled(left, right, longPress, leftTap, rightTap)

    private fun mask(m: Int) = Enabled(m and 1 != 0, m and 2 != 0, m and 4 != 0, m and 8 != 0, m and 16 != 0)

    private val taps = enabled(leftTap = true, rightTap = true)

    @Test fun minorJitterKeepsTheTouchStillButMovementOfEitherFingerEndsIt() {
        val stationary = tracker()
        assertTrue(stationary.still)
        assertNull(stationary.move(23f, 304f, 217f, 346f))
        assertTrue(stationary.still)

        val firstMoves = tracker()
        firstMoves.move(31f, 300f, 220f, 350f)
        assertFalse(firstMoves.still)

        val secondMoves = tracker()
        secondMoves.move(20f, 300f, 220f, 361f)
        assertFalse(secondMoves.still)
        secondMoves.move(20f, 300f, 220f, 350f)
        assertFalse(secondMoves.still)
    }

    @Test fun horizontalSwipesRequireTheCorrespondingEdge() {
        assertEquals(TwoFingerGestureTracker.Swipe.LEFT_EDGE,
            tracker(x1 = 20f, x2 = 60f).move(230f, 300f, 270f, 350f))
        assertEquals(TwoFingerGestureTracker.Swipe.RIGHT_EDGE,
            tracker(x1 = 1180f, x2 = 1140f).move(970f, 300f, 930f, 350f))
        assertNull(centre().move(610f, 300f, 810f, 350f))
        assertNull(centre().move(190f, 300f, 390f, 350f))
    }

    @Test fun twoFingersMovingDownAreNoGesture() {
        assertNull(tracker().move(30f, 400f, 230f, 450f))
        assertNull(tracker(x1 = 1180f, x2 = 1000f).move(1180f, 400f, 1000f, 450f))
        assertEquals(setOf("LEFT_EDGE", "RIGHT_EDGE"), TwoFingerGestureTracker.Swipe.values().map { it.name }.toSet())
    }

    @Test fun noMainSourceReadsTheDownSwipe() {
        val main = File("src/main")
        val hits = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> f.readText().let { "down-two-finger" in it || "downTwoFinger" in it || "gesture_two_finger_down" in it } }
            .map { it.path }.toList()
        assertTrue(main.isDirectory)
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun aTouchInTheMiddleCanNeverBecomeAGesture() {
        for (m in 0..31) assertFalse(centre().mayBecome(mask(m)))
    }

    @Test fun theLeftZoneStartsAnyEnabledActionThatCanStartThere() {
        val t = tracker(x1 = 20f, x2 = 60f)
        assertTrue(t.mayBecome(enabled(left = true)))
        assertTrue(t.mayBecome(enabled(longPress = true)))
        assertTrue(t.mayBecome(enabled(leftTap = true)))
        assertFalse(t.mayBecome(enabled(right = true)))
        assertFalse(t.mayBecome(enabled(rightTap = true)))
        assertFalse(t.mayBecome(enabled()))
    }

    @Test fun theRightZoneMirrorsTheLeft() {
        val t = tracker(x1 = 1180f, x2 = 1140f)
        assertTrue(t.mayBecome(enabled(right = true)))
        assertTrue(t.mayBecome(enabled(longPress = true)))
        assertTrue(t.mayBecome(enabled(rightTap = true)))
        assertFalse(t.mayBecome(enabled(left = true)))
        assertFalse(t.mayBecome(enabled(leftTap = true)))
    }

    @Test fun mayStartAtGivesTheSameAnswerAsTheTrackerForEveryFirstFinger() {
        for (zone in listOf(48f, 64f, 96f)) {
            for (density in listOf(1f, 1.5f, 2f, 3f)) {
                for (x in listOf(0f, 20f, 99f, 101f, 150f, 600f, 1050f, 1099f, 1101f, 1199f)) {
                    for (m in 0..31) {
                        assertEquals(
                            tracker(x1 = x, x2 = 600f, density = density, zoneDp = zone).mayBecome(mask(m)),
                            TwoFingerGestureTracker.mayStartAt(x, 1200f, density, zone, mask(m))
                        )
                    }
                }
            }
        }
        assertTrue(TwoFingerGestureTracker.mayStartAt(20f, 1200f, 1f, normalDp, enabled(left = true)))
        assertFalse(TwoFingerGestureTracker.mayStartAt(600f, 1200f, 1f, normalDp, mask(31)))
    }

    @Test fun theZoneWidthMovesWhereAGestureMayStart() {
        // Density 1.5: Normal is 96 dp at the floor of 100 px, Wide is 144 px.
        assertFalse(TwoFingerGestureTracker.mayStartAt(130f, 1200f, 1.5f, 64f, enabled(left = true)))
        assertTrue(TwoFingerGestureTracker.mayStartAt(130f, 1200f, 1.5f, 96f, enabled(left = true)))
        assertTrue(TwoFingerGestureTracker.mayStartAt(1070f, 1200f, 1.5f, 96f, enabled(right = true)))
    }

    @Test fun inEdgeZoneIsTrueOnlyNearAnEdge() {
        assertFalse(centre().inEdgeZone)
        assertTrue(tracker(x1 = 20f).inEdgeZone)
        assertTrue(tracker(x1 = 1180f, x2 = 1000f).inEdgeZone)
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
        assertFalse(t.canStillFire(mask(31), 1000L))
    }

    @Test fun twoFingersMovingTogetherKeepTheSwipeAlive() {
        val t = trackerAtRight()
        assertEquals(TwoFingerGestureTracker.Swipe.RIGHT_EDGE, t.move(850f, 300f, 800f, 300f))
        assertTrue(t.canStillFire(enabled(left = true, right = true), 1100L))
    }

    @Test fun aLongPressOnlyGivesUpWhenAFingerMoves() {
        val t = tracker()
        assertTrue(t.canStillFire(enabled(longPress = true), 5000L))
        t.move(50f, 300f, 220f, 350f)
        assertFalse(t.canStillFire(enabled(longPress = true), 1000L))
    }

    @Test fun anEdgeSwipeGivesUpWhenItDriftsSidewaysOrBackwards() {
        val t = tracker()
        t.move(20f, 450f, 220f, 500f)
        assertFalse(t.canStillFire(enabled(left = true), 1000L))
        val back = tracker(x1 = 60f, x2 = 260f)
        back.move(30f, 300f, 230f, 350f)
        assertFalse(back.canStillFire(enabled(left = true), 1000L))
    }

    @Test fun aLeftTapAndARightTapEachFireTheirSlot() {
        assertEquals(Tap.LEFT_TAP, tracker(x1 = 60f, x2 = 260f).tap(taps, 1000L + TwoFingerGestureTracker.TAP_MS))
        assertEquals(Tap.RIGHT_TAP, tracker(x1 = 1150f, x2 = 950f).tap(taps, 1200L))
        assertNull(centre().tap(taps, 1100L))
    }

    @Test fun aTapNeedsItsOwnSlotEnabled() {
        assertNull(tracker(x1 = 60f, x2 = 260f).tap(enabled(rightTap = true, left = true), 1100L))
        assertFalse(tracker(x1 = 60f, x2 = 260f).mayTap(enabled(left = true, longPress = true), 1100L))
        assertEquals(Tap.RIGHT_TAP, tracker(x1 = 1150f, x2 = 950f).tap(enabled(rightTap = true), 1100L))
    }

    @Test fun aSlowTapCanNoLongerFireAndGivesUp() {
        val t = tracker(x1 = 60f, x2 = 260f)
        val late = 1000L + TwoFingerGestureTracker.TAP_MS + 1
        assertTrue(t.canStillFire(taps, 1000L + TwoFingerGestureTracker.TAP_MS))
        assertFalse(t.mayTap(taps, late))
        assertNull(t.tap(taps, late))
        assertFalse(t.canStillFire(taps, late))
    }

    @Test fun aTapGivesUpWhenAFingerMoves() {
        val t = tracker(x1 = 60f, x2 = 260f)
        t.move(60f, 300f, 260f, 365f)
        assertFalse(t.canStillFire(taps, 1100L))
        assertNull(t.tap(taps, 1100L))
    }

    @Test fun theFingerLeftDownMustStayStillForATap() {
        val stays = tracker(x1 = 60f, x2 = 260f)
        stays.moveOne(isFirst = false, x = 265f, y = 355f)
        assertEquals(Tap.LEFT_TAP, stays.tap(taps, 1200L))
        val moves = tracker(x1 = 60f, x2 = 260f)
        moves.moveOne(isFirst = true, x = 60f, y = 330f)
        assertNull(moves.tap(taps, 1200L))
    }

    @Test fun tapEndsInMsCountsDownFromTheSecondFingerAndStopsAtZero() {
        val t = tracker(downMs = 1000L)
        assertEquals(TwoFingerGestureTracker.TAP_MS, t.tapEndsInMs(1000L))
        assertEquals(TwoFingerGestureTracker.TAP_MS - 100L, t.tapEndsInMs(1100L))
        assertEquals(0L, t.tapEndsInMs(1000L + TwoFingerGestureTracker.TAP_MS + 50L))
    }

    @Test fun theTapWindowIsShorterThanALongPress() {
        // 400 ms is Android's shortest default long-press timeout.
        assertTrue(TwoFingerGestureTracker.TAP_MS < 400L)
    }
}
