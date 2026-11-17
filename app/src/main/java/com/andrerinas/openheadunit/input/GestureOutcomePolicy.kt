package com.andrerinas.openheadunit.input

/** Maps a local action, from a gesture or a key binding, to what the projection screen does. */
object GestureOutcomePolicy {
    enum class Outcome { ShowOverlay, ToggleDimmer, ToggleFullscreen, ShowExitDialog }

    fun decide(action: LocalAction): Outcome = when (action) {
        LocalAction.EXIT_DIALOG -> Outcome.ShowExitDialog
        LocalAction.TOGGLE_FULLSCREEN -> Outcome.ToggleFullscreen
        LocalAction.NIGHT_OVERLAY -> Outcome.ShowOverlay
        LocalAction.DIM_SCREEN -> Outcome.ToggleDimmer
    }
}
