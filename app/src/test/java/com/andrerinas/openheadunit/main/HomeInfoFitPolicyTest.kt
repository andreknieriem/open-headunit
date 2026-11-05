package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomeInfoFitPolicy.Block
import com.andrerinas.openheadunit.main.HomeInfoFitPolicy.Line
import com.andrerinas.openheadunit.main.HomeInfoFitPolicy.Placement
import com.andrerinas.openheadunit.main.HomeInfoFitPolicy.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInfoFitPolicyTest {

    /** A wide landscape card: 900 px inside its padding, and little height above the title. */
    private val landscape = Room(inner = 900, gap = 12, minTitle = 300, iconWidth = 64, iconHeight = 64, freeHeight = 40)

    /** A portrait phone card: narrow, with a tall empty space above the title. */
    private val portrait = Room(inner = 340, gap = 12, minTitle = 120, iconWidth = 64, iconHeight = 64, freeHeight = 400)

    private fun line(width: Int, minWidth: Int = 120, height: Int = 30, cap: Int = 380) = Line(width, minWidth, height, cap)

    @Test
    fun `without a maintenance line the clock keeps the old rule`() {
        val fit = HomeInfoFitPolicy.fit(landscape, Block(150, 50, null))
        assertEquals(Placement.BESIDE_TITLE, fit.placement)
        assertEquals(900 - 150 - 12, fit.titleWidth)
        assertFalse(fit.showLine)
        assertEquals(Placement.NONE, HomeInfoFitPolicy.fit(portrait, Block(260, 50, null)).placement)
    }

    @Test
    fun `a line that fits stays beside the title at its full width`() {
        val fit = HomeInfoFitPolicy.fit(landscape, Block(150, 50, line(320)))
        assertEquals(Placement.BESIDE_TITLE, fit.placement)
        assertEquals(320, fit.lineWidth)
        assertEquals(900 - 320 - 12, fit.titleWidth)
    }

    @Test
    fun `a long line beside the title stops at its cap and shortens its name`() {
        val fit = HomeInfoFitPolicy.fit(landscape, Block(150, 50, line(560)))
        assertEquals(Placement.BESIDE_TITLE, fit.placement)
        assertEquals(380, fit.lineWidth)
    }

    @Test
    fun `in portrait a long line takes a row above the title and the clock stays`() {
        val fit = HomeInfoFitPolicy.fit(portrait, Block(150, 70, line(500)))
        assertEquals(Placement.LINE_ON_OWN_ROW, fit.placement)
        assertTrue(fit.showInfo)
        // Under the icon's height, so the row takes the card's whole width.
        assertEquals(340, fit.lineWidth)
        assertEquals(340 - 150 - 12, fit.titleWidth)
    }

    @Test
    fun `a row that starts beside the icon leaves it its width`() {
        val fit = HomeInfoFitPolicy.fit(portrait, Block(150, 40, line(500)))
        assertEquals(Placement.LINE_ON_OWN_ROW, fit.placement)
        assertEquals(340 - 64 - 12, fit.lineWidth)
    }

    @Test
    fun `without height for a row the line shortens its name beside the title`() {
        val fit = HomeInfoFitPolicy.fit(landscape.copy(inner = 600), Block(150, 50, line(500)))
        assertEquals(Placement.BESIDE_TITLE, fit.placement)
        assertEquals(600 - 12 - 300, fit.lineWidth)
        assertEquals(300, fit.titleWidth)
    }

    @Test
    fun `when the clock is what costs the room it steps aside and the line stays`() {
        val narrow = portrait.copy(inner = 260, freeHeight = 40)
        val fit = HomeInfoFitPolicy.fit(narrow, Block(160, 70, line(200, minWidth = 100)))
        assertEquals(Placement.LINE_ONLY, fit.placement)
        assertFalse(fit.showInfo)
        assertEquals(260 - 64 - 12, fit.lineWidth)
        assertNull(fit.titleWidth)
    }

    @Test
    fun `the line alone goes beside the title when there is no row above it`() {
        val flat = portrait.copy(inner = 400, freeHeight = 10)
        val fit = HomeInfoFitPolicy.fit(flat, Block(300, 70, line(250, minWidth = 100)))
        assertEquals(Placement.LINE_ONLY, fit.placement)
        assertEquals(250, fit.lineWidth)
        assertEquals(400 - 250 - 12, fit.titleWidth)
    }

    @Test
    fun `the whole state never gets cut, the block steps out instead`() {
        val tiny = Room(inner = 200, gap = 6, minTitle = 70, iconWidth = 40, iconHeight = 40, freeHeight = 10)
        assertEquals(Placement.NONE, HomeInfoFitPolicy.fit(tiny, Block(100, 30, line(300, minWidth = 180))).placement)
    }
}
