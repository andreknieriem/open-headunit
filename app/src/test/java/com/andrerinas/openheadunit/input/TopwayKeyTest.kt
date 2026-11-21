package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopwayKeyTest {

    @Test
    fun `extractKeyCode accepts 0x0201 and 0x8201 key presses`() {
        assertEquals(0x05, TopwayKey.extractKeyCode(0x0201, 1, 0x05))
        assertEquals(0x5A, TopwayKey.extractKeyCode(0x8201, 1, 0x5A))
    }

    @Test
    fun `extractKeyCode rejects non-key messages and zero arg1`() {
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0102, 1, 0x05))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0201, 0, 0x05))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0201, 1, 0))
        assertEquals(TopwayKey.NO_KEY, TopwayKey.extractKeyCode(0x0201, 1, 256))
    }

    @Test
    fun `virtual key codes round-trip and format labels`() {
        val vNext = TopwayKey.toKeyCode(0x05)
        assertEquals(6005, vNext)
        assertEquals(0x05, TopwayKey.topwayCodeOf(vNext))
        assertEquals("Topway Next (0x05)", TopwayKey.label(vNext))
        assertEquals("Topway Voice (0x5A)", TopwayKey.label(TopwayKey.toKeyCode(0x5A)))
        assertEquals("Topway key (0x7F)", TopwayKey.label(TopwayKey.toKeyCode(0x7F)))
        assertNull(TopwayKey.label(5005))
    }

    @Test
    fun `unbound media phone and voice keys send their defaults while system keys stay virtual`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, TopwayKey.sendCode(0x05, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, TopwayKey.sendCode(0x06, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, TopwayKey.sendCode(0x14, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_CALL, TopwayKey.sendCode(0x18, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_ENDCALL, TopwayKey.sendCode(0x2E, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_SEARCH, TopwayKey.sendCode(0x5A, emptyMap()))
        // The firmware keeps volume and Mode unless the user binds them.
        assertEquals(TopwayKey.toKeyCode(0x03), TopwayKey.sendCode(0x03, emptyMap()))
        assertEquals(TopwayKey.toKeyCode(0x12), TopwayKey.sendCode(0x12, emptyMap()))
    }

    @Test
    fun `a bound key goes on as its virtual code, Do nothing included`() {
        val virtual = TopwayKey.toKeyCode(0x05)
        assertEquals(virtual, TopwayKey.sendCode(0x05, mapOf(virtual to InputAction.AaKey(KeyEvent.KEYCODE_HOME))))
        assertEquals(virtual, TopwayKey.sendCode(0x05, mapOf(virtual to InputAction.Ignore)))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, TopwayKey.sendCode(0x05, mapOf(virtual to InputAction.None)))
    }

    @Test
    fun `debounceWindowMs uses shorter window for rotary and volume keys than push buttons`() {
        assertEquals(TopwayKey.ROTARY_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x03))
        assertEquals(TopwayKey.ROTARY_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x04))
        assertEquals(TopwayKey.ROTARY_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x45))
        assertEquals(TopwayKey.BUTTON_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x10))
        assertEquals(TopwayKey.BUTTON_DEBOUNCE_MS, TopwayKey.debounceWindowMs(0x5A))
    }

    @Test
    fun `the same button inside its window is a repeat, another button is not`() {
        assertTrue(TopwayKey.isRepeat(0x05, 1_000L, 0x05, 1_300L))
        assertFalse(TopwayKey.isRepeat(0x05, 1_000L, 0x05, 1_400L))
        assertFalse(TopwayKey.isRepeat(0x06, 1_000L, 0x05, 1_100L))
        assertFalse(TopwayKey.isRepeat(0x45, 1_000L, 0x45, 1_100L))
    }

    /** Feeds MCU messages of one code through isRepeat and windowStartMs, as the receiver does. */
    private fun clicks(code: Int, vararg atMs: Long): Int {
        var lastCode = TopwayKey.NO_KEY
        var lastAt = 0L
        var clicks = 0
        for (now in atMs) {
            val repeat = TopwayKey.isRepeat(lastCode, lastAt, code, now)
            lastAt = TopwayKey.windowStartMs(lastCode, lastAt, code, now)
            lastCode = code
            if (!repeat) clicks++
        }
        return clicks
    }

    @Test
    fun `a button's repeat train is one click, however long it runs`() {
        assertEquals(1, clicks(0x05, 1_000L, 1_200L, 1_400L, 1_600L))
        assertEquals(2, clicks(0x05, 1_000L, 1_200L, 1_700L))
    }

    @Test
    fun `a fast rotary turn keeps a step for each window`() {
        assertEquals(3, clicks(0x45, 1_000L, 1_050L, 1_100L, 1_150L, 1_200L))
    }
}
