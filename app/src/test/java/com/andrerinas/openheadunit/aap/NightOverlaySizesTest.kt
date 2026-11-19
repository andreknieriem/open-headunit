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
}
