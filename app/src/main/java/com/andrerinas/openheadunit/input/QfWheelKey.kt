package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import java.util.Locale

/**
 * Vendor wheel and panel keys of QF / K706 units, heard on the `util_service` key listener.
 * The listener also hears normal Android keys, which reach us as key events too, so only the
 * vendor range is taken. These keys have no default: the phone gets one only when it is bound.
 */
object QfWheelKey {

    const val FIRST_VENDOR_CODE = 290
    const val LAST_VENDOR_CODE = 368

    /** Clear of NWD 1000+, Eryanet 2001+, BZ 3001+, BYD 4000+, Dynavin 5000+ and Topway 6000+. */
    const val VIRTUAL_BASE = 7000

    /** A key with no event that comes again this soon is taken as the same press. */
    const val EVENTLESS_REPEAT_MS = 250L

    private const val FIRST_CUSTOM_CODE = 354

    private val NAMES = mapOf(
        290 to "Power",
        291 to "Mute",
        292 to "Mode",
        293 to "VolumeUp",
        294 to "VolumeDown",
        298 to "Navi",
        299 to "Prev",
        300 to "Next",
        301 to "Phone",
        302 to "Hangup",
        303 to "Radio",
        304 to "Voice",
        307 to "PlayPause",
        315 to "Backlight",
        316 to "Settings",
        321 to "Apps",
        325 to "Knob",
    )

    enum class Press { DOWN, UP, CLICK, NONE }

    fun isVendorKey(qfCode: Int): Boolean = qfCode in FIRST_VENDOR_CODE..LAST_VENDOR_CODE

    fun toKeyCode(qfCode: Int): Int = VIRTUAL_BASE + qfCode

    fun qfCodeOf(keyCode: Int): Int? = (keyCode - VIRTUAL_BASE).takeIf { isVendorKey(it) }

    fun nameOf(qfCode: Int): String? {
        NAMES[qfCode]?.let { return it }
        if (qfCode in FIRST_CUSTOM_CODE..LAST_VENDOR_CODE) return "KEY${qfCode - FIRST_CUSTOM_CODE + 1}"
        return null
    }

    /**
     * What one listener call means. [action] is the event's action, or null when
     * the call carried no event; such a call is a whole press unless it repeats the last one.
     */
    fun pressOf(
        qfCode: Int, action: Int?, repeatCount: Int,
        lastEventlessCode: Int, lastEventlessAtMs: Long, nowMs: Long,
    ): Press {
        if (!isVendorKey(qfCode)) return Press.NONE
        return when (action) {
            null -> if (qfCode == lastEventlessCode && nowMs - lastEventlessAtMs < EVENTLESS_REPEAT_MS) Press.NONE else Press.CLICK
            KeyEvent.ACTION_DOWN -> if (repeatCount == 0) Press.DOWN else Press.NONE
            KeyEvent.ACTION_UP -> Press.UP
            else -> Press.NONE
        }
    }

    /** Eventless calls of one key within [EVENTLESS_REPEAT_MS] of the last counted one are that press. */
    class EventlessGate {
        private var lastCode = 0
        private var lastAtMs = 0L

        fun next(qfCode: Int, nowMs: Long): Press {
            val press = QfWheelKey.pressOf(qfCode, null, 0, lastCode, lastAtMs, nowMs)
            // Only a counted press moves the window, so a fast run cannot hold it open.
            if (press == Press.CLICK) {
                lastCode = qfCode
                lastAtMs = nowMs
            }
            return press
        }
    }

    fun label(keyCode: Int): String? {
        val qfCode = qfCodeOf(keyCode) ?: return null
        return String.format(Locale.US, "QF %s (%d)", nameOf(qfCode) ?: "key", qfCode)
    }
}
