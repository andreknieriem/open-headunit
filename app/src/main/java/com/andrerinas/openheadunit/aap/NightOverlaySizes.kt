package com.andrerinas.openheadunit.aap

/**
 * Text and icon sizes of the night overlay, from the shorter side of the screen.
 *
 * Without route guidance the clock is the main line. While the navigation row shows, the next
 * turn is what a glance is for, so the row gets the room: the clock and the track shrink, the
 * maneuver icon, the distance and the road grow.
 */
object NightOverlaySizes {
    data class Sizes(
        val clockSp: Float,
        val distanceSp: Float,
        val maneuverFallbackSp: Float,
        val roadSp: Float,
        val extraInfoSp: Float,
        val titleSp: Float,
        /** Edge of the maneuver icon in dp. */
        val iconDp: Float
    )

    /** [shorterDp] is the shorter side in dp, [scale] the text size option, see [NightOverlayOptions.textScale]. */
    fun of(shorterDp: Float, scale: Float, navigationShown: Boolean): Sizes =
        if (navigationShown) Sizes(
            clockSp = (shorterDp * 0.11f * scale).coerceIn(24f * scale, 56f * scale),
            distanceSp = (shorterDp * 0.21f * scale).coerceIn(28f * scale, 110f * scale),
            maneuverFallbackSp = (shorterDp * 0.12f * scale).coerceIn(20f * scale, 60f * scale),
            roadSp = (shorterDp * 0.085f * scale).coerceIn(14f * scale, 44f * scale),
            extraInfoSp = (shorterDp * 0.07f * scale).coerceIn(12f * scale, 36f * scale),
            titleSp = (shorterDp * 0.055f * scale).coerceIn(14f * scale, 28f * scale),
            iconDp = (shorterDp * 0.36f).coerceIn(64f, 200f)
        ) else Sizes(
            clockSp = (shorterDp * 0.30f * scale).coerceIn(57.6f * scale, 144f * scale),
            distanceSp = (shorterDp * 0.1235f * scale).coerceIn(20.8f * scale, 65f * scale),
            maneuverFallbackSp = (shorterDp * 0.085f * scale).coerceIn(16f * scale, 40f * scale),
            roadSp = (shorterDp * 0.0546f * scale).coerceIn(9.8f * scale, 28f * scale),
            extraInfoSp = (shorterDp * 0.0546f * scale).coerceIn(9.8f * scale, 28f * scale),
            titleSp = (shorterDp * 0.065f * scale).coerceIn(16f * scale, 32f * scale),
            iconDp = (shorterDp * 0.2f).coerceIn(48f, 148f)
        )
}
