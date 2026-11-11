package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenSettingsHashTest {

    private fun hash(
        resolutionId: Int = 2,
        dpiPixelDensity: Int = 240,
        pixelAspectRatioE4: Int = 10000,
        insetLeft: Int = 0,
        insetTop: Int = 0,
        insetRight: Int = 0,
        insetBottom: Int = 0,
        viewMode: Int = 0,
        screenOrientation: Int = 0,
        fullscreenMode: Int = 0,
        videoFitMode: Int = 0,
        forcedScale: Boolean = false,
        normalisation: ScreenOrientationPolicy.Normalisation = ScreenOrientationPolicy.Normalisation.LANDSCAPE,
        dpiAutoLayout: String = "",
    ) = ScreenSettingsHash.of(
        resolutionId, dpiPixelDensity, pixelAspectRatioE4,
        insetLeft, insetTop, insetRight, insetBottom,
        viewMode, screenOrientation, fullscreenMode, videoFitMode, forcedScale, normalisation,
        dpiAutoLayout,
    )

    @Test
    fun `the same settings hash the same way`() {
        assertEquals(hash(), hash())
    }

    // The question a hardware round could not settle: applying a screen-mode change on the rig needs
    // a force-stop, which restarts the session by definition. The mode has to invalidate a reading.
    @Test
    fun `a screen mode change drops every measurement taken in the old one`() {
        assertNotEquals(hash(fullscreenMode = 0), hash(fullscreenMode = 1))
        assertNotEquals(hash(fullscreenMode = 2), hash(fullscreenMode = 3))
    }

    @Test
    fun `every setting the canvas depends on moves the hash`() {
        val base = hash()
        assertNotEquals(base, hash(resolutionId = 3))
        assertNotEquals(base, hash(dpiPixelDensity = 213))
        assertNotEquals(base, hash(pixelAspectRatioE4 = 11250))
        assertNotEquals(base, hash(insetLeft = 1))
        assertNotEquals(base, hash(insetTop = 1))
        assertNotEquals(base, hash(insetRight = 1))
        assertNotEquals(base, hash(insetBottom = 1))
        assertNotEquals(base, hash(viewMode = 1))
        assertNotEquals(base, hash(screenOrientation = 1))
        assertNotEquals(base, hash(videoFitMode = 1))
        assertNotEquals(base, hash(forcedScale = true))
    }

    // Under AUTO and SYSTEM a rotation does not move the orientation setting, so this term is what
    // invalidates a landscape measurement after the panel turns.
    @Test
    fun `a rotation still invalidates a measurement`() {
        assertNotEquals(
            hash(normalisation = ScreenOrientationPolicy.Normalisation.LANDSCAPE),
            hash(normalisation = ScreenOrientationPolicy.Normalisation.PORTRAIT)
        )
    }

    @Test
    fun `two settings exchanged are not the same settings`() {
        assertNotEquals(hash(insetLeft = 5, insetTop = 9), hash(insetLeft = 9, insetTop = 5))
    }

    // The number the default inputs gave before dpiAutoLayout existed: an upgrade keeps its cache.
    @Test
    fun `an empty layout leaves the hash where it was`() {
        assertEquals(953289905, hash())
    }

    @Test
    fun `a picked layout invalidates a reading`() {
        val base = hash()
        assertNotEquals(base, hash(dpiAutoLayout = "ONE_APP"))
        assertNotEquals(hash(dpiAutoLayout = "ONE_APP"), hash(dpiAutoLayout = "COMPACT"))
    }
}
