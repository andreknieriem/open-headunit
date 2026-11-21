package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadroverKeyTest {

    @Test
    fun `short presses are clicks of the media keys`() {
        assertEquals(RoadroverKey.Event(KeyEvent.KEYCODE_MEDIA_NEXT, RoadroverKey.Edge.CLICK), RoadroverKey.eventFor("next"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, RoadroverKey.eventFor("next_saved")!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, RoadroverKey.eventFor("search_next")!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, RoadroverKey.eventFor("prev")!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, RoadroverKey.eventFor("search_pre")!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RoadroverKey.eventFor("play_pause")!!.keyCode)
    }

    @Test
    fun `a long press holds seek until its end word`() {
        assertEquals(RoadroverKey.Event(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RoadroverKey.Edge.DOWN), RoadroverKey.eventFor("ff_start"))
        assertEquals(RoadroverKey.Event(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RoadroverKey.Edge.UP), RoadroverKey.eventFor("ff_end"))
        assertEquals(RoadroverKey.Event(KeyEvent.KEYCODE_MEDIA_REWIND, RoadroverKey.Edge.DOWN), RoadroverKey.eventFor("fr_start"))
        assertEquals(RoadroverKey.Event(KeyEvent.KEYCODE_MEDIA_REWIND, RoadroverKey.Edge.UP), RoadroverKey.eventFor("fr_end"))
    }

    @Test
    fun `an unknown word is no key`() {
        assertNull(RoadroverKey.eventFor("mode"))
        assertNull(RoadroverKey.eventFor(""))
    }
}
