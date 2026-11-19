package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.LockedViewPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class LockedViewPolicyTest {

    @Test
    fun `locked with the setting on, the overlay goes up`() {
        assertEquals(Step.SHOW, LockedViewPolicy.decide(engaged = true, enabled = true, overlayVisible = false, ownsOverlay = false))
    }

    @Test
    fun `an overlay already up stays up when the unit locks`() {
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = true, enabled = true, overlayVisible = true, ownsOverlay = true))
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = true, enabled = true, overlayVisible = true, ownsOverlay = false))
    }

    @Test
    fun `unlocking takes away the overlay the locked unit put up`() {
        assertEquals(Step.HIDE, LockedViewPolicy.decide(engaged = false, enabled = true, overlayVisible = true, ownsOverlay = true))
    }

    @Test
    fun `unlocking keeps an overlay opened by the gesture action`() {
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = false, enabled = true, overlayVisible = true, ownsOverlay = false))
    }

    @Test
    fun `setting off, a locked unit shows the picture`() {
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = true, enabled = false, overlayVisible = false, ownsOverlay = false))
        assertEquals(Step.HIDE, LockedViewPolicy.decide(engaged = true, enabled = false, overlayVisible = true, ownsOverlay = true))
    }

    @Test
    fun `setting off leaves an overlay opened by the gesture action alone`() {
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = true, enabled = false, overlayVisible = true, ownsOverlay = false))
    }

    @Test
    fun `unlocked with nothing up, nothing happens`() {
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = false, enabled = true, overlayVisible = false, ownsOverlay = false))
        assertEquals(Step.KEEP, LockedViewPolicy.decide(engaged = false, enabled = false, overlayVisible = false, ownsOverlay = false))
    }
}
