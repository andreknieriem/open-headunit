package com.andrerinas.openheadunit.main.settings

import android.app.Application
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// The real App class touches things Robolectric does not provide (a native library dir); these
// tests only need a themed context.
@Config(application = Application::class)
class SettingsPaletteTest {

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `reads the colors and flags from the app theme`() {
        val context = ContextThemeWrapper(app, R.style.AppTheme)
        val palette = SettingsPalette.from(context)

        assertEquals(ContextCompat.getColor(context, R.color.md_surface), palette.surface)
        assertEquals(ContextCompat.getColor(context, R.color.md_surface_container), palette.container)
        assertEquals(ContextCompat.getColor(context, R.color.md_primary), palette.primary)
        assertFalse(palette.outlinedGroups)
        assertTrue(palette.filledIcons)
    }

    @Test
    fun `the extreme dark overlay swaps the colors and the icon style`() {
        val context = ContextThemeWrapper(
            ContextThemeWrapper(app, R.style.AppTheme),
            R.style.ThemeOverlay_ExtremeDark
        )
        val palette = SettingsPalette.from(context)

        assertEquals(ContextCompat.getColor(context, R.color.extreme_dark_background), palette.surface)
        assertEquals(ContextCompat.getColor(context, R.color.md_extreme_primary), palette.primary)
        assertTrue(palette.outlinedGroups)
        assertFalse(palette.filledIcons)
    }

    @Test
    fun `a theme without the settings attributes falls back to the day and night colors`() {
        val context = ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault)
        val palette = SettingsPalette.from(context)

        assertEquals(ContextCompat.getColor(context, R.color.md_surface), palette.surface)
        assertEquals(ContextCompat.getColor(context, R.color.md_primary), palette.primary)
        assertFalse(palette.outlinedGroups)
        assertTrue(palette.filledIcons)
    }
}
