package com.andrerinas.openheadunit.aap

import kotlin.math.roundToInt

/** Turns the dim-opacity setting into the alpha of the black dimmer layer. */
object DimPolicy {
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 90
    const val DEFAULT_PERCENT = 50

    fun alphaFor(percent: Int): Int = (percent.coerceIn(MIN_PERCENT, MAX_PERCENT) * 255f / 100f).roundToInt()
}
