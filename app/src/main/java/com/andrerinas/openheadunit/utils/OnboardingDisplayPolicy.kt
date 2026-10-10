package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset
import kotlin.math.sqrt

/**
 * The wizard's screen size and the DPI it implies. The size only matters through the DPI, so a
 * size the picker does not follow is a choice that is silently dropped.
 */
object OnboardingDisplayPolicy {

    /** Nudges the density up for the car viewing distance, which is farther than a phone's. */
    private const val LEGIBILITY_FACTOR = 1.1f
    private const val MIN_DPI = 110
    private const val MAX_DPI_LANDSCAPE = 240
    private const val MAX_DPI_PORTRAIT = 190

    /** The preset nearest the panel's physical diagonal. An xdpi of 40 or less is unreadable. */
    fun estimatePreset(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float, densityDpi: Int): DisplaySizePreset {
        val x = if (xdpi > 40f) xdpi else densityDpi.toFloat()
        val y = if (ydpi > 40f) ydpi else densityDpi.toFloat()
        val wIn = widthPx / x
        val hIn = heightPx / y
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        return DisplaySizePreset.values().minByOrNull { kotlin.math.abs(it.diagonalInch - diagonal) }
            ?: DisplaySizePreset.STANDARD_9_10
    }

    /** DPI of the surface Android Auto draws (the panel-capped resolution) at the chosen size. */
    fun recommendedDpi(preset: DisplaySizePreset, renderWidthPx: Int, renderHeightPx: Int, portrait: Boolean): Int {
        val diagonalPx = sqrt((renderWidthPx * renderWidthPx + renderHeightPx * renderHeightPx).toFloat())
        val dpi = ((diagonalPx / preset.diagonalInch) * LEGIBILITY_FACTOR).toInt()
        return dpi.coerceAtMost(if (portrait) MAX_DPI_PORTRAIT else MAX_DPI_LANDSCAPE).coerceAtLeast(MIN_DPI)
    }

    /** A size change moves the picker, unless the user has already set the DPI by hand. */
    fun pickerDpiAfterSizeChange(recommended: Int, current: Int, pickerTouched: Boolean): Int =
        if (pickerTouched) current else recommended

    /** The size the user picked last time, or the estimate when there is none. */
    fun initialPreset(stored: String?, estimated: DisplaySizePreset): DisplaySizePreset =
        DisplaySizePreset.values().firstOrNull { it.name == stored } ?: estimated
}
