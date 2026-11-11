package com.andrerinas.openheadunit.utils

/**
 * The identity of the settings a canvas measurement was taken under. A reading only counts for the
 * settings it describes, and a display metric that moves by a decoration's width is not a setting:
 * folding one in threw away a good window measurement and then supplied the fallback itself.
 */
object ScreenSettingsHash {

    /**
     * [normalisation] stands in for the panel the hash used to fold. It is what actually invalidates
     * a measurement, because AUTO and SYSTEM rotate without the orientation setting moving.
     */
    fun of(
        resolutionId: Int,
        dpiPixelDensity: Int,
        pixelAspectRatioE4: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        viewMode: Int,
        screenOrientation: Int,
        fullscreenMode: Int,
        videoFitMode: Int,
        forcedScale: Boolean,
        normalisation: ScreenOrientationPolicy.Normalisation,
        dpiAutoLayout: String = "",
    ): Int {
        var hash = 17
        hash = 31 * hash + resolutionId
        hash = 31 * hash + dpiPixelDensity
        hash = 31 * hash + pixelAspectRatioE4
        hash = 31 * hash + insetLeft
        hash = 31 * hash + insetTop
        hash = 31 * hash + insetRight
        hash = 31 * hash + insetBottom
        hash = 31 * hash + viewMode
        hash = 31 * hash + screenOrientation
        hash = 31 * hash + fullscreenMode
        hash = 31 * hash + videoFitMode
        hash = 31 * hash + (if (forcedScale) 1 else 0)
        hash = 31 * hash + normalisation.ordinal
        // Folded only when set, so an upgrade keeps every cached measurement.
        if (dpiAutoLayout.isNotEmpty()) hash = 31 * hash + dpiAutoLayout.hashCode()
        return hash
    }
}
