package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import java.util.Locale

/**
 * Keys of Dynavin units (PX30, D9). The vendor service sends a panel key as [ACTION] with an int
 * [EXTRA_KEYCODE], on release only. The knob joystick arrives as MCU frames instead ([mcuKey]).
 */
object DynavinPanelKey {

    const val ACTION = "dynavin.intent.action.KeyEvent"
    const val EXTRA_KEYCODE = "keycode"

    /** Clear of NWD 1000+, Eryanet 2001+, BZ 3001+ and BYD 4000+. */
    const val VIRTUAL_BASE = 5000
    private val VIRTUAL_RANGE = VIRTUAL_BASE until (VIRTUAL_BASE + 1000)

    // Names from an Audi MMI 3G panel. The joystick reports the same codes as the four corner keys.
    private val NAMES = mapOf(
        305 to "NAV",
        316 to "RADIO",
        323 to "MEDIA",
        326 to "BACK",
        329 to "MENU",
        340 to "Knob press",
        342 to "INFO",
        376 to "Top-right / joystick up",
        377 to "Bottom-right / joystick down",
        378 to "Top-left / joystick left",
        379 to "Bottom-left / joystick right",
        380 to "Knob right",
        381 to "Knob left",
    )

    /** Sent while the virtual key has no binding; SOFT_LEFT and SOFT_RIGHT scroll the rotary list. */
    private val DEFAULTS = mapOf(
        340 to KeyEvent.KEYCODE_DPAD_CENTER,
        376 to KeyEvent.KEYCODE_DPAD_UP,
        377 to KeyEvent.KEYCODE_DPAD_DOWN,
        378 to KeyEvent.KEYCODE_DPAD_LEFT,
        379 to KeyEvent.KEYCODE_DPAD_RIGHT,
        380 to KeyEvent.KEYCODE_SOFT_RIGHT,
        381 to KeyEvent.KEYCODE_SOFT_LEFT,
    )

    /** Raw MCU key in an `E_ProtOS_Key` frame to its Dynavin code. These come on press only. */
    private val MCU_KEYS = mapOf(240 to 376, 241 to 377, 242 to 378, 243 to 379)

    fun toKeyCode(dynavinCode: Int): Int = VIRTUAL_BASE + dynavinCode

    /** The Dynavin code in an MCU key frame, or null for a frame that is not a joystick key. */
    fun mcuKey(frame: ByteArray?): Int? {
        if (frame == null || frame.size <= 4) return null
        return MCU_KEYS[frame[4].toInt() and 0xFF]
    }

    /**
     * A knob detent is its own press, so a fast turn keeps every step. Other keys go on with no
     * identity, so a firmware copy of one merges in the debounce.
     */
    fun isKnobTurn(dynavinCode: Int): Boolean = dynavinCode == 380 || dynavinCode == 381

    /** The broadcast copy of a key the MCU frames already delivered. */
    fun isMcuKey(dynavinCode: Int): Boolean = dynavinCode in MCU_KEYS.values

    /** A bound virtual key goes on as itself, so its binding matches. Else the default, else the virtual key. */
    fun sendCode(dynavinCode: Int, bindings: Map<Int, InputAction>): Int {
        val virtual = toKeyCode(dynavinCode)
        if (bindings[virtual].let { it != null && it != InputAction.None }) return virtual
        return DEFAULTS[dynavinCode] ?: virtual
    }

    fun label(keyCode: Int): String? {
        if (keyCode !in VIRTUAL_RANGE) return null
        val code = keyCode - VIRTUAL_BASE
        return String.format(Locale.US, "Dynavin %s (%d)", NAMES[code] ?: "key", code)
    }
}
