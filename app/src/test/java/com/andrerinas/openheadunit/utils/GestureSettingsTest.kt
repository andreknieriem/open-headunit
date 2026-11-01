package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GestureSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val raw get() = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    @Test fun dimScreenIsItsOwnAction() {
        assertEquals(Settings.GestureAction.DIM_SCREEN, Settings.GestureAction.fromInt(4))
        assertNotEquals(Settings.GestureAction.NIGHT_OVERLAY, Settings.GestureAction.DIM_SCREEN)
        assertNull(Settings.GestureAction.fromInt(99))
    }

    @Test fun aBadStoredValueReadsBackAsTheDefault() {
        raw.edit().clear().putInt("left-two-finger-gesture", 99).putInt("right-two-finger-gesture", 99)
            .putInt("long-press-two-finger-gesture", 99).putInt("left-tap-two-finger-gesture", 99)
            .putInt("right-tap-two-finger-gesture", 99).putInt("gesture-edge-zone", 9)
            .putInt("dim-opacity-percent", 5).commit()
        val settings = Settings(context)
        assertEquals(Settings.GestureAction.EXIT_DIALOG, settings.leftTwoFingerGesture)
        assertEquals(Settings.GestureAction.TOGGLE_FULLSCREEN, settings.rightTwoFingerGesture)
        assertEquals(Settings.GestureAction.DISABLED, settings.longPressTwoFingerGesture)
        assertEquals(Settings.GestureAction.DIM_SCREEN, settings.leftTapTwoFingerGesture)
        assertEquals(Settings.GestureAction.NIGHT_OVERLAY, settings.rightTapTwoFingerGesture)
        assertEquals(Settings.GestureEdgeZone.NORMAL, settings.gestureEdgeZone)
        assertEquals(50, settings.dimOpacityPercent)
    }

    @Test fun tapSlotsAndTheZoneWidthDefaultAndWriteNothing() {
        raw.edit().clear().commit()
        val settings = Settings(context)
        assertEquals(Settings.GestureAction.DIM_SCREEN, settings.leftTapTwoFingerGesture)
        assertEquals(Settings.GestureAction.NIGHT_OVERLAY, settings.rightTapTwoFingerGesture)
        assertEquals(Settings.GestureAction.DISABLED, settings.longPressTwoFingerGesture)
        assertEquals(Settings.GestureEdgeZone.NORMAL, settings.gestureEdgeZone)
        assertEquals(0, raw.all.size)
        settings.gestureEdgeZone = Settings.GestureEdgeZone.WIDE
        settings.rightTapTwoFingerGesture = Settings.GestureAction.DISABLED
        assertEquals(Settings.GestureEdgeZone.WIDE, Settings(context).gestureEdgeZone)
        assertEquals(Settings.GestureAction.DISABLED, Settings(context).rightTapTwoFingerGesture)
        settings.resetGestureSettings()
        assertEquals(0, raw.all.size)
    }

    @Test fun dimOpacityDefaultsToFiftyAndWritesNothing() {
        raw.edit().clear().commit()
        assertEquals(50, Settings(context).dimOpacityPercent)
        assertEquals(false, raw.contains("dim-opacity-percent"))
        Settings(context).dimOpacityPercent = 70
        assertEquals(70, Settings(context).dimOpacityPercent)
    }

    @Test fun nightOverlayOptionsDefaultAndWriteNothing() {
        raw.edit().clear().commit()
        val settings = Settings(context)
        assertEquals(Settings.NightOverlayTextSize.NORMAL, settings.nightOverlayTextSize)
        assertEquals(true, settings.nightOverlayShowClock)
        assertEquals(true, settings.nightOverlayShowNavigation)
        assertEquals(true, settings.nightOverlayShowTrack)
        assertEquals(Settings.NightOverlayBackground.BLACK, settings.nightOverlayBackground)
        assertEquals(0, raw.all.size)
    }

    @Test fun aBadStoredEnumReadsBackAsTheDefault() {
        raw.edit().clear().putInt("night-overlay-text-size", 9).putInt("night-overlay-background", 9).commit()
        val settings = Settings(context)
        assertEquals(Settings.NightOverlayTextSize.NORMAL, settings.nightOverlayTextSize)
        assertEquals(Settings.NightOverlayBackground.BLACK, settings.nightOverlayBackground)
    }

    @Test fun resetRemovesTheGestureKeysAndNothingElse() {
        raw.edit().clear().putInt("left-two-finger-gesture", 0).putInt("dim-opacity-percent", 70)
            .putBoolean("night-overlay-show-clock", false).putInt("other-key", 7).commit()
        val settings = Settings(context)
        settings.resetGestureSettings()
        assertEquals(false, raw.contains("left-two-finger-gesture"))
        assertEquals(false, raw.contains("dim-opacity-percent"))
        assertEquals(false, raw.contains("night-overlay-show-clock"))
        assertEquals(7, raw.getInt("other-key", 0))
        assertEquals(Settings.GestureAction.EXIT_DIALOG, settings.leftTwoFingerGesture)
        assertEquals(50, settings.dimOpacityPercent)
        assertEquals(true, settings.nightOverlayShowClock)
    }

    @Test fun resetWritesNothingWhenNothingWasStored() {
        raw.edit().clear().commit()
        Settings(context).resetGestureSettings()
        assertEquals(0, raw.all.size)
    }
}
