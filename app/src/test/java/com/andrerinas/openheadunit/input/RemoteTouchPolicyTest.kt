package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.RemoteTouchPolicy.Action
import com.andrerinas.openheadunit.input.RemoteTouchPolicy.Local
import com.andrerinas.openheadunit.input.RemoteTouchPolicy.Pointer
import com.andrerinas.openheadunit.input.RemoteTouchPolicy.Send
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTouchPolicyTest {
    private val slop = 16f
    private val policy = RemoteTouchPolicy()
    private val out = ArrayList<Send>()

    private fun p(id: Int, x: Float, y: Float) = Pointer(id, x, y)

    private fun feed(local: Local, index: Int, undecided: Boolean, vararg pointers: Pointer, mayStart: Boolean = false) {
        out += policy.onEvent(local, index, pointers.toList(), mayStart, undecided, slop)
    }

    private fun ids(send: Send) = send.pointers.map { it.id }

    private fun holdFirst(x: Float = 60f, y: Float = 452f) = feed(Local.DOWN, 0, false, p(0, x, y), mayStart = true)

    private fun startUndecided(x: Float = 60f, y: Float = 452f) {
        holdFirst(x, y)
        feed(Local.POINTER_DOWN, 1, true, p(0, x, y), p(1, x + 200, y + 50))
    }

    @Test fun aFirstFingerThatMayStartAGestureSendsNothingYet() {
        holdFirst()
        feed(Local.MOVE, 0, false, p(0, 70f, 460f))
        assertTrue(out.isEmpty())
        assertTrue(policy.mayStartGesture)
    }

    @Test fun aWithheldTapSendsDownAndUpAtTheDownPoint() {
        holdFirst()
        feed(Local.MOVE, 0, false, p(0, 66f, 455f))
        feed(Local.UP, 0, false, p(0, 66f, 455f))
        assertEquals(listOf(Send(Action.DOWN, 0, listOf(p(0, 60f, 452f))), Send(Action.UP, 0, listOf(p(0, 60f, 452f)))), out)
        assertFalse(policy.mayStartGesture)
    }

    @Test fun aLoneDragPastSlopSendsDownThenMoveAndForwardsTheRest() {
        holdFirst()
        feed(Local.MOVE, 0, false, p(0, 60f + slop + 1, 452f))
        assertEquals(listOf(Send(Action.DOWN, 0, listOf(p(0, 60f, 452f))), Send(Action.MOVE, 0, listOf(p(0, 77f, 452f)))), out)
        feed(Local.MOVE, 0, false, p(0, 200f, 452f))
        feed(Local.UP, 0, false, p(0, 200f, 452f))
        assertEquals(Send(Action.MOVE, 0, listOf(p(0, 200f, 452f))), out[2])
        assertEquals(Send(Action.UP, 0, listOf(p(0, 200f, 452f))), out[3])
    }

    @Test fun theHoldWindowSendsDownAtTheDownPointAndForwardsTheRest() {
        holdFirst()
        feed(Local.MOVE, 0, false, p(0, 64f, 452f))
        out += policy.holdExpired()
        assertEquals(listOf(Send(Action.DOWN, 0, listOf(p(0, 60f, 452f)))), out)
        assertFalse(policy.mayStartGesture)
        out += policy.holdExpired()
        feed(Local.UP, 0, false, p(0, 64f, 452f))
        assertEquals(listOf(Action.DOWN, Action.UP), out.map { it.action })
    }

    @Test fun aRecognisedGestureSendsNothingForTheWholeStream() {
        startUndecided()
        for (x in listOf(120f, 200f, 300f)) feed(Local.MOVE, 0, true, p(0, x, 452f), p(1, x + 200, 502f))
        policy.recognise()
        out += policy.holdExpired()
        out += policy.giveUp(listOf(p(0, 300f, 452f), p(1, 500f, 502f)))
        feed(Local.CANCEL, 0, false, p(0, 300f, 452f), p(1, 500f, 502f))
        feed(Local.MOVE, 0, false, p(0, 310f, 452f), p(1, 510f, 502f))
        feed(Local.POINTER_UP, 1, false, p(0, 310f, 452f), p(1, 510f, 502f))
        feed(Local.UP, 0, false, p(0, 310f, 452f))
        assertEquals(emptyList<Send>(), out)
    }

    @Test fun aLongPressRecognisedWithNoMovementSendsNothing() {
        startUndecided()
        policy.recognise()
        feed(Local.POINTER_UP, 1, false, p(0, 60f, 452f), p(1, 260f, 502f))
        feed(Local.UP, 0, false, p(0, 60f, 452f))
        assertEquals(emptyList<Send>(), out)
    }

    @Test fun givingUpSendsDownAndPointerDownAtTheCurrentPoints() {
        holdFirst(974f, 300f)
        feed(Local.POINTER_DOWN, 1, true, p(0, 974f, 300f), p(1, 924f, 300f))
        feed(Local.MOVE, 0, true, p(0, 974f, 310f), p(1, 900f, 300f))
        out += policy.giveUp(listOf(p(0, 974f, 310f), p(1, 880f, 300f)))
        assertEquals(listOf(
            Send(Action.DOWN, 0, listOf(p(0, 974f, 310f))),
            Send(Action.POINTER_DOWN, 1, listOf(p(0, 974f, 310f), p(1, 880f, 300f)))
        ), out)
        feed(Local.MOVE, 0, false, p(0, 970f, 310f), p(1, 860f, 300f))
        assertEquals(listOf(0, 1), ids(out.last()))
        feed(Local.POINTER_UP, 1, false, p(0, 970f, 310f), p(1, 860f, 300f))
        assertEquals(Send(Action.POINTER_UP, 1, listOf(p(0, 970f, 310f), p(1, 860f, 300f))), out.last())
        feed(Local.UP, 0, false, p(0, 970f, 310f))
        assertEquals(Send(Action.UP, 0, listOf(p(0, 970f, 310f))), out.last())
        assertTrue(out.none { it.action == Action.UP && it.pointers[0].x == 974f })
    }

    @Test fun aSecondFingerAfterTheFirstWasForwardedIsNeverWithheld() {
        holdFirst()
        out += policy.holdExpired()
        assertFalse(policy.mayStartGesture)
        feed(Local.POINTER_DOWN, 1, true, p(0, 60f, 452f), p(1, 260f, 502f))
        assertEquals(Send(Action.POINTER_DOWN, 1, listOf(p(0, 60f, 452f), p(1, 260f, 502f))), out.last())
    }

    @Test fun aFirstFingerOutsideTheZoneIsForwardedAtOnce() {
        feed(Local.DOWN, 0, false, p(0, 400f, 300f))
        assertEquals(listOf(Send(Action.DOWN, 0, listOf(p(0, 400f, 300f)))), out)
        assertFalse(policy.mayStartGesture)
    }

    @Test fun aCentrePinchForwardsBothFingers() {
        feed(Local.DOWN, 0, false, p(0, 400f, 300f))
        feed(Local.POINTER_DOWN, 1, false, p(0, 400f, 300f), p(1, 600f, 300f))
        feed(Local.MOVE, 0, false, p(0, 380f, 300f), p(1, 620f, 300f))
        assertEquals(Send(Action.POINTER_DOWN, 1, listOf(p(0, 400f, 300f), p(1, 600f, 300f))), out[1])
        assertEquals(listOf(0, 1), ids(out[2]))
    }

    @Test fun anUndecidedTwoFingerTouchThatLiftsOneFingerSendsNothing() {
        // Short of travel, so the gesture can still fire, then either finger lifts first.
        for (lifted in 0..1) {
            out.clear()
            startUndecided()
            feed(Local.MOVE, 0, true, p(0, 210f, 452f), p(1, 410f, 502f))
            feed(Local.POINTER_UP, lifted, false, p(0, 210f, 452f), p(1, 410f, 502f))
            val left = p(1 - lifted, if (lifted == 0) 410f else 210f, if (lifted == 0) 502f else 452f)
            feed(Local.MOVE, 0, false, left)
            feed(Local.UP, 0, false, left)
            assertTrue(out.isEmpty())
            assertFalse(policy.mayStartGesture)
        }
        feed(Local.DOWN, 0, false, p(0, 400f, 300f))
        assertEquals(listOf(Send(Action.DOWN, 0, listOf(p(0, 400f, 300f)))), out)
    }

    @Test fun aCancelSendsNothingInAnyPhase() {
        holdFirst()
        feed(Local.CANCEL, 0, false, p(0, 60f, 452f))
        startUndecided()
        feed(Local.CANCEL, 0, false, p(0, 60f, 452f), p(1, 260f, 502f))
        assertTrue(out.isEmpty())
        feed(Local.DOWN, 0, false, p(0, 400f, 300f))
        out.clear()
        feed(Local.CANCEL, 0, false, p(0, 400f, 300f))
        feed(Local.MOVE, 0, false, p(0, 410f, 300f))
        feed(Local.UP, 0, false, p(0, 410f, 300f))
        assertTrue(out.isEmpty())
    }

    @Test fun theHoldEndsWellBeforeAOneFingerLongPress() {
        // 400 ms is Android's shortest default long-press timeout.
        assertTrue(RemoteTouchPolicy.HOLD_MS * 2 <= 400L)
    }

    @Test fun theActionEnumHasNoCancel() {
        assertEquals(setOf("DOWN", "MOVE", "UP", "POINTER_DOWN", "POINTER_UP"), Action.values().map { it.name }.toSet())
    }

    @Test fun describeNamesTheActionAndTheIds() {
        assertEquals("Remote touch: action=MOVE count=1 ids=0",
            RemoteTouchPolicy.describe(Send(Action.MOVE, 0, listOf(p(0, 1f, 1f)))))
        assertEquals("Remote touch: action=POINTER_DOWN count=2 ids=0,1",
            RemoteTouchPolicy.describe(Send(Action.POINTER_DOWN, 1, listOf(p(0, 1f, 1f), p(1, 2f, 2f)))))
    }

    @Test fun theActivityLogsAGestureAsWithheldAndKeysTheTrackerOnTheHold() {
        val src = File("src/main/java/com/andrerinas/openheadunit/aap/AapProjectionActivity.kt").readText()
        assertTrue(src.contains("withheld from Android Auto\""))
        assertTrue(src.contains("ev.pointerCount == 2 && remoteTouch.mayStartGesture"))
        assertFalse(src.contains("clearance"))
    }
}
