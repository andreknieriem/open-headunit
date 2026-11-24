package com.andrerinas.openheadunit.aap

/**
 * Text and icon sizes of the night overlay, from the shorter side of the screen.
 *
 * Without route guidance the clock is the main line. While the navigation row shows, the next
 * turn is what a glance is for, so the row gets the room: the clock and the track shrink, the
 * maneuver icon, the distance and the road grow.
 *
 * The icon and the distance sit side by side, so in portrait, where the shorter side is the
 * width, both are also capped by the width: the widest distance string must fit beside the icon.
 */
object NightOverlaySizes {
    data class Sizes(
        val clockSp: Float,
        val distanceSp: Float,
        val maneuverFallbackSp: Float,
        val roadSp: Float,
        val extraInfoSp: Float,
        val titleSp: Float,
        /** Edge of the maneuver icon in dp. The fallback text takes the same width at most. */
        val iconDp: Float
    )

    /** Gap between the parts of the navigation row in dp; the row holds four of them. */
    const val GAP_DP = 8f

    /**
     * Width in dp per sp of text of the widest distance string, for callers without a measured
     * value (tests): about "88.8 km" in a bold sans-serif at font scale 1.
     */
    const val DEFAULT_DISTANCE_WIDTH_PER_SP = 3.7f

    /** Padding on each side of the overlay in dp. */
    fun paddingDp(shorterDp: Float): Float = shorterDp * 0.04f

    /**
     * [shorterDp] is the shorter side in dp, [scale] the text size option, see
     * [NightOverlayOptions.textScale]. [widthDp] is the width of the overlay in dp and
     * [distanceWidthPerSp] the width in dp of the widest distance string at a text size of 1 sp,
     * measured with the distance's own font and the system font size.
     */
    fun of(
        shorterDp: Float,
        scale: Float,
        navigationShown: Boolean,
        widthDp: Float = Float.POSITIVE_INFINITY,
        distanceWidthPerSp: Float = DEFAULT_DISTANCE_WIDTH_PER_SP
    ): Sizes {
        val plain = Sizes(
            clockSp = (shorterDp * 0.30f * scale).coerceIn(57.6f * scale, 144f * scale),
            distanceSp = (shorterDp * 0.1235f * scale).coerceIn(20.8f * scale, 65f * scale),
            maneuverFallbackSp = (shorterDp * 0.085f * scale).coerceIn(16f * scale, 40f * scale),
            roadSp = (shorterDp * 0.0546f * scale).coerceIn(9.8f * scale, 28f * scale),
            extraInfoSp = (shorterDp * 0.0546f * scale).coerceIn(9.8f * scale, 28f * scale),
            titleSp = (shorterDp * 0.065f * scale).coerceIn(16f * scale, 32f * scale),
            iconDp = (shorterDp * 0.2f).coerceIn(48f, 148f)
        )
        if (!navigationShown) return plain
        val nav = Sizes(
            clockSp = (shorterDp * 0.11f * scale).coerceIn(24f * scale, 56f * scale),
            distanceSp = (shorterDp * 0.21f * scale).coerceIn(28f * scale, 110f * scale),
            maneuverFallbackSp = (shorterDp * 0.12f * scale).coerceIn(20f * scale, 60f * scale),
            roadSp = (shorterDp * 0.085f * scale).coerceIn(14f * scale, 44f * scale),
            extraInfoSp = (shorterDp * 0.07f * scale).coerceIn(12f * scale, 36f * scale),
            titleSp = (shorterDp * 0.055f * scale).coerceIn(14f * scale, 28f * scale),
            iconDp = (shorterDp * 0.36f).coerceIn(64f, 200f)
        )
        // Room for the icon and the distance side by side, and what the larger sizes would take.
        val room = widthDp - 2 * paddingDp(shorterDp) - 4 * GAP_DP
        val needed = nav.iconDp + nav.distanceSp * distanceWidthPerSp
        if (needed <= room) return nav
        // Shrink both by the same factor, but never below the sizes without navigation.
        val factor = room / needed
        return nav.copy(
            iconDp = maxOf(nav.iconDp * factor, plain.iconDp),
            distanceSp = maxOf(nav.distanceSp * factor, plain.distanceSp),
            maneuverFallbackSp = maxOf(nav.maneuverFallbackSp * factor, plain.maneuverFallbackSp)
        )
    }
}
