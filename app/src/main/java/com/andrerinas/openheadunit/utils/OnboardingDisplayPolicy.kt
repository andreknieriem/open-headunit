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
    private const val TARGET_DPI_PER_800_PX = 150
    private const val REFERENCE_DIAGONAL_INCH = 7.5f

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

    /**
     * DPI of the surface Android Auto draws at the chosen size. Landscape aims at a 853 dp wide
     * canvas and never goes under Android Auto's minimum; portrait keeps the physical formula.
     */
    fun recommendedDpi(preset: DisplaySizePreset, renderWidthPx: Int, renderHeightPx: Int, portrait: Boolean): Int {
        if (portrait) {
            val diagonalPx = sqrt((renderWidthPx * renderWidthPx + renderHeightPx * renderHeightPx).toFloat())
            val dpi = ((diagonalPx / preset.diagonalInch) * LEGIBILITY_FACTOR).toInt()
            return dpi.coerceAtMost(MAX_DPI_PORTRAIT).coerceAtLeast(MIN_DPI)
        }
        val base = renderWidthPx * TARGET_DPI_PER_800_PX / 800f
        val scaled = base * minOf(1f, REFERENCE_DIAGONAL_INCH / preset.diagonalInch)
        val cap = minOf(MAX_DPI_LANDSCAPE, AnnouncedDensityPolicy.huigMaxDpi(renderWidthPx, renderHeightPx))
        return Math.round(scaled).coerceAtLeast(MIN_DPI).coerceAtMost(cap)
    }

    /** The size the user picked last time, or the estimate when there is none. */
    fun initialPreset(stored: String?, estimated: DisplaySizePreset): DisplaySizePreset =
        DisplaySizePreset.values().firstOrNull { it.name == stored } ?: estimated
}
