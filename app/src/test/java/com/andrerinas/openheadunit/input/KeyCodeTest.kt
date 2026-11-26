package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the local night key is never announced to the phone`() {
        assertFalse(KeyCode.supported.contains(KeyCode.KEY_NIGHT_MODE))
        assertEquals(KeyEvent.KEYCODE_N, KeyCode.convert(KeyEvent.KEYCODE_N))
    }

    @Test
    fun `the phone key TEL is declared, RADIO is not`() {
        assertEquals(65540, KeyCode.KEY_TEL)
        assertTrue(KeyCode.supported.contains(KeyCode.KEY_TEL))
        assertFalse(KeyCode.supported.contains(65539))
    }

    @Test
    fun `a legacy night mapping under KEYCODE_N moves to KEY_NIGHT_MODE`() {
        val legacy = mapOf(KeyEvent.KEYCODE_N to 300, KeyEvent.KEYCODE_HOME to 301)
        assertEquals(
            mapOf(KeyCode.KEY_NIGHT_MODE to 300, KeyEvent.KEYCODE_HOME to 301),
            KeyCode.migrateLegacyNightKey(legacy)
        )
    }

    @Test
    fun `a keymap without a KEYCODE_N entry is unchanged`() {
        val keymap = mapOf(KeyEvent.KEYCODE_HOME to 301)
        assertEquals(keymap, KeyCode.migrateLegacyNightKey(keymap))
    }

    @Test
    fun `an existing KEY_NIGHT_MODE mapping wins and the stale entry is dropped`() {
        val keymap = mapOf(KeyEvent.KEYCODE_N to 300, KeyCode.KEY_NIGHT_MODE to 302)
        assertEquals(mapOf(KeyCode.KEY_NIGHT_MODE to 302), KeyCode.migrateLegacyNightKey(keymap))
    }

    @Test
    fun `rewind, fast forward and mute are declared`() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        ).forEach { code ->
            assertTrue("KeyCode.supported must declare $code", KeyCode.supported.contains(code))
        }
    }

    @Test
    fun `every key that convert sends as itself is declared`() {
        (0..300).forEach { code ->
            if (code != KeyEvent.KEYCODE_UNKNOWN && KeyCode.convert(code) == code) {
                assertTrue("convert sends $code, so supported must declare it", KeyCode.supported.contains(code))
            }
        }
    }
}
