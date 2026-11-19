package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.GestureOutcomePolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureOutcomePolicyTest {
    @Test fun everyActionMapsToItsOutcome() {
        assertEquals(Outcome.ShowExitDialog, GestureOutcomePolicy.decide(LocalAction.EXIT_DIALOG))
        assertEquals(Outcome.ToggleFullscreen, GestureOutcomePolicy.decide(LocalAction.TOGGLE_FULLSCREEN))
        assertEquals(Outcome.ShowOverlay, GestureOutcomePolicy.decide(LocalAction.NIGHT_OVERLAY))
        assertEquals(Outcome.ToggleDimmer, GestureOutcomePolicy.decide(LocalAction.DIM_SCREEN))
    }
}
