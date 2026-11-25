package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Choice
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Layout
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Pane
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Pick
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Run
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Thresholds
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Writes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayLayoutPolicyTest {

    private fun layout(cw: Int, ch: Int, d: Int) = DisplayLayoutPolicy.layoutOf(cw, ch, d)

    @Test
    fun `the measured edges hold`() {
        assertEquals(Layout.WIDESCREEN, layout(1440, 720, 185))
        assertEquals(Layout.VERTICAL_RAIL, layout(1440, 720, 186))
        assertEquals(Layout.CANONICAL, layout(1920, 1080, 350))
        assertEquals(Layout.VERTICAL_RAIL, layout(1920, 1080, 349))
        assertEquals(Layout.VERTICAL_RAIL, layout(1920, 1080, 248))
        assertEquals(Layout.WIDESCREEN, layout(1920, 1080, 247))
        assertEquals(Layout.CANONICAL, layout(1280, 800, 189))
        assertEquals(Layout.PORTRAIT_SHORT, layout(1280, 800, 188))
        assertEquals(Layout.PORTRAIT_SHORT, layout(1280, 800, 166))
        assertEquals(Layout.WIDESCREEN, layout(1280, 800, 165))
    }

    @Test
    fun `dp truncates in float`() {
        assertEquals(1245, DisplayLayoutPolicy.dp(1440, 185))
        assertEquals(622, DisplayLayoutPolicy.dp(720, 185))
        assertEquals(879, DisplayLayoutPolicy.dp(737, 134))
    }

    @Test
    fun `narrow portrait ends at 730`() {
        assertEquals(730, DisplayLayoutPolicy.dp(1200, 263))
        assertEquals(Layout.PORTRAIT_NARROW, layout(1200, 1920, 263))
        assertEquals(732, DisplayLayoutPolicy.dp(1200, 262))
        assertEquals(Layout.PORTRAIT, layout(1200, 1920, 262))
    }

    @Test
    fun `the ratio is compared in double`() {
        // 1002 / 600 is 1.67 in double, and a float ratio falls short of it and gives CANONICAL
        assertEquals(Layout.VERTICAL_RAIL, layout(1002, 600, 160))
    }

    @Test
    fun `every density is in exactly one ordered run`() {
        val panels = listOf(
            1440 to 720, 720 to 1440, 1000 to 1000, 3440 to 1440, 1916 to 1080, 1280 to 800, 1336 to 800, 1002 to 600
        )
        for ((cw, ch) in panels) {
            val runs = DisplayLayoutPolicy.runs(cw, ch, 80, 640)
            assertEquals(80, runs.first().low)
            assertEquals(640, runs.last().high)
            for (i in 1 until runs.size) {
                assertEquals(runs[i - 1].high + 1, runs[i].low)
                assertTrue(runs[i - 1].layout != runs[i].layout)
            }
            for (r in runs) {
                for (d in r.low..r.high) assertEquals(r.layout, layout(cw, ch, d))
            }
            val choices = DisplayLayoutPolicy.choices(cw, ch, 640)
            assertEquals(choices.map { it.pane }.distinct(), choices.map { it.pane })
            for (c in choices) {
                for (d in c.low..c.high) assertEquals(c.pane, layout(cw, ch, d).pane)
                assertTrue(c.high == 640 || layout(cw, ch, c.high + 1).pane != c.pane)
                assertTrue(runs.none { it.layout.pane == c.pane && it.low > c.high })
            }
        }
    }

    @Test
    fun `the rig units keep their cards`() {
        assertEquals(
            listOf(Choice(Pane.MAP_AND_MEDIA, 80, 185), Choice(Pane.ONE_APP, 186, 256)),
            DisplayLayoutPolicy.choices(1440, 720, 256)
        )
        assertEquals(
            listOf(Choice(Pane.MAP_AND_MEDIA, 80, 165), Choice(Pane.COMPACT, 166, 188), Choice(Pane.ONE_APP, 189, 273)),
            DisplayLayoutPolicy.choices(1280, 800, 273)
        )
        assertEquals(
            listOf(Choice(Pane.MAP_AND_MEDIA, 80, 309), Choice(Pane.ONE_APP, 310, 384)),
            DisplayLayoutPolicy.choices(2400, 1080, 384)
        )
        assertEquals(listOf(Choice(Pane.MAP_OVER_MEDIA, 80, 236)), DisplayLayoutPolicy.choices(1080, 1920, 236))
    }

    @Test
    fun `1336 x 800 has one One app card over its highest run`() {
        // One app also holds at 173 to 175, below Compact; the card is the rail and canonical runs above it
        val runs = DisplayLayoutPolicy.runs(1336, 800, DisplayLayoutPolicy.SLIDER_MIN, 284)
        val below = runs.last { it.layout.pane != Pane.ONE_APP }
        assertEquals(Run(Layout.PORTRAIT_SHORT, 176, 182), below)
        assertEquals(
            Choice(Pane.ONE_APP, below.high + 1, 284),
            DisplayLayoutPolicy.choices(1336, 800, 284).single { it.pane == Pane.ONE_APP }
        )
    }

    @Test
    fun `1002 x 600 offers choices that stay inside the clamp`() {
        val choices = DisplayLayoutPolicy.choices(1002, 600, 213)
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.all { it.high <= 213 && it.low >= DisplayLayoutPolicy.SLIDER_MIN })
    }

    @Test
    fun `a stricter widescreen flag moves only the widescreen edge`() {
        val t = Thresholds.DEFAULT.copy(widescreenW = 1280)
        assertEquals(Layout.WIDESCREEN, DisplayLayoutPolicy.layoutOf(1440, 720, 180, t))
        assertEquals(Layout.VERTICAL_RAIL, DisplayLayoutPolicy.layoutOf(1440, 720, 181, t))
        assertEquals(Layout.VERTICAL_RAIL, DisplayLayoutPolicy.layoutOf(1440, 720, 186, t))
        assertEquals(Layout.CANONICAL, DisplayLayoutPolicy.layoutOf(1920, 1080, 350, t))
    }

    @Test
    fun `size steps drop what leaves the choice`() {
        assertEquals(listOf(188), DisplayLayoutPolicy.sizeSteps(Choice(Pane.COMPACT, 166, 188)))
        assertEquals(listOf(185, 157, 129), DisplayLayoutPolicy.sizeSteps(Choice(Pane.MAP_AND_MEDIA, 80, 185)))
        assertEquals(listOf(256, 217), DisplayLayoutPolicy.sizeSteps(Choice(Pane.ONE_APP, 186, 256)))
        assertEquals(listOf(1), DisplayLayoutPolicy.sizeSteps(Choice(Pane.ONE_APP, 1, 1)))
        assertEquals(256, DisplayLayoutPolicy.autoDensity(Choice(Pane.ONE_APP, 186, 256)))
    }

    @Test
    fun `nameFor takes the nearest step and ties go to the larger`() {
        // steps 185, 157, 129: 171 is 14 from 185 and 14 from 157
        assertEquals(0, DisplayLayoutPolicy.nameFor(1440, 720, 171, 256).stepIndex)
        assertEquals(1, DisplayLayoutPolicy.nameFor(1440, 720, 160, 256).stepIndex)
        assertEquals(2, DisplayLayoutPolicy.nameFor(1440, 720, 100, 256).stepIndex)
        assertEquals(Pane.MAP_AND_MEDIA, DisplayLayoutPolicy.nameFor(1440, 720, 100, 256).pane)
        // the only step of a one-step choice
        assertEquals(0, DisplayLayoutPolicy.nameFor(1280, 800, 170, 284).stepIndex)
        assertEquals(Pane.COMPACT, DisplayLayoutPolicy.nameFor(1280, 800, 170, 284).pane)
    }

    @Test
    fun `a density in a pane the clamp cuts off has no step`() {
        val name = DisplayLayoutPolicy.nameFor(1440, 720, 300, 150)
        assertEquals(Layout.HORIZONTAL_RAIL, name.layout)
        assertNull(name.stepIndex)
        assertEquals(0, DisplayLayoutPolicy.nameFor(1440, 720, 300, 256).stepIndex)
    }

    @Test
    fun `D-HU offers two choices and none past the clamp`() {
        assertEquals(
            listOf(Choice(Pane.MAP_AND_MEDIA, 80, 185), Choice(Pane.ONE_APP, 186, 256)),
            DisplayLayoutPolicy.choices(1440, 720, 256)
        )
    }

    @Test
    fun `the name follows the content area`() {
        val a = DisplayLayoutPolicy.nameFor(1440, 720, 185, 256)
        val b = DisplayLayoutPolicy.nameFor(1280, 800, 185, 284)
        assertEquals(Pane.MAP_AND_MEDIA, a.pane)
        assertEquals(Pane.COMPACT, b.pane)
    }

    @Test
    fun `portrait offers one choice and the slider stops at the cap`() {
        for ((w, h, clamp) in listOf(
            Triple(1080, 1920, 236), Triple(800, 1280, 175), Triple(720, 1280, 157), Triple(1200, 1920, 262)
        )) {
            val choices = DisplayLayoutPolicy.choices(w, h, clamp)
            assertEquals("$w x $h", 1, choices.size)
            assertEquals(Pane.MAP_OVER_MEDIA, choices[0].pane)
            assertEquals(clamp, choices[0].high)
        }
        assertEquals(236, DisplayLayoutPolicy.sliderMax(true, 236))
        assertEquals(DisplayLayoutPolicy.SLIDER_MAX, DisplayLayoutPolicy.sliderMax(false, 236))
    }

    @Test
    fun `the preselect is the widest picture, else the Automatic pane`() {
        assertEquals(Pane.MAP_AND_MEDIA, DisplayLayoutPolicy.initialPane(1440, 720, 256, 0))
        assertEquals(Pane.MAP_OVER_MEDIA, DisplayLayoutPolicy.initialPane(1080, 1920, 236, 0))
        // a square screen reaches no widescreen: Automatic at 500 dpi is a canonical layout
        val auto = layout(1000, 1000, 500).pane
        assertTrue(DisplayLayoutPolicy.choices(1000, 1000, 640).none { it.pane == Pane.MAP_AND_MEDIA })
        assertEquals(auto, DisplayLayoutPolicy.initialPane(1000, 1000, 640, 500))
    }

    private val all = Pane.values().toList()

    @Test
    fun `Auto writes zero and the pane`() {
        val auto = Pick.Auto(Pane.ONE_APP)
        assertEquals(Writes(0, "ONE_APP"), DisplayLayoutPolicy.writes(auto, true, false, 185, "", all))
        assertEquals(Writes(null, "ONE_APP"), DisplayLayoutPolicy.writes(auto, true, false, 0, "MAP_AND_MEDIA", all))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(auto, true, false, 0, "ONE_APP", all))
    }

    @Test
    fun `Manual writes the number and leaves the layout key`() {
        assertEquals(Writes(157, null), DisplayLayoutPolicy.writes(Pick.Manual(157), true, false, 0, "ONE_APP", all))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(Pick.Manual(157), true, false, 157, "", all))
    }

    @Test
    fun `an untouched pick writes nothing unless Next confirms a preselected Auto`() {
        val auto = Pick.Auto(Pane.MAP_AND_MEDIA)
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(auto, false, false, 0, "", all))
        assertEquals(Writes(null, "MAP_AND_MEDIA"), DisplayLayoutPolicy.writes(auto, false, true, 0, "", all))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(auto, false, true, 0, "ONE_APP", all))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(auto, false, true, 185, "", all))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(Pick.Manual(185), false, true, 185, "", all))
    }

    @Test
    fun `Next replaces a stored pane the screen cannot reach`() {
        val reachable = DisplayLayoutPolicy.choices(1440, 720, 256).map { it.pane }
        val auto = Pick.Auto(Pane.MAP_AND_MEDIA)
        assertEquals(Writes(null, "MAP_AND_MEDIA"), DisplayLayoutPolicy.writes(auto, false, true, 0, "COMPACT", reachable))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(auto, false, true, 0, "ONE_APP", reachable))
        assertEquals(Writes(null, null), DisplayLayoutPolicy.writes(auto, false, false, 0, "COMPACT", reachable))
    }

    @Test
    fun `keepLayout moves a fallback density back across an edge`() {
        assertEquals(Layout.VERTICAL_RAIL, layout(1277, 720, 165))
        assertEquals(164, DisplayLayoutPolicy.keepLayout(165, Layout.WIDESCREEN, 1277, 720))
        assertEquals(247, DisplayLayoutPolicy.keepLayout(247, Layout.WIDESCREEN, 1920, 1080))
        assertEquals(100, DisplayLayoutPolicy.keepLayout(100, Layout.PORTRAIT, 1277, 720))
    }

    @Test
    fun `a stored name outside the panes gives no pane`() {
        assertNull(Pane.fromStored(""))
        assertNull(Pane.fromStored("nonsense"))
        assertNull(Pane.fromStored("PORTRAIT_NARROW"))
        assertNull(Pane.fromStored(null))
        assertEquals(Pane.ONE_APP, Pane.fromStored("ONE_APP"))
    }
}
