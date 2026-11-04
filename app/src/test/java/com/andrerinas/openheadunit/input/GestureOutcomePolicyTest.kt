package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.GestureOutcomePolicy.Outcome
import com.andrerinas.openheadunit.utils.Settings.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureOutcomePolicyTest {
    @Test fun everyActionMapsToItsOutcome() {
        assertEquals(Outcome.None, GestureOutcomePolicy.decide(GestureAction.DISABLED))
        assertEquals(Outcome.ShowExitDialog, GestureOutcomePolicy.decide(GestureAction.EXIT_DIALOG))
        assertEquals(Outcome.ToggleFullscreen, GestureOutcomePolicy.decide(GestureAction.TOGGLE_FULLSCREEN))
        assertEquals(Outcome.ShowOverlay, GestureOutcomePolicy.decide(GestureAction.NIGHT_OVERLAY))
        assertEquals(Outcome.ToggleDimmer, GestureOutcomePolicy.decide(GestureAction.DIM_SCREEN))
    }
}
