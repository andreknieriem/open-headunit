package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DongfengWheelKeyTest {

    private fun line(key: Int, action: Int) =
        "I/DF_CARINPUT( 2140): [Capture]dispatchHWKeyEvent: inputKey=$key,inputAction=$action"

    @Test
    fun `next press and short release`() {
        assertEquals(DongfengWheelKey.Event(KeyEvent.KEYCODE_MEDIA_NEXT, true), DongfengWheelKey.parse(line(12, 0)))
        assertEquals(DongfengWheelKey.Event(KeyEvent.KEYCODE_MEDIA_NEXT, false), DongfengWheelKey.parse(line(12, 2)))
    }

    @Test
    fun `previous and play pause map to media keys`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, DongfengWheelKey.parse(line(11, 0))!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, DongfengWheelKey.parse(line(20, 0))!!.keyCode)
    }

    @Test
    fun `a long press release also ends the key`() {
        assertEquals(DongfengWheelKey.Event(KeyEvent.KEYCODE_MEDIA_NEXT, false), DongfengWheelKey.parse(line(12, 3)))
    }

    @Test
    fun `unknown keys and actions are ignored`() {
        assertNull(DongfengWheelKey.parse(line(99, 0)))
        assertNull(DongfengWheelKey.parse(line(12, 1)))
        assertNull(DongfengWheelKey.parse(line(12, 7)))
    }

    @Test
    fun `other log lines are ignored`() {
        assertNull(DongfengWheelKey.parse("I/DF_CARINPUT( 2140): [Capture]onHWKeyEvent: keyCode=12"))
        assertNull(DongfengWheelKey.parse("I/DF_CARINPUT( 2140): [Capture]handleKeyEvent, keyCode=12 action=0"))
        assertNull(DongfengWheelKey.parse("I/DF_Media( 3490): [InputFwkManager]:onKeyDown : 12 next"))
        assertNull(DongfengWheelKey.parse(""))
    }

    @Test
    fun `a number too large to be a key is ignored not thrown`() {
        assertNull(DongfengWheelKey.parse("dispatchHWKeyEvent: inputKey=99999999999,inputAction=0"))
    }
}
