package com.andrerinas.openheadunit.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadUnitScreenConfigSessionShapeTest {

    @After
    fun endSession() = HeadUnitScreenConfig.unlockResolution()

    @Test
    fun `the session is announced in the shape of its canvas`() {
        HeadUnitScreenConfig.recordSessionShape(1080, 2094)
        assertEquals(ScreenOrientationPolicy.Normalisation.PORTRAIT, HeadUnitScreenConfig.announcedShape())
    }

    // Measured 2026-10-23: returning from the app's home recreates the projection under a running
    // session, and the old window's onDestroy cleared the announced canvas after the new one started,
    // so a turn in the new window could no longer be redrawn.
    @Test
    fun `the announced shape outlives the projection window`() {
        HeadUnitScreenConfig.recordSessionShape(1080, 2094)
        HeadUnitScreenConfig.clearAnnouncedMargins()
        assertEquals(ScreenOrientationPolicy.Normalisation.PORTRAIT, HeadUnitScreenConfig.announcedShape())
    }

    // A turn unlocks the resolution mid-session and re-chooses it; the stream keeps its shape.
    @Test
    fun `a mid-session unlock keeps the announced shape`() {
        HeadUnitScreenConfig.recordSessionShape(2094, 1080)
        HeadUnitScreenConfig.unlockResolution(keepFallback = true)
        assertEquals(ScreenOrientationPolicy.Normalisation.LANDSCAPE, HeadUnitScreenConfig.announcedShape())
    }

    @Test
    fun `the session's end forgets it`() {
        HeadUnitScreenConfig.recordSessionShape(2094, 1080)
        HeadUnitScreenConfig.unlockResolution()
        assertNull(HeadUnitScreenConfig.announcedShape())
    }
}
