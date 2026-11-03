package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.ProjectionCanvasPolicy.Measurement
import com.andrerinas.openheadunit.utils.ProjectionCanvasPolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionCanvasPolicyTest {

    private val hash = 4711

    private fun choose(
        immersive: Boolean = false,
        surface: Measurement? = null,
        window: Measurement? = null,
        cached: Measurement? = null,
        hash: Int = this.hash,
    ) = ProjectionCanvasPolicy.choose(
        immersive = immersive,
        realW = 1920, realH = 720,
        usableW = 1920, usableH = 642,
        surface = surface, window = window, cached = cached, hash = hash,
    )

    @Test
    fun `nothing measured yet reads the panel in immersive`() {
        val choice = choose(immersive = true)
        assertEquals(1920, choice.width)
        assertEquals(720, choice.height)
        assertEquals(Source.DISPLAY, choice.source)
    }

    @Test
    fun `nothing measured yet reads the window metrics outside immersive`() {
        val choice = choose(immersive = false)
        assertEquals(1920, choice.width)
        assertEquals(642, choice.height)
        assertEquals(Source.DISPLAY, choice.source)
    }

    // #809's unit: getSize() reports 1920x642 for a window a ROM sidebar has narrowed to 1748x720,
    // so the display reading describes neither the panel nor the canvas the video is drawn into.
    @Test
    fun `a measured surface outranks the display metrics`() {
        val choice = choose(surface = Measurement(1748, 720, hash))
        assertEquals(1748, choice.width)
        assertEquals(720, choice.height)
        assertEquals(Source.SURFACE, choice.source)
    }

    @Test
    fun `a live window outranks a stored surface`() {
        val choice = choose(window = Measurement(1748, 720, hash), cached = Measurement(1600, 700, hash))
        assertEquals(1748, choice.width)
        assertEquals(Source.WINDOW, choice.source)
    }

    @Test
    fun `the projection surface outranks the window it is inside`() {
        val choice = choose(surface = Measurement(1748, 720, hash), window = Measurement(1920, 720, hash))
        assertEquals(1748, choice.width)
        assertEquals(Source.SURFACE, choice.source)
    }

    @Test
    fun `a cached surface is used when nothing has been measured this session`() {
        val choice = choose(cached = Measurement(1748, 720, hash))
        assertEquals(1748, choice.width)
        assertEquals(Source.CACHE, choice.source)
    }

    // Changing the screen mode changes the hash, and every reading described the old window.
    @Test
    fun `a measurement taken under other settings is ignored`() {
        val stale = Measurement(1920, 720, hash + 1)
        val choice = choose(surface = stale, window = stale, cached = stale)
        assertEquals(1920, choice.width)
        assertEquals(642, choice.height)
        assertEquals(Source.DISPLAY, choice.source)
    }

    @Test
    fun `an empty measurement is not a measurement`() {
        val choice = choose(surface = Measurement(0, 0, hash), immersive = true)
        assertEquals(720, choice.height)
        assertEquals(Source.DISPLAY, choice.source)
    }

    // Measured in Screen mode STATUS_ONLY on a 1440x720 panel with a 136 px OEM bar: the window was
    // measured before the bar arrived, the anchor was left at 1304, and the bar was then taken off
    // it again, so 1168 was the canvas the pixel shape would have been derived from.
    @Test
    fun `a bar that arrives after the canvas was measured does not shrink it twice`() {
        val anchor = ProjectionCanvasPolicy.anchor(1304, 720, 136, 0)
        assertEquals(1440, anchor.width)
        assertEquals(720, anchor.height)

        val canvas = ProjectionCanvasPolicy.canvas(anchor.width, anchor.height, 136, 0)
        assertEquals(1304, canvas.width)
        assertEquals(720, canvas.height)
    }

    @Test
    fun `the canvas inside an anchor is the canvas the anchor was built from`() {
        for (insetW in intArrayOf(0, 1, 136, 480)) {
            for (insetH in intArrayOf(0, 1, 216)) {
                val anchor = ProjectionCanvasPolicy.anchor(1280, 720, insetW, insetH)
                val canvas = ProjectionCanvasPolicy.canvas(anchor.width, anchor.height, insetW, insetH)
                assertEquals(1280, canvas.width)
                assertEquals(720, canvas.height)
            }
        }
    }

    @Test
    fun `insets that would empty the canvas are ignored rather than obeyed`() {
        val canvas = ProjectionCanvasPolicy.canvas(1280, 720, 1280, 0)
        assertEquals(1280, canvas.width)
        assertEquals(720, canvas.height)
    }

    // The panel reading this is checked against is the one that moved 2400x1080 -> 2237x1080 ->
    // 2300x1017 mid-connect, so an exact test would reject the very cache it is meant to protect.
    @Test
    fun `a stored canvas survives a panel reading that wobbled`() {
        assertTrue(ProjectionCanvasPolicy.fitsPanel(2400, 1080, 2237, 1080))
    }

    @Test
    fun `a stored canvas from a bigger panel is not this panel's canvas`() {
        assertFalse(ProjectionCanvasPolicy.fitsPanel(2400, 1080, 1024, 600))
    }

    @Test
    fun `nothing is known about the panel yet, so nothing is rejected`() {
        assertTrue(ProjectionCanvasPolicy.fitsPanel(2400, 1080, 0, 0))
    }

    @Test
    fun `window canvas changes invalidate surfaces even when settings do not change`() {
        val immersive = Measurement(1000, 600, hash)
        val fitted = Measurement(1000, 528, hash)
        assertTrue(ProjectionCanvasPolicy.windowChanged(immersive, fitted))
        assertTrue(ProjectionCanvasPolicy.windowChanged(fitted, immersive))
        assertTrue(ProjectionCanvasPolicy.windowChanged(fitted, Measurement(958, 576, hash)))
    }

    @Test
    fun `unchanged or first window report does not displace a valid measured surface`() {
        val window = Measurement(1000, 528, hash)
        assertFalse(ProjectionCanvasPolicy.windowChanged(window, window.copy()))
        assertFalse(ProjectionCanvasPolicy.windowChanged(null, window))
        assertEquals(Source.SURFACE, choose(surface = Measurement(960, 500, hash), window = window).source)
    }
}
