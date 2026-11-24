package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightOverlaySizesTest {

    @Test
    fun `without navigation the sizes are the ones the overlay always had`() {
        val sizes = NightOverlaySizes.of(400f, 1f, navigationShown = false)
        assertEquals(120f, sizes.clockSp, 0.01f)
        assertEquals(49.4f, sizes.distanceSp, 0.01f)
        assertEquals(34f, sizes.maneuverFallbackSp, 0.01f)
        assertEquals(21.84f, sizes.roadSp, 0.01f)
        assertEquals(21.84f, sizes.extraInfoSp, 0.01f)
        assertEquals(26f, sizes.titleSp, 0.01f)
        assertEquals(80f, sizes.iconDp, 0.01f)
    }

    @Test
    fun `with navigation the turn is larger than the clock and the track`() {
        for (shorterDp in listOf(240f, 360f, 400f, 600f, 800f, 1200f)) for (scale in listOf(0.8f, 1f, 1.3f)) {
            val sizes = NightOverlaySizes.of(shorterDp, scale, navigationShown = true)
            assertTrue("distance over clock at $shorterDp dp x$scale", sizes.distanceSp > sizes.clockSp)
            assertTrue("distance over track at $shorterDp dp x$scale", sizes.distanceSp > sizes.titleSp)
            assertTrue("road over track at $shorterDp dp x$scale", sizes.roadSp >= sizes.titleSp)
            assertTrue("clock over track at $shorterDp dp x$scale", sizes.clockSp >= sizes.titleSp)
        }
    }

    @Test
    fun `navigation shrinks the clock and the track and grows its own row`() {
        for (shorterDp in listOf(240f, 360f, 400f, 600f, 800f)) {
            val plain = NightOverlaySizes.of(shorterDp, 1f, navigationShown = false)
            val nav = NightOverlaySizes.of(shorterDp, 1f, navigationShown = true)
            assertTrue(nav.clockSp < plain.clockSp)
            assertTrue(nav.titleSp <= plain.titleSp)
            assertTrue(nav.distanceSp > plain.distanceSp)
            assertTrue(nav.roadSp > plain.roadSp)
            assertTrue(nav.iconDp > plain.iconDp)
        }
    }

    @Test
    fun `the lines fit the height of a phone in landscape`() {
        // Rough line heights: text 1.2 times its size, the gaps of the layout (24 dp twice, 4 dp twice).
        for (scale in listOf(0.8f, 1f, 1.3f)) {
            val s = NightOverlaySizes.of(360f, scale, navigationShown = true)
            val texts = (s.distanceSp + s.roadSp + s.extraInfoSp) * 1.2f + 8f
            val total = s.clockSp * 1.2f + 24f + maxOf(s.iconDp, texts) + 24f + s.titleSp * 1.2f
            assertTrue("height $total at x$scale", total <= 360f)
        }
    }

    private fun roomDp(widthDp: Float) = widthDp - 2 * NightOverlaySizes.paddingDp(widthDp) - 4 * NightOverlaySizes.GAP_DP

    /** About "88.8 km" in bold at font scale 1: 3.7 dp per sp, in proportion to the size. */
    private val linear: (Float) -> Float = { sp -> sp * 3.7f }

    /**
     * Like Android 14's non-linear font scaling at a large font size: small text grows by 2, large
     * text by less, so the width does not grow in proportion to the size.
     */
    private val nonLinear: (Float) -> Float = { sp -> 3.7f * sp * (2f - minOf(sp, 100f) / 100f) }

    private fun used(s: NightOverlaySizes.Sizes, width: (Float) -> Float) = s.iconDp + width(s.distanceSp)

    @Test
    fun `in portrait the widest distance fits beside the icon`() {
        // Portrait: the shorter side is the width.
        for (widthDp in listOf(320f, 360f, 400f, 480f, 600f)) for (scale in listOf(0.8f, 1f, 1.3f)) for (width in listOf(linear, nonLinear)) {
            val s = NightOverlaySizes.of(widthDp, scale, navigationShown = true, widthDp = widthDp, distanceWidthDp = width)
            val room = roomDp(widthDp) * NightOverlaySizes.FIT_SHARE
            assertTrue("icon and distance take ${used(s, width)} of $room dp at $widthDp dp x$scale", used(s, width) <= room + 0.01f)
        }
    }

    @Test
    fun `in portrait the turn stays larger than the clock`() {
        for (widthDp in listOf(320f, 360f, 400f, 480f, 600f)) for (scale in listOf(0.8f, 1f, 1.3f)) {
            val s = NightOverlaySizes.of(widthDp, scale, navigationShown = true, widthDp = widthDp, distanceWidthDp = linear)
            assertTrue("distance over clock at $widthDp dp x$scale", s.distanceSp > s.clockSp)
        }
    }

    @Test
    fun `when it shrinks, the turn fills the room instead of shrinking more than needed`() {
        for (widthDp in listOf(320f, 360f, 400f)) for (width in listOf(linear, nonLinear)) {
            val s = NightOverlaySizes.of(widthDp, 1f, navigationShown = true, widthDp = widthDp, distanceWidthDp = width)
            val room = roomDp(widthDp) * NightOverlaySizes.FIT_SHARE
            assertTrue("only ${used(s, width)} of $room dp used at $widthDp dp", used(s, width) >= room * 0.995f)
        }
    }

    @Test
    fun `a wide distance string still fits, below the sizes without navigation if it must`() {
        // 5 dp per sp: a large system font or a long unit. The sizes without navigation would not fit here.
        val wide: (Float) -> Float = { sp -> sp * 5f }
        for (widthDp in listOf(320f, 360f)) for (scale in listOf(1f, 1.3f)) {
            val s = NightOverlaySizes.of(widthDp, scale, navigationShown = true, widthDp = widthDp, distanceWidthDp = wide)
            assertTrue("${used(s, wide)} dp at $widthDp dp x$scale", used(s, wide) <= roomDp(widthDp) * NightOverlaySizes.FIT_SHARE + 0.01f)
        }
    }

    @Test
    fun `the width cap stops at the minimum sizes`() {
        // An impossible string: far too wide for any size.
        val s = NightOverlaySizes.of(320f, 1f, navigationShown = true, widthDp = 320f, distanceWidthDp = { sp -> sp * 50f })
        assertEquals(NightOverlaySizes.MIN_ICON_DP, s.iconDp, 0.01f)
        assertEquals(NightOverlaySizes.MIN_DISTANCE_SP, s.distanceSp, 0.01f)
        assertEquals(NightOverlaySizes.MIN_FALLBACK_SP, s.maneuverFallbackSp, 0.01f)
    }

    @Test
    fun `in landscape the width leaves the navigation sizes as they are`() {
        for ((shorterDp, widthDp) in listOf(360f to 640f, 400f to 800f, 600f to 1024f)) for (scale in listOf(0.8f, 1f, 1.3f)) {
            assertEquals(
                NightOverlaySizes.of(shorterDp, scale, navigationShown = true),
                NightOverlaySizes.of(shorterDp, scale, navigationShown = true, widthDp = widthDp, distanceWidthDp = linear)
            )
        }
    }

    @Test
    fun `the width does not change the sizes without navigation`() {
        assertEquals(
            NightOverlaySizes.of(360f, 1f, navigationShown = false),
            NightOverlaySizes.of(360f, 1f, navigationShown = false, widthDp = 360f, distanceWidthDp = { sp -> sp * 5f })
        )
    }
}
