package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.protocol.messages.ScrollWheelEvent // Not directly in supported list, but used in AapTransport
import com.andrerinas.openheadunit.utils.AppLog

object KeyCode {

    const val KEY_NIGHT_MODE = 65539

    val supported = listOf(
        // Standard Android KeyEvents used in the convert method or common
        KeyEvent.KEYCODE_SOFT_LEFT,
        KeyEvent.KEYCODE_SOFT_RIGHT,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_MUSIC,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_STOP,

        // Alphabetic keys (A through Z) for text entry and search
        KeyEvent.KEYCODE_A,
        KeyEvent.KEYCODE_B,
        KeyEvent.KEYCODE_C,
        KeyEvent.KEYCODE_D,
        KeyEvent.KEYCODE_E,
        KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_G,
        KeyEvent.KEYCODE_H,
        KeyEvent.KEYCODE_I,
        KeyEvent.KEYCODE_J,
        KeyEvent.KEYCODE_K,
        KeyEvent.KEYCODE_L,
        KeyEvent.KEYCODE_M,
        KeyEvent.KEYCODE_N,
        KeyEvent.KEYCODE_O,
        KeyEvent.KEYCODE_P,
        KeyEvent.KEYCODE_Q,
        KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_S,
        KeyEvent.KEYCODE_T,
        KeyEvent.KEYCODE_U,
        KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_W,
        KeyEvent.KEYCODE_X,
        KeyEvent.KEYCODE_Y,
        KeyEvent.KEYCODE_Z,

        // Text editing, navigation and escape
        KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_ESCAPE,

        // Punctuation and symbols
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
        KeyEvent.KEYCODE_RIGHT_BRACKET,

        // Modifiers
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK,

        // Additional keys explicitly listed by number in BuildCarConfig.java
        // Mapped to named constants where they exist in KeyEvent
        KeyEvent.KEYCODE_ENDCALL, // 6
        KeyEvent.KEYCODE_0, // 7
        KeyEvent.KEYCODE_1, // 8
        KeyEvent.KEYCODE_2, // 9
        KeyEvent.KEYCODE_3, // 10
        KeyEvent.KEYCODE_4, // 11
        KeyEvent.KEYCODE_5, // 12
        KeyEvent.KEYCODE_6, // 13
        KeyEvent.KEYCODE_7, // 14
        KeyEvent.KEYCODE_8, // 15
        KeyEvent.KEYCODE_9, // 16
        KeyEvent.KEYCODE_STAR, // 17
        KeyEvent.KEYCODE_POUND, // 18

        // Custom/Rotary codes from BuildCarConfig.java (no direct KeyEvent.KEYCODE_X mapping)
        1, // Appears to be a custom keycode
        2, // Appears to be a custom keycode
        81, // Appears to be a custom keycode or old BOOKMARK (actual BOOKMARK is 137)
        224, // KEYCODE_WAKEUP → Voice Command
        264, 265, 267, // STEM_PRIMARY, STEM_1, STEM_3 (steering wheel)
        268, 269, 270, 271, // Rotary controller
        65536, 65537, 65538, // Rotary controller
        KEY_NIGHT_MODE // Custom night mode keycode
    ).distinct().sorted()

    val KeyEvent.isMediaSessionKey: Boolean
        get() = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    internal fun convert(keyCode: Int): Int {
        // If it's in our supported list or a standard media key, pass it through.
        // We no longer force ENTER -> DPAD_CENTER here to allow users to map it specifically.
        if (supported.contains(keyCode) ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            keyCode == KeyEvent.KEYCODE_MUTE ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return keyCode
        }

        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)
            return KeyEvent.KEYCODE_SEARCH

        // Return KEYCODE_UNKNOWN for anything else to avoid sending invalid codes to AA
        AppLog.w("KeyCode: Unknown or unsupported keycode $keyCode - returning KEYCODE_UNKNOWN")
        return KeyEvent.KEYCODE_UNKNOWN
    }
}
