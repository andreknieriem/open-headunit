package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import java.util.Locale

/**
 * Wheel and panel keys of Topway / XYAUTO units, read as `TWUtil` messages from `/dev/tw`.
 * `what` is 0x0201 or 0x8201, `arg2` the button and `arg1` the press state. Several buttons
 * (Mode, Phone, Navi, Voice) never become key events.
 */
object TopwayKey {

    const val WHAT_KEY_EVENT = 0x0201
    const val WHAT_SYNTH_KEY_EVENT = 0x8201
    const val NO_KEY = -1

    /** Clear of NWD 1000+, Eryanet 2001+, BZ 3001+, BYD 4000+ and Dynavin 5000+. */
    const val VIRTUAL_BASE = 6000
    private val VIRTUAL_RANGE = VIRTUAL_BASE until (VIRTUAL_BASE + 256)

    private val NAMES = mapOf(
        0x01 to "Power",
        0x03 to "VolumeUp",
        0x04 to "VolumeDown",
        0x05 to "Next",
        0x06 to "Prev",
        0x07 to "Mute",
        0x10 to "Home",
        0x11 to "Menu",
        0x12 to "Mode",
        0x13 to "SeekNext",
        0x14 to "PlayPause",
        0x15 to "SeekPrev",
        0x16 to "Play",
        0x17 to "Pause",
        0x18 to "Phone",
        0x1A to "Back",
        0x1F to "Brightness",
        0x20 to "Navi",
        0x21 to "Radio",
        0x22 to "DVD",
        0x23 to "Eject",
        0x24 to "AUX",
        0x25 to "DTV",
        0x29 to "Music",
        0x2B to "Photo",
        0x2C to "Video",
        0x2D to "AVIn",
        0x2E to "Hangup",
        0x2F to "Answer",
        0x45 to "RollRight",
        0x46 to "RollLeft",
        0x47 to "RollEnter",
        0x52 to "EQ",
        0x57 to "DVR",
        0x5A to "Voice",
    )

    // Media, phone and voice only. The firmware already acts on volume, mute, home, back and mode.
    private val DEFAULTS = mapOf(
        0x05 to KeyEvent.KEYCODE_MEDIA_NEXT,
        0x06 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        0x13 to KeyEvent.KEYCODE_MEDIA_NEXT,
        0x14 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        0x15 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        0x16 to KeyEvent.KEYCODE_MEDIA_PLAY,
        0x17 to KeyEvent.KEYCODE_MEDIA_PAUSE,
        0x18 to KeyEvent.KEYCODE_CALL,
        0x2E to KeyEvent.KEYCODE_ENDCALL,
        0x2F to KeyEvent.KEYCODE_CALL,
        0x5A to KeyEvent.KEYCODE_SEARCH,
    )

    /** Rotary and volume steps come fast; the MCU repeats a push button 33 to 364 ms apart. */
    const val ROTARY_DEBOUNCE_MS = 80L
    const val BUTTON_DEBOUNCE_MS = 400L
    private val ROTARY_KEYS = setOf(0x03, 0x04, 0x45, 0x46)

    /** The button in a `TWUtil` message, or [NO_KEY] when the message is not a press. */
    fun extractKeyCode(what: Int, arg1: Int, arg2: Int): Int {
        if (what != WHAT_KEY_EVENT && what != WHAT_SYNTH_KEY_EVENT) return NO_KEY
        if (arg2 !in 1..255 || arg1 == 0) return NO_KEY
        return arg2
    }

    fun toKeyCode(topwayCode: Int): Int = VIRTUAL_BASE + topwayCode

    fun topwayCodeOf(keyCode: Int): Int? = if (keyCode in VIRTUAL_RANGE) keyCode - VIRTUAL_BASE else null

    fun nameOf(topwayCode: Int): String? = NAMES[topwayCode]

    /** A bound virtual key goes on as itself, so its binding matches. Else the default, else the virtual key. */
    fun sendCode(topwayCode: Int, bindings: Map<Int, InputAction>): Int {
        val virtual = toKeyCode(topwayCode)
        if (bindings[virtual].let { it != null && it != InputAction.None }) return virtual
        return DEFAULTS[topwayCode] ?: virtual
    }

    fun debounceWindowMs(topwayCode: Int): Long =
        if (topwayCode in ROTARY_KEYS) ROTARY_DEBOUNCE_MS else BUTTON_DEBOUNCE_MS

    /** The same button again inside its window is the MCU repeating one press. */
    fun isRepeat(lastCode: Int, lastAtMs: Long, topwayCode: Int, nowMs: Long): Boolean =
        topwayCode == lastCode && nowMs - lastAtMs < debounceWindowMs(topwayCode)

    /** Where the window starts after this message. A button's repeat train moves it on; a rotary step never does. */
    fun windowStartMs(lastCode: Int, lastAtMs: Long, topwayCode: Int, nowMs: Long): Long =
        if (topwayCode in ROTARY_KEYS && isRepeat(lastCode, lastAtMs, topwayCode, nowMs)) lastAtMs else nowMs

    fun label(keyCode: Int): String? {
        val topwayCode = topwayCodeOf(keyCode) ?: return null
        return String.format(Locale.US, "Topway %s (0x%02X)", nameOf(topwayCode) ?: "key", topwayCode)
    }
}
