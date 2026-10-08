package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCodeTest {

    @Test
    fun `supported list contains all alphabetic keycodes from A to Z`() {
        for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            assertTrue(
                "KeyCode.supported must contain alphabetic keycode $code",
                KeyCode.supported.contains(code)
            )
        }
    }

    @Test
    fun `supported list contains text editing and whitespace keycodes`() {
        val editingKeys = listOf(
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER
        )
        for (code in editingKeys) {
            assertTrue(
                "KeyCode.supported must contain editing key $code",
                KeyCode.supported.contains(code)
            )
        }
    }

    @Test
    fun `supported list contains common punctuation and symbol keycodes`() {
        val punctuationKeys = listOf(
            KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_BACKSLASH,
            KeyEvent.KEYCODE_SEMICOLON,
            KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_GRAVE,
            KeyEvent.KEYCODE_AT,
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_LEFT_BRACKET,
            KeyEvent.KEYCODE_RIGHT_BRACKET
        )
        for (code in punctuationKeys) {
            assertTrue(
                "KeyCode.supported must contain punctuation key $code",
                KeyCode.supported.contains(code)
            )
        }
    }

    @Test
    fun `supported list contains modifier keycodes`() {
        val modifierKeys = listOf(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK
        )
        for (code in modifierKeys) {
            assertTrue(
                "KeyCode.supported must contain modifier key $code",
                KeyCode.supported.contains(code)
            )
        }
    }

    @Test
    fun `convert passes through alphabetic keycodes without returning KEYCODE_UNKNOWN`() {
        for (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            assertEquals(
                "convert must pass through $code",
                code,
                KeyCode.convert(code)
            )
        }
    }

    @Test
    fun `convert passes through backspace and punctuation keycodes`() {
        val keys = listOf(
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_SLASH
        )
        for (code in keys) {
            assertEquals(
                "convert must pass through $code",
                code,
                KeyCode.convert(code)
            )
        }
    }

    @Test
    fun `convert returns KEYCODE_UNKNOWN for unsupported keycode`() {
        val unsupportedCode = 999
        assertEquals(
            KeyEvent.KEYCODE_UNKNOWN,
            KeyCode.convert(unsupportedCode)
        )
    }

    @Test
    fun `supported list contains KEY_NIGHT_MODE and convert preserves KEYCODE_N`() {
        assertTrue(KeyCode.supported.contains(KeyCode.KEY_NIGHT_MODE))
        assertEquals(KeyCode.KEY_NIGHT_MODE, KeyCode.convert(KeyCode.KEY_NIGHT_MODE))
        assertEquals(KeyEvent.KEYCODE_N, KeyCode.convert(KeyEvent.KEYCODE_N))
    }
}
