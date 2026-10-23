package com.andrerinas.openheadunit.utils

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOrientationPolicyTest {

    private fun normalisation(
        setting: Settings.ScreenOrientation,
        landscape: Boolean = false,
        portrait: Boolean = false,
    ) = ScreenOrientationPolicy.normalisation(setting, landscape, portrait)

    @Test
    fun `an explicit orientation ignores what the configuration says`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            normalisation(Settings.ScreenOrientation.LANDSCAPE, portrait = true)
        )
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(Settings.ScreenOrientation.PORTRAIT, landscape = true)
        )
    }

    @Test
    fun `a reversed orientation normalises like its forward twin`() {
        assertEquals(
            normalisation(Settings.ScreenOrientation.LANDSCAPE),
            normalisation(Settings.ScreenOrientation.LANDSCAPE_REVERSE)
        )
        assertEquals(
            normalisation(Settings.ScreenOrientation.PORTRAIT),
            normalisation(Settings.ScreenOrientation.PORTRAIT_REVERSE)
        )
    }

    // The rig runs AUTO/SYSTEM, and the surface path used to omit this fallback entirely: it took
    // the four explicit settings only, so on the rig every surface reading was left as it came.
    @Test
    fun `auto and system take the orientation the configuration is in`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            normalisation(Settings.ScreenOrientation.AUTO, landscape = true)
        )
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(Settings.ScreenOrientation.SYSTEM, portrait = true)
        )
    }

    @Test
    fun `a configuration in neither orientation leaves the reading alone`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.AS_READ,
            normalisation(Settings.ScreenOrientation.AUTO)
        )
        val read = ScreenOrientationPolicy.normalise(1080, 2400, ScreenOrientationPolicy.Normalisation.AS_READ)
        assertEquals(1080, read.width)
        assertEquals(2400, read.height)
    }

    // Measured: a 2400x1080 phone reported its window content as the portrait 1080x2400 for 61 ms
    // and the head unit announced a portrait resolution from it.
    @Test
    fun `a portrait window reading becomes the landscape canvas`() {
        val turned = ScreenOrientationPolicy.normalise(1080, 2400, ScreenOrientationPolicy.Normalisation.LANDSCAPE)
        assertEquals(2400, turned.width)
        assertEquals(1080, turned.height)
    }

    @Test
    fun `a reading already the right way up is left as it is`() {
        val turned = ScreenOrientationPolicy.normalise(1440, 720, ScreenOrientationPolicy.Normalisation.LANDSCAPE)
        assertEquals(1440, turned.width)
        assertEquals(720, turned.height)
    }

    private fun normalisation(
        setting: Settings.ScreenOrientation,
        landscape: Boolean = false,
        portrait: Boolean = false,
        ourWindow: Boolean,
        remembered: ScreenOrientationPolicy.Normalisation?,
    ) = ScreenOrientationPolicy.normalisation(setting, landscape, portrait, ourWindow, remembered)

    // Measured on a Note8 locked to landscape: a reconnect that landed behind the keyguard read the
    // keyguard's portrait configuration, announced 1080x1920 and locked it, and the projection then
    // came up in landscape around a portrait stream.
    @Test
    fun `a configuration that is not one of our windows gives way to the shape last projected`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            normalisation(
                Settings.ScreenOrientation.SYSTEM, portrait = true,
                ourWindow = false, remembered = ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            )
        )
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(
                Settings.ScreenOrientation.AUTO, landscape = true,
                ourWindow = false, remembered = ScreenOrientationPolicy.Normalisation.PORTRAIT,
            )
        )
    }

    @Test
    fun `one of our windows describes the projection, so its configuration still decides`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(
                Settings.ScreenOrientation.SYSTEM, portrait = true,
                ourWindow = true, remembered = ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            )
        )
    }

    @Test
    fun `with nothing projected yet the configuration is all there is`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(Settings.ScreenOrientation.SYSTEM, portrait = true, ourWindow = false, remembered = null)
        )
        assertEquals(
            ScreenOrientationPolicy.Normalisation.AS_READ,
            normalisation(Settings.ScreenOrientation.AUTO, ourWindow = false, remembered = null)
        )
    }

    @Test
    fun `an explicit orientation ignores the remembered shape too`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(
                Settings.ScreenOrientation.PORTRAIT_REVERSE, landscape = true,
                ourWindow = false, remembered = ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            )
        )
    }

    @Test
    fun `a lit, unlocked, started window of ours describes the projection`() {
        assertTrue(ScreenOrientationPolicy.describesProjection(
            startedActivity = true, pictureInPicture = false, screenInteractive = true, keyguardLocked = false,
        ))
    }

    // Measured: the projection activity was resumed for 140 ms behind the keyguard, and service
    // discovery ran inside that window reading the keyguard's portrait.
    @Test
    fun `our window behind the keyguard does not describe the projection`() {
        assertFalse(ScreenOrientationPolicy.describesProjection(
            startedActivity = true, pictureInPicture = false, screenInteractive = true, keyguardLocked = true,
        ))
    }

    @Test
    fun `a dark screen, another app in front or picture-in-picture do not describe it either`() {
        assertFalse(ScreenOrientationPolicy.describesProjection(
            startedActivity = true, pictureInPicture = false, screenInteractive = false, keyguardLocked = false,
        ))
        assertFalse(ScreenOrientationPolicy.describesProjection(
            startedActivity = false, pictureInPicture = false, screenInteractive = true, keyguardLocked = false,
        ))
        assertFalse(ScreenOrientationPolicy.describesProjection(
            startedActivity = true, pictureInPicture = true, screenInteractive = true, keyguardLocked = false,
        ))
    }

    // A unit that cannot read its power or keyguard state keeps the behaviour it had.
    @Test
    fun `an unreadable screen or keyguard state is not held against the reading`() {
        assertTrue(ScreenOrientationPolicy.describesProjection(
            startedActivity = true, pictureInPicture = false, screenInteractive = null, keyguardLocked = null,
        ))
    }

    @Test
    fun `a stored canvas remembers the shape it was drawn in`() {
        assertEquals(ScreenOrientationPolicy.Normalisation.LANDSCAPE, ScreenOrientationPolicy.shapeOf(2220, 1080))
        assertEquals(ScreenOrientationPolicy.Normalisation.PORTRAIT, ScreenOrientationPolicy.shapeOf(1080, 2094))
    }

    @Test
    fun `a missing or square canvas remembers nothing`() {
        assertNull(ScreenOrientationPolicy.shapeOf(0, 0))
        assertNull(ScreenOrientationPolicy.shapeOf(2220, 0))
        assertNull(ScreenOrientationPolicy.shapeOf(1080, 1080))
    }

    // Measured in the car 2026-10-22 16:43: a session started on a unit whose rotation was locked
    // portrait came up portrait, and holding it there kept it portrait after rotation was turned
    // on. SYSTEM follows the system, and a turn to the other shape redraws the session instead.
    @Test
    fun `system holds no session shape`() {
        assertNull(ScreenOrientationPolicy.sessionOrientation(Settings.ScreenOrientation.SYSTEM, negotiatedLandscape = true))
        assertNull(ScreenOrientationPolicy.sessionOrientation(Settings.ScreenOrientation.SYSTEM, negotiatedLandscape = false))
    }

    private fun redraws(
        setting: Settings.ScreenOrientation = Settings.ScreenOrientation.SYSTEM,
        negotiated: ScreenOrientationPolicy.Normalisation? = ScreenOrientationPolicy.Normalisation.PORTRAIT,
        window: ScreenOrientationPolicy.Normalisation? = ScreenOrientationPolicy.Normalisation.LANDSCAPE,
        readingIsOurs: Boolean = true,
        alreadyRedrawnFor: ScreenOrientationPolicy.Normalisation? = null,
    ) = ScreenOrientationPolicy.redrawsSession(setting, negotiated, window, readingIsOurs, alreadyRedrawnFor)

    @Test
    fun `a system session whose own window turns to the other shape is redrawn`() {
        assertTrue(redraws())
        assertTrue(redraws(
            negotiated = ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            window = ScreenOrientationPolicy.Normalisation.PORTRAIT,
        ))
    }

    // Reverse landscape is still landscape: the stream fits it as it is.
    @Test
    fun `a window still in the negotiated shape keeps its session`() {
        assertFalse(redraws(window = ScreenOrientationPolicy.Normalisation.PORTRAIT))
    }

    // The keyguard is portrait whatever the mount, and is gone again a moment later.
    @Test
    fun `a turn read behind the keyguard, in the dark or in picture-in-picture keeps the session`() {
        assertFalse(redraws(readingIsOurs = false))
    }

    @Test
    fun `only system redraws a session`() {
        for (setting in Settings.ScreenOrientation.values().filter { it != Settings.ScreenOrientation.SYSTEM }) {
            assertFalse("setting=$setting", redraws(setting = setting))
        }
    }

    // A session that came back in the old shape would otherwise be redrawn on every landing.
    @Test
    fun `a window shape is redrawn for once`() {
        assertFalse(redraws(alreadyRedrawnFor = ScreenOrientationPolicy.Normalisation.LANDSCAPE))
        assertTrue(redraws(alreadyRedrawnFor = ScreenOrientationPolicy.Normalisation.PORTRAIT))
    }

    @Test
    fun `an unknown negotiated or window shape keeps the session`() {
        assertFalse(redraws(negotiated = null))
        assertFalse(redraws(window = null))
        assertFalse(redraws(window = null, alreadyRedrawnFor = ScreenOrientationPolicy.Normalisation.LANDSCAPE))
    }

    @Test
    fun `auto keeps the hold it already had`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ScreenOrientationPolicy.sessionOrientation(Settings.ScreenOrientation.AUTO, negotiatedLandscape = true)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ScreenOrientationPolicy.sessionOrientation(Settings.ScreenOrientation.AUTO, negotiatedLandscape = false)
        )
    }

    @Test
    fun `an explicit orientation is applied as set, with no session hold`() {
        for (setting in listOf(
            Settings.ScreenOrientation.LANDSCAPE,
            Settings.ScreenOrientation.LANDSCAPE_REVERSE,
            Settings.ScreenOrientation.PORTRAIT,
            Settings.ScreenOrientation.PORTRAIT_REVERSE,
        )) {
            assertNull(
                "setting=$setting",
                ScreenOrientationPolicy.sessionOrientation(setting, negotiatedLandscape = true)
            )
        }
    }
}
