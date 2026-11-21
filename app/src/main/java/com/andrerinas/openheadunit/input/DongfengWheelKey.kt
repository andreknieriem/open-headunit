package com.andrerinas.openheadunit.input

import android.view.KeyEvent

/**
 * Dongfeng wheel keys, read from the line `com.dftc.carinput` logs for each key it dispatches:
 * `dispatchHWKeyEvent: inputKey=12,inputAction=0`. Action 0 is a press; 2 (short) or 3 (long) a release.
 * The service hands keys only to system apps, so its log is the one place an app can see them.
 */
object DongfengWheelKey {

    const val LOG_TAG = "DF_CARINPUT"
    const val INPUT_PACKAGE = "com.dftc.carinput"

    private const val DOWN = 0
    private const val SHORT_UP = 2
    private const val LONG_UP = 3

    private val LINE = Regex("""dispatchHWKeyEvent: inputKey=(\d+),inputAction=(\d+)""")

    private val KEYS = mapOf(
        11 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        12 to KeyEvent.KEYCODE_MEDIA_NEXT,
        20 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    )

    data class Event(val keyCode: Int, val isDown: Boolean)

    /** The key event a log line describes, or null for any other line or an unknown key. */
    fun parse(line: String): Event? {
        val match = LINE.find(line) ?: return null
        val key = match.groupValues[1].toIntOrNull() ?: return null
        val action = match.groupValues[2].toIntOrNull() ?: return null
        val keyCode = KEYS[key] ?: return null
        return when (action) {
            DOWN -> Event(keyCode, true)
            SHORT_UP, LONG_UP -> Event(keyCode, false)
            else -> null
        }
    }
}
