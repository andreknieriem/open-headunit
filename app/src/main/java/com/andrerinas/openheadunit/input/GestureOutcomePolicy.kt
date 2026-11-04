package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.utils.Settings

/** Maps a bound gesture action to what the projection screen does. */
object GestureOutcomePolicy {
    enum class Outcome { ShowOverlay, ToggleDimmer, ToggleFullscreen, ShowExitDialog, None }

    fun decide(action: Settings.GestureAction): Outcome = when (action) {
        Settings.GestureAction.DISABLED -> Outcome.None
        Settings.GestureAction.EXIT_DIALOG -> Outcome.ShowExitDialog
        Settings.GestureAction.TOGGLE_FULLSCREEN -> Outcome.ToggleFullscreen
        Settings.GestureAction.NIGHT_OVERLAY -> Outcome.ShowOverlay
        Settings.GestureAction.DIM_SCREEN -> Outcome.ToggleDimmer
    }
}
