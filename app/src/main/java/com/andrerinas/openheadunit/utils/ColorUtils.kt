package com.andrerinas.openheadunit.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt

object ColorUtils {

    /**
     * Darkens a given color by multiplying its HSV Value (brightness) by [factor].
     */
    @ColorInt
    fun darkenColor(@ColorInt color: Int, factor: Float = 0.65f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Creates a vertical top-to-bottom [GradientDrawable] starting with [baseColor]
     * and ending with a darkened version of [baseColor].
     */
    fun createGradientDrawable(
        @ColorInt baseColor: Int,
        cornerRadiusDp: Float = 32f,
        context: Context
    ): GradientDrawable {
        val darkColor = darkenColor(baseColor, 0.65f)
        val density = context.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(baseColor, darkColor)
        ).apply {
            cornerRadius = cornerRadiusDp * density
        }
    }

    /**
     * Safely parses a hex color string (e.g. "#2CC6F2" or "2CC6F2").
     * Returns [defaultColor] if parsing fails.
     */
    @ColorInt
    fun parseColorSafely(hexString: String, defaultColor: Int = 0): Int {
        val trimmed = hexString.trim()
        if (trimmed.isEmpty()) return defaultColor
        val formatted = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return try {
            Color.parseColor(formatted)
        } catch (e: Exception) {
            defaultColor
        }
    }
}
