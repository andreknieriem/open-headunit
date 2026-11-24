package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.R

/**
 * How the night overlay writes a distance: whole metres below 1 km, kilometres with one decimal
 * below 100 km, whole kilometres from 100 km up. A tenth of a kilometre says nothing at that
 * distance, and the shorter string stays within the width [NightOverlaySizes] plans for the
 * distance beside the maneuver icon.
 */
object NightOverlayDistance {
    sealed class Display {
        data class Meters(val meters: Int) : Display()
        data class Kilometers(val km: Double) : Display()
        data class WholeKilometers(val km: Int) : Display()
    }

    /** Kept free of Android calls so it can be unit tested. */
    fun of(meters: Int): Display = when {
        meters < 1000 -> Display.Meters(meters)
        // From 99 950 m one decimal would round to "100.0 km", one character wider: whole kilometres.
        meters < 99_950 -> Display.Kilometers(meters / 1000.0)
        else -> Display.WholeKilometers(Math.round(meters / 1000.0).toInt())
    }

    fun format(context: Context, meters: Int): String = when (val d = of(meters)) {
        is Display.Meters -> context.getString(R.string.night_overlay_meters, d.meters)
        is Display.Kilometers -> context.getString(R.string.night_overlay_kilometers, d.km)
        is Display.WholeKilometers -> context.getString(R.string.night_overlay_kilometers_whole, d.km)
    }
}
