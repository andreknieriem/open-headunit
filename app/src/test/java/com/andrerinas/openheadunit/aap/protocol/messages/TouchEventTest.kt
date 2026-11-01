package com.andrerinas.openheadunit.aap.protocol.messages

import android.view.MotionEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Input
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TouchEventTest {
    @Test
    fun `maps supported android touch actions to aap actions`() {
        assertAction(MotionEvent.ACTION_DOWN, Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN)
        assertAction(MotionEvent.ACTION_POINTER_DOWN, Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN)
        assertAction(MotionEvent.ACTION_MOVE, Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE)
        assertAction(MotionEvent.ACTION_UP, Input.TouchEvent.PointerAction.TOUCH_ACTION_UP)
        assertAction(MotionEvent.ACTION_POINTER_UP, Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP)
    }

    @Test
    fun `does not map cancel and outside actions`() {
        assertAction(MotionEvent.ACTION_CANCEL, null)
        assertAction(MotionEvent.ACTION_OUTSIDE, null)
    }

    private fun assertAction(action: Int, expected: Input.TouchEvent.PointerAction?) {
        val event = mock<MotionEvent>()
        whenever(event.actionMasked).thenReturn(action)
        assertEquals(expected, TouchEvent.motionEventToAction(event))
    }
}


