package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomePagesPolicy.Kind
import com.andrerinas.openheadunit.main.HomePagesPolicy.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePagesPolicyTest {

    @Test
    fun `with nothing added the home is one page`() {
        assertEquals(listOf(Page(Kind.HOME)), HomePagesPolicy.pages(compact = false, widgetPages = 0))
    }

    @Test
    fun `a small screen keeps the main button and moves the favorites to the next page`() {
        assertEquals(
            listOf(Page(Kind.HOME), Page(Kind.APPS)),
            HomePagesPolicy.pages(compact = true, widgetPages = 0)
        )
    }

    @Test
    fun `widget pages come after the main button and never past four pages`() {
        val pages = HomePagesPolicy.pages(compact = false, widgetPages = 9)
        assertEquals(4, pages.size)
        assertEquals(Page(Kind.HOME), pages.first())
        assertEquals(listOf(0, 1, 2), pages.drop(1).map { it.widgetPage })
    }

    @Test
    fun `a small screen shows one widget page fewer, since the favorites took one`() {
        val pages = HomePagesPolicy.pages(compact = true, widgetPages = 3)
        assertEquals(listOf(Kind.HOME, Kind.APPS, Kind.WIDGETS, Kind.WIDGETS), pages.map { it.kind })
    }

    @Test
    fun `widgets with no room under the favorites get the second page`() {
        assertEquals(
            listOf(Kind.HOME, Kind.WIDGET_ROW, Kind.WIDGETS),
            HomePagesPolicy.pages(compact = false, widgetPages = 1, widgetRowOverflows = true).map { it.kind }
        )
    }

    @Test
    fun `on the compact home the widget row already lives on the favorites' page`() {
        assertEquals(
            listOf(Kind.HOME, Kind.APPS),
            HomePagesPolicy.pages(compact = true, widgetPages = 0, widgetRowOverflows = true).map { it.kind }
        )
    }

    @Test
    fun `the page the widget row takes counts toward the limit`() {
        assertFalse(HomePagesPolicy.canAddPage(compact = false, widgetPages = 2, widgetRowOverflows = true))
        assertTrue(HomePagesPolicy.canAddPage(compact = false, widgetPages = 1, widgetRowOverflows = true))
    }

    @Test
    fun `a page can be added only while there is room for it`() {
        assertTrue(HomePagesPolicy.canAddPage(compact = false, widgetPages = 2))
        assertFalse(HomePagesPolicy.canAddPage(compact = false, widgetPages = 3))
        assertFalse(HomePagesPolicy.canAddPage(compact = true, widgetPages = 2))
    }

    @Test
    fun `with the favorites switch off the home has no widget page and no widget row page`() {
        assertEquals(
            listOf(Page(Kind.HOME)),
            HomePagesPolicy.pages(compact = false, widgetPages = 3, widgetRowOverflows = true, widgetsShown = false)
        )
    }

    @Test
    fun `with the favorites switch off a small screen still gives the all apps tile its page`() {
        assertEquals(
            listOf(Kind.HOME, Kind.APPS),
            HomePagesPolicy.pages(compact = true, widgetPages = 2, widgetsShown = false).map { it.kind }
        )
    }

    @Test
    fun `with the favorites switch off no page can be added`() {
        assertFalse(HomePagesPolicy.canAddPage(compact = false, widgetPages = 0, widgetsShown = false))
        assertFalse(HomePagesPolicy.canAddPage(compact = true, widgetPages = 0, widgetsShown = false))
    }

    @Test
    fun `stored pages keep their order and an empty page survives`() {
        assertEquals(listOf(listOf(12, 13), emptyList(), listOf(40)), HomePagesPolicy.decode("p12,13|p|p40"))
    }

    @Test
    fun `nothing stored is no pages, not one empty page`() {
        assertEquals(emptyList<List<Int>>(), HomePagesPolicy.decode(""))
    }

    @Test
    fun `nonsense is dropped rather than breaking the home`() {
        assertEquals(listOf(listOf(3)), HomePagesPolicy.decode("garbage|p3,x,3,|zz"))
    }

    @Test
    fun `encoding and decoding gives the same pages back`() {
        val pages = listOf(listOf(1, 2, 3, 4), emptyList(), listOf(9))
        assertEquals(pages, HomePagesPolicy.decode(HomePagesPolicy.encode(pages)))
    }

    @Test
    fun `more pages or widgets than fit are cut when stored`() {
        val pages = List(5) { listOf(1, 2, 3, 4, 5) }
        val decoded = HomePagesPolicy.decode(HomePagesPolicy.encode(pages))
        assertEquals(3, decoded.size)
        assertTrue(decoded.all { it.size == 4 })
    }

    @Test
    fun `adding a page stops at the limit`() {
        val three = List(3) { emptyList<Int>() }
        assertEquals(three, HomePagesPolicy.addPage(three))
        assertEquals(1, HomePagesPolicy.addPage(emptyList()).size)
    }

    @Test
    fun `removing a page leaves the others in order`() {
        assertEquals(listOf(listOf(1), listOf(3)), HomePagesPolicy.removePage(listOf(listOf(1), listOf(2), listOf(3)), 1))
    }

    @Test
    fun `a widget goes to the end of its page and a full page refuses one`() {
        assertEquals(listOf(listOf(1, 7)), HomePagesPolicy.addWidget(listOf(listOf(1)), 0, 7))
        assertNull(HomePagesPolicy.addWidget(listOf(listOf(1, 2, 3, 4)), 0, 7))
        assertNull(HomePagesPolicy.addWidget(listOf(listOf(1)), 3, 7))
    }

    @Test
    fun `removing a widget keeps its page, even when the page ends up empty`() {
        assertEquals(listOf(emptyList(), listOf(2)), HomePagesPolicy.removeWidget(listOf(listOf(1), listOf(2)), 1))
    }

    @Test
    fun `cells fill rows of the page's columns, the last row taking what is left`() {
        assertEquals(listOf(2, 1), HomePagesPolicy.rows(3, 2))
        assertEquals(listOf(2, 2), HomePagesPolicy.rows(4, 2))
        assertEquals(listOf(1, 1, 1), HomePagesPolicy.rows(3, 1))
        assertEquals(emptyList<Int>(), HomePagesPolicy.rows(0, 2))
    }

    @Test
    fun `the bar carries every connection only once the main button can be out of view`() {
        assertFalse(HomePagesPolicy.barCarriesEveryConnection(1))
        assertTrue(HomePagesPolicy.barCarriesEveryConnection(2))
    }

    @Test
    fun `a page that no longer exists falls back to the last one`() {
        assertEquals(1, HomePagesPolicy.clampPage(3, 2))
        assertEquals(0, HomePagesPolicy.clampPage(-1, 2))
        assertEquals(0, HomePagesPolicy.clampPage(2, 0))
    }

    @Test
    fun `favorites sit beside the card only on a really wide unit`() {
        // 1920x720 at 240 dpi
        assertTrue(HomePagesPolicy.sideBySide(landscape = true, widthDp = 1280, heightDp = 480))
        // 1280x720 at 146 dpi, a 16:9 unit
        assertFalse(HomePagesPolicy.sideBySide(landscape = true, widthDp = 1403, heightDp = 789))
        // 1920x1080 at 160 dpi
        assertFalse(HomePagesPolicy.sideBySide(landscape = true, widthDp = 1920, heightDp = 1080))
        // 8:3 but too narrow in dp
        assertFalse(HomePagesPolicy.sideBySide(landscape = true, widthDp = 960, heightDp = 360))
        assertFalse(HomePagesPolicy.sideBySide(landscape = false, widthDp = 1280, heightDp = 480))
        assertFalse(HomePagesPolicy.sideBySide(landscape = true, widthDp = 1280, heightDp = 0))
    }
}
