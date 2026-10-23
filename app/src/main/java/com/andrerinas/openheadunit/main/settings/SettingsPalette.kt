package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R

/**
 * The settings screen's Material 3 colors, read once from the theme.
 *
 * The values live in theme attributes (attrs.xml) so that ThemeOverlay.ExtremeDark can swap the
 * whole set. They are resolved here rather than referenced from drawable XML because a shape
 * drawable cannot read a theme attribute before API 21, and this screen runs down to API 16.
 * A theme that does not define an attribute (a translucent helper activity, say) falls back to
 * the day/night `md_*` color, so nothing reading the palette can crash on a missing attribute.
 */
class SettingsPalette private constructor(
    @ColorInt val surface: Int,
    @ColorInt val container: Int,
    @ColorInt val containerHigh: Int,
    @ColorInt val containerHighest: Int,
    @ColorInt val onSurface: Int,
    @ColorInt val onSurfaceVariant: Int,
    @ColorInt val outline: Int,
    @ColorInt val outlineVariant: Int,
    @ColorInt val primary: Int,
    @ColorInt val onPrimary: Int,
    @ColorInt val secondaryContainer: Int,
    @ColorInt val onSecondaryContainer: Int,
    /** True where every surface is the same black, so groups need an outline to stand apart. */
    val outlinedGroups: Boolean,
    /** Filled Material Symbols in day and night, outline-only ones on extreme dark. */
    val filledIcons: Boolean
) {
    companion object {
        fun from(context: Context): SettingsPalette {
            val value = TypedValue()
            fun color(@AttrRes attr: Int, @ColorRes fallback: Int): Int {
                val resolved = context.theme.resolveAttribute(attr, value, true) &&
                    value.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                    value.type <= TypedValue.TYPE_LAST_COLOR_INT
                return if (resolved) value.data else ContextCompat.getColor(context, fallback)
            }
            fun flag(@AttrRes attr: Int, fallback: Boolean): Boolean {
                val resolved = context.theme.resolveAttribute(attr, value, true) &&
                    value.type == TypedValue.TYPE_INT_BOOLEAN
                return if (resolved) value.data != 0 else fallback
            }
            return SettingsPalette(
                surface = color(R.attr.settingsSurface, R.color.md_surface),
                container = color(R.attr.settingsContainer, R.color.md_surface_container),
                containerHigh = color(R.attr.settingsContainerHigh, R.color.md_surface_container_high),
                containerHighest = color(R.attr.settingsContainerHighest, R.color.md_surface_container_highest),
                onSurface = color(R.attr.settingsOnSurface, R.color.md_on_surface),
                onSurfaceVariant = color(R.attr.settingsOnSurfaceVariant, R.color.md_on_surface_variant),
                outline = color(R.attr.settingsOutline, R.color.md_outline),
                outlineVariant = color(R.attr.settingsOutlineVariant, R.color.md_outline_variant),
                primary = color(R.attr.settingsPrimary, R.color.md_primary),
                onPrimary = color(R.attr.settingsOnPrimary, R.color.md_on_primary),
                secondaryContainer = color(R.attr.settingsSecondaryContainer, R.color.md_secondary_container),
                onSecondaryContainer = color(R.attr.settingsOnSecondaryContainer, R.color.md_on_secondary_container),
                outlinedGroups = flag(R.attr.settingsOutlinedGroups, false),
                filledIcons = flag(R.attr.settingsFilledIcons, true)
            )
        }
    }
}
