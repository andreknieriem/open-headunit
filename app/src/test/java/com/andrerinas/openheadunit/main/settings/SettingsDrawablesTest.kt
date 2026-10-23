package com.andrerinas.openheadunit.main.settings

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.app.Application
import android.view.ContextThemeWrapper
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// The real App class touches things Robolectric does not provide (a native library dir); these
// tests only need a themed context.
@Config(application = Application::class)
class SettingsDrawablesTest {

    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
    private val palette = SettingsPalette.from(context)

    private val innerCorner = SettingsDrawables.dp(context, 4f)

    @Test
    fun `a row in the middle of a group only rounds its inner corners`() {
        val shape = shapeOf(SettingsDrawables.groupItem(context, palette, GroupPosition.MIDDLE, OUTER))

        assertArrayEquals(FloatArray(8) { innerCorner }, shape.cornerRadii, 0.01f)
        assertEquals(palette.container, shape.color?.defaultColor)
    }

    @Test
    fun `the first and last rows round the ends of the card`() {
        val top = shapeOf(SettingsDrawables.groupItem(context, palette, GroupPosition.TOP, OUTER))
        assertArrayEquals(
            floatArrayOf(OUTER, OUTER, OUTER, OUTER, innerCorner, innerCorner, innerCorner, innerCorner),
            top.cornerRadii,
            0.01f
        )

        val bottom = shapeOf(SettingsDrawables.groupItem(context, palette, GroupPosition.BOTTOM, OUTER))
        assertArrayEquals(
            floatArrayOf(innerCorner, innerCorner, innerCorner, innerCorner, OUTER, OUTER, OUTER, OUTER),
            bottom.cornerRadii,
            0.01f
        )
    }

    @Test
    fun `extreme dark groups take an outline and the others do not`() {
        val day = shapeOf(SettingsDrawables.groupItem(context, palette, GroupPosition.SINGLE, OUTER))
        assertEquals(0, shadowOf(day).strokeWidth)

        val extreme = ContextThemeWrapper(context, R.style.ThemeOverlay_ExtremeDark)
        val extremePalette = SettingsPalette.from(extreme)
        assertTrue(extremePalette.outlinedGroups)
        val dark = shapeOf(SettingsDrawables.groupItem(extreme, extremePalette, GroupPosition.SINGLE, OUTER))
        assertTrue(shadowOf(dark).strokeWidth > 0)
    }

    @Test
    fun `the switch track is filled when on and outlined when off`() {
        val track = SettingsDrawables.switchTrack(context, palette)

        track.state = intArrayOf(android.R.attr.state_checked)
        val on = track.current as GradientDrawable
        assertEquals(palette.primary, on.color?.defaultColor)
        assertEquals(SettingsDrawables.dp(context, 2f).toInt(), shadowOf(on).strokeWidth)

        track.state = intArrayOf()
        val off = track.current as GradientDrawable
        assertEquals(palette.containerHighest, off.color?.defaultColor)
        assertEquals(SettingsDrawables.dp(context, 2f).toInt(), shadowOf(off).strokeWidth)
    }

    @Test
    fun `the switch thumb grows when checked`() {
        val thumb = SettingsDrawables.switchThumb(context, palette)

        thumb.state = intArrayOf(android.R.attr.state_checked)
        val on = (thumb.current as LayerDrawable).getDrawable(0) as GradientDrawable
        assertEquals(SettingsDrawables.dp(context, 24f).toInt(), on.intrinsicWidth)
        assertEquals(palette.onPrimary, on.color?.defaultColor)

        thumb.state = intArrayOf()
        val off = (thumb.current as LayerDrawable).getDrawable(0) as GradientDrawable
        assertEquals(SettingsDrawables.dp(context, 16f).toInt(), off.intrinsicWidth)
        assertEquals(palette.outline, off.color?.defaultColor)
    }

    @Test
    fun `the switch thumb keeps one slot size so the track is not drawn narrow`() {
        val thumb = SettingsDrawables.switchThumb(context, palette)
        val slotWidth = SettingsDrawables.dp(context, 52f).toInt() / 2
        val slotHeight = SettingsDrawables.dp(context, 32f).toInt()

        for (state in listOf(intArrayOf(android.R.attr.state_checked), intArrayOf())) {
            thumb.state = state
            assertEquals(slotWidth, thumb.current.intrinsicWidth)
            assertEquals(slotHeight, thumb.current.intrinsicHeight)
        }
    }

    @Test
    fun `checked and enabled color lists pick the color of each state`() {
        val checked = SettingsDrawables.checkedColors(CHOSEN, OTHER)
        assertEquals(CHOSEN, checked.getColorForState(intArrayOf(android.R.attr.state_checked), FALLBACK))
        assertEquals(OTHER, checked.getColorForState(intArrayOf(), FALLBACK))

        val enabled = SettingsDrawables.enabledColors(CHOSEN, OTHER)
        assertEquals(OTHER, enabled.getColorForState(intArrayOf(-android.R.attr.state_enabled), FALLBACK))
        assertEquals(CHOSEN, enabled.getColorForState(intArrayOf(android.R.attr.state_enabled), FALLBACK))
    }

    private fun shapeOf(drawable: android.graphics.drawable.Drawable): GradientDrawable =
        if (drawable is RippleDrawable) drawable.getDrawable(0) as GradientDrawable
        else (drawable as StateListDrawable).current as GradientDrawable

    private companion object {
        const val OUTER = 64f
        const val CHOSEN = 0xFF112233.toInt()
        const val OTHER = 0xFF445566.toInt()
        const val FALLBACK = 0
    }
}
