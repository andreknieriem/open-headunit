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

    /** Smallest maneuver icon in dp, distance in sp and fallback text in sp, at text size 1. */
    const val MIN_ICON_DP = 48f
    const val MIN_DISTANCE_SP = 20.8f
    const val MIN_FALLBACK_SP = 16f

    /** Gap between the parts of the navigation row in dp; the row holds four of them. */
    const val GAP_DP = 8f

    /**
     * Width in dp per sp of the widest distance string, for callers without a measuring function
     * (tests): about "88.8 km" in a bold sans-serif at font scale 1.
     */
    const val DEFAULT_DISTANCE_WIDTH_PER_SP = 3.7f

    /**
     * Share of the room the icon and the widest distance may fill. The overlay measures at the
     * planned size, but the layout rounds to whole pixels and text is placed at sub-pixel
     * positions, so an exact fit could still lose a pixel and be cut; 2 % keeps a margin no reader
     * notices.
     */
    const val FIT_SHARE = 0.98f

    /** Padding on each side of the overlay in dp. */
    fun paddingDp(shorterDp: Float): Float = shorterDp * 0.04f

    /**
     * [shorterDp] is the shorter side in dp, [scale] the text size option, see
     * [NightOverlayOptions.textScale]. [widthDp] is the width of the overlay in dp.
     * [distanceWidthDp] gives the width in dp of the widest distance string at a text size in sp,
     * measured at that size with the distance's own font and the system font size. Measuring at
     * each size, rather than scaling one ratio, also holds where the system font size scales text
     * non-linearly (Android 14 and later), and for any language.
     */
    fun of(
        shorterDp: Float,
        scale: Float,
        navigationShown: Boolean,
        widthDp: Float = Float.POSITIVE_INFINITY,
        distanceWidthDp: (Float) -> Float = { sp -> sp * DEFAULT_DISTANCE_WIDTH_PER_SP }
    ): Sizes {
        val plain = Sizes(
            clockSp = (shorterDp * 0.30f * scale).coerceIn(57.6f * scale, 144f * scale),
            distanceSp = (shorterDp * 0.1235f * scale).coerceIn(MIN_DISTANCE_SP * scale, 65f * scale),
            maneuverFallbackSp = (shorterDp * 0.085f * scale).coerceIn(MIN_FALLBACK_SP * scale, 40f * scale),
            roadSp = (shorterDp * 0.0546f * scale).coerceIn(9.8f * scale, 28f * scale),
            extraInfoSp = (shorterDp * 0.0546f * scale).coerceIn(9.8f * scale, 28f * scale),
            titleSp = (shorterDp * 0.065f * scale).coerceIn(16f * scale, 32f * scale),
            iconDp = (shorterDp * 0.2f).coerceIn(MIN_ICON_DP, 148f)
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
        // Room for the icon and the distance side by side.
        val room = (widthDp - 2 * paddingDp(shorterDp) - 4 * GAP_DP) * FIT_SHARE
        // The icon, the distance and the fallback text shrink by one factor, down to the minimum
        // sizes: a smaller whole number reads better than a cut one, also where the sizes without
        // navigation would not fit.
        fun shrunk(factor: Float) = nav.copy(
            iconDp = maxOf(nav.iconDp * factor, MIN_ICON_DP),
            distanceSp = maxOf(nav.distanceSp * factor, MIN_DISTANCE_SP * scale),
            maneuverFallbackSp = maxOf(nav.maneuverFallbackSp * factor, MIN_FALLBACK_SP * scale)
        )
        fun fits(s: Sizes) = s.iconDp + distanceWidthDp(s.distanceSp) <= room
        if (fits(nav)) return nav
        // The largest factor that fits. Width grows with size, so the fit only changes once
        // between 0 and 1, and halving the interval finds it; 20 steps are finer than a pixel.
        var fitting = 0f
        var tooLarge = 1f
        repeat(20) {
            val middle = (fitting + tooLarge) / 2
            if (fits(shrunk(middle))) fitting = middle else tooLarge = middle
        }
        return shrunk(fitting)
    }
}
