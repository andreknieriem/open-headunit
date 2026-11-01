package com.andrerinas.openheadunit.app

import android.os.Build

/** Which screen a screen-on request raises to light the panel. */
enum class ScreenOnRaise { NONE, HOME, PROJECTION }

/**
 * From API 33 only an activity can light the panel, and from API 26 only an activity can ask to
 * dismiss a keyguard, lit panel or not. A live session gets its own screen back: raising the home
 * screen would finish the projection in the same task.
 */
object ScreenOnRaisePolicy {
    fun decide(sdk: Int, interactive: Boolean?, keyguardLocked: Boolean?, sessionConnected: Boolean): ScreenOnRaise = when {
        sdk < Build.VERSION_CODES.O -> ScreenOnRaise.NONE
        keyguardLocked == true -> if (sessionConnected) ScreenOnRaise.PROJECTION else ScreenOnRaise.HOME
        sdk < Build.VERSION_CODES.TIRAMISU -> ScreenOnRaise.NONE
        interactive == true -> ScreenOnRaise.NONE
        sessionConnected -> ScreenOnRaise.PROJECTION
        else -> ScreenOnRaise.HOME
    }
}
