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
            .putInt("down-two-finger-gesture", 99).putInt("long-press-two-finger-gesture", 99)
            .putInt("dim-opacity-percent", 5).commit()
        val settings = Settings(context)
        assertEquals(Settings.GestureAction.EXIT_DIALOG, settings.leftTwoFingerGesture)
        assertEquals(Settings.GestureAction.TOGGLE_FULLSCREEN, settings.rightTwoFingerGesture)
        assertEquals(Settings.GestureAction.DISABLED, settings.downTwoFingerGesture)
        assertEquals(Settings.GestureAction.DISABLED, settings.longPressTwoFingerGesture)
        assertEquals(50, settings.dimOpacityPercent)
    }

    @Test fun dimOpacityDefaultsToFiftyAndWritesNothing() {
        raw.edit().clear().commit()
        assertEquals(50, Settings(context).dimOpacityPercent)
        assertEquals(false, raw.contains("dim-opacity-percent"))
        Settings(context).dimOpacityPercent = 70
        assertEquals(70, Settings(context).dimOpacityPercent)
    }
}
