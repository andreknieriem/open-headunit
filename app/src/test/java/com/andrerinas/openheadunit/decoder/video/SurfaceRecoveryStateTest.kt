package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.*
import org.junit.Test

class SurfaceRecoveryStateTest {
    @Test fun `repeated retained surface returns each rearm once but resize does not`() {
        val state = SurfaceRecoveryState()
        var generation = 0L
        repeat(3) {
            state.cycleSpent = true
            state.loggedKeyframelessPicture = true
            assertFalse(state.onSurfaceChanged(false, generation))
            assertTrue(state.cycleSpent)
            generation++
            assertTrue(state.onSurfaceChanged(false, generation))
            assertFalse(state.cycleSpent)
            assertFalse(state.loggedKeyframelessPicture)
            state.cycleSpent = true
            assertFalse(state.onSurfaceChanged(false, generation))
            assertTrue(state.cycleSpent)
        }
    }

    @Test fun `new session clears spent recovery without losing pending stopped surface`() {
        val state = SurfaceRecoveryState()
        var generation = 0L
        state.cycleSpent = true
        state.loggedKeyframelessPicture = true
        generation++
        state.resetSession()
        assertFalse(state.cycleSpent)
        assertFalse(state.loggedKeyframelessPicture)
        assertTrue(state.onSurfaceChanged(false, generation))
        assertFalse(state.onSurfaceChanged(false, generation))
        state.cycleSpent = true
        assertTrue(state.onSurfaceChanged(true, generation))
        assertFalse(state.cycleSpent)
    }
}
