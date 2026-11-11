package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.utils.AnnouncedDensityPolicy.Reason
import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncedDensityPolicyTest {

    private val small = DisplaySizePreset.SMALL_7_8

    private fun decide(
        stored: Int, preset: DisplaySizePreset, visibleW: Int, visibleH: Int,
        parE4: Int = ProjectionGeometryPolicy.SQUARE_PIXELS_E4, floorOverride: Boolean = false,
        panelW: Int = 0, panelH: Int = 0, autoLayout: DisplayLayoutPolicy.Pane? = null,
        t: DisplayLayoutPolicy.Thresholds = DisplayLayoutPolicy.Thresholds.DEFAULT
    ) = AnnouncedDensityPolicy.decide(
        stored, preset, visibleW, visibleH, parE4, floorOverride, panelW, panelH, autoLayout, t
    )

    @Test
    fun `the floor's maximum density per codec size`() {
        assertEquals(170, AnnouncedDensityPolicy.huigMaxDpi(800, 480))
        assertEquals(256, AnnouncedDensityPolicy.huigMaxDpi(1280, 720))
        assertEquals(384, AnnouncedDensityPolicy.huigMaxDpi(1920, 1080))
    }

    @Test
    fun `171 at 800x480 is announced as 170`() {
        val d = decide(171, small, 800, 480)
        assertEquals(170, d.announced)
        assertEquals(171, d.requested)
        assertEquals(Reason.HEIGHT_FLOOR, d.reason)
    }

    @Test
    fun `175 is clamped and 160 is kept at 800x480`() {
        assertEquals(170, decide(175, small, 800, 480).announced)
        val kept = decide(160, small, 800, 480)
        assertEquals(160, kept.announced)
        assertEquals(Reason.AS_STORED, kept.reason)
    }

    @Test
    fun `a width bound canvas names the width floor`() {
        val d = decide(230, small, 1000, 700)
        assertEquals(213, d.announced)
        assertEquals(Reason.WIDTH_FLOOR, d.reason)
    }

    @Test
    fun `values that meet the floor at 720p stay`() {
        for (v in intArrayOf(215, 240)) {
            val d = decide(v, small, 1280, 720)
            assertEquals(v, d.announced)
            assertEquals(Reason.AS_STORED, d.reason)
        }
    }

    @Test
    fun `automatic is codec based`() {
        val d = decide(0, small, 800, 480)
        assertEquals(150, d.announced)
        assertEquals(Reason.AUTOMATIC, d.reason)
        assertEquals(113, decide(0, DisplaySizePreset.STANDARD_9_10, 800, 480).announced)
    }

    /** Automatic for a landscape panel, with the geometry the callers derive. */
    private fun automatic(panelW: Int, panelH: Int, resW: Int, resH: Int, preset: DisplaySizePreset): AnnouncedDensityPolicy.Decision {
        val mode = Settings.VideoFitMode.FILL
        val (w, h) = AnnouncedDensityPolicy.visibleSize(mode, panelW, panelH, resW, resH)
        val par = AnnouncedDensityPolicy.pixelAspectE4(mode, panelW, panelH, resW, resH, 0)
        return decide(0, preset, w, h, par, panelW = panelW, panelH = panelH)
    }

    @Test
    fun `a phone-size 1080p panel grows only to the widescreen line it already had`() {
        val d = automatic(2400, 1080, 1920, 1080, DisplaySizePreset.PHONE_4_6)
        assertEquals(309, d.announced)
        assertEquals(Reason.WIDESCREEN_KEPT, d.reason)
        assertEquals(
            "309 (dp canvas 1242x559, requested automatic, panel-based, held at the 1240 dp widescreen line)",
            AnnouncedDensityPolicy.describe(d, 1920, 1080, AnnouncedDensityPolicy.pixelAspectE4(Settings.VideoFitMode.FILL, 2400, 1080, 1920, 1080, 0))
        )
    }

    @Test
    fun `a 6 inch 720p head unit moves only to its height floor`() {
        val d = automatic(1440, 720, 1280, 720, DisplaySizePreset.PHONE_4_6)
        assertEquals(256, d.announced)
        assertEquals(Reason.PANEL, d.reason)
    }

    @Test
    fun `larger panels keep the codec-based value`() {
        assertEquals(Reason.AUTOMATIC, automatic(1280, 800, 1920, 1080, DisplaySizePreset.SMALL_7_8).reason)
        assertEquals(240, automatic(1280, 800, 1920, 1080, DisplaySizePreset.SMALL_7_8).announced)
        assertEquals(144, automatic(1024, 600, 1280, 720, DisplaySizePreset.STANDARD_9_10).announced)
    }

    @Test
    fun `the widescreen hint reads the stretched canvas`() {
        assertEquals(309, AnnouncedDensityPolicy.widescreenHintDpi(2400, 1080))
    }

    @Test
    fun `a wide 10 inch panel gets a dp of 1 160th of an inch`() {
        val d = automatic(1920, 720, 1280, 720, DisplaySizePreset.STANDARD_9_10)
        assertEquals(205, d.announced)
        assertEquals(Reason.PANEL, d.reason)
    }

    @Test
    fun `an unknown panel or a stored value ignores the panel size`() {
        assertEquals(Reason.AUTOMATIC, decide(0, DisplaySizePreset.PHONE_4_6, 1920, 1080).reason)
        assertEquals(
            240, decide(240, DisplaySizePreset.PHONE_4_6, 1920, 1080, panelW = 2400, panelH = 1080).announced
        )
    }

    @Test
    fun `portrait is clamped to the narrow layout's width`() {
        val d = decide(240, small, 480, 800)
        assertEquals(105, d.announced)
        assertEquals(Reason.PORTRAIT_FLOOR, d.reason)
    }

    @Test
    fun `the rig's portrait edge on an 800 px panel with a 720 px codec`() {
        // Measured: Maps took touch at 175 (phone w731) and none at 176 (w727) with PAR 11111.
        assertEquals(800 to 1280, AnnouncedDensityPolicy.phoneCanvas(720, 1280, 11111))
        val kept = decide(175, small, 720, 1280, 11111)
        assertEquals(175, kept.announced)
        assertEquals(Reason.PORTRAIT, kept.reason)
        for (v in intArrayOf(176, 190, 198)) {
            val d = decide(v, small, 720, 1280, 11111)
            assertEquals(175, d.announced)
            assertEquals(Reason.PORTRAIT_FLOOR, d.reason)
        }
    }

    @Test
    fun `square pixels lower the portrait edge`() {
        // Measured: 175 failed and 157 worked with PAR 10001 (phone w658 and w734).
        assertEquals(157, decide(175, small, 720, 1280, 10001).announced)
        assertEquals(157, decide(157, small, 720, 1280, 10001).announced)
    }

    @Test
    fun `automatic portrait stays under the edge`() {
        val d = decide(0, small, 720, 1280)
        assertEquals(157, d.announced)
        assertEquals(Reason.PORTRAIT, d.reason)
    }

    @Test
    fun `the portrait boundary is exact`() {
        for (w in listOf(720, 800, 1080)) {
            val n = AnnouncedDensityPolicy.portraitMaxDpi(w)
            assertTrue("$w at $n", AnnouncedDensityPolicy.canvasDp(w, n) >= 731)
            assertTrue("$w at ${n + 1}", AnnouncedDensityPolicy.canvasDp(w, n + 1) < 731)
        }
    }

    @Test
    fun `the phone canvas grows on the stretched axis only`() {
        assertEquals(1280 to 720, AnnouncedDensityPolicy.phoneCanvas(1280, 720, 10000))
        assertEquals(1280 to 720, AnnouncedDensityPolicy.phoneCanvas(1280, 720, 0))
        // Measured in landscape on the same rig: contentArea 1280x800 for a 1280x720 codec.
        assertEquals(1280 to 800, AnnouncedDensityPolicy.phoneCanvas(1280, 720, 9000))
    }

    @Test
    fun `the preview aspect ratio follows the manual override`() {
        assertEquals(10001, AnnouncedDensityPolicy.pixelAspectE4(Settings.VideoFitMode.FILL, 800, 1280, 1280, 720, 10001))
        val derived = AnnouncedDensityPolicy.pixelAspectE4(Settings.VideoFitMode.FILL, 800, 1280, 1280, 720, 10000)
        assertEquals(
            ProjectionGeometryPolicy.pixelAspectRatioE4(Settings.VideoFitMode.FILL, 800, 1280, 720, 1280, 0, 0), derived
        )
    }

    @Test
    fun `unknown geometry keeps the stored value or 160`() {
        assertEquals(171, decide(171, small, 0, 0).announced)
        assertEquals(160, decide(0, small, 0, 0).announced)
    }

    @Test
    fun `margins shrink the visible area the floor is checked on`() {
        assertEquals(213, decide(213, small, 1024, 600).announced)
        assertEquals(213, decide(215, small, 1024, 600).announced)
    }

    @Test
    fun `automatic keeps the canvas at or above the floor for every size`() {
        for (r in Settings.Resolution.values()) {
            if (r.width <= r.height) continue
            for (p in DisplaySizePreset.values()) {
                val dpi = decide(0, p, r.width, r.height).announced
                assertTrue("$p ${r.resName} -> $dpi", AnnouncedDensityPolicy.canvasDp(r.width, dpi) >= 748)
                assertTrue("$p ${r.resName} -> $dpi", AnnouncedDensityPolicy.canvasDp(r.height, dpi) >= 450)
            }
        }
    }

    @Test
    fun `the fallback density scales by the width ratio`() {
        assertEquals(160, AnnouncedDensityPolicy.fallbackDensity(240, 1920, 1280))
        assertEquals(120, AnnouncedDensityPolicy.fallbackDensity(240, 2560, 1280))
        assertEquals(80, AnnouncedDensityPolicy.fallbackDensity(240, 3840, 1280))
        assertEquals(240, AnnouncedDensityPolicy.fallbackDensity(240, 0, 1280))
    }

    @Test
    fun `the fallback keeps the primary dp canvas`() {
        val primary = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
        val fb = VideoFallbackPolicy.fallbackFor(primary)!!
        val (pw, ph) = VideoFallbackPolicy.sizeOf(primary)
        val (fw, fh) = VideoFallbackPolicy.sizeOf(fb)
        val m = VideoFallbackPolicy.scaledMargins(pw, ph, 120, 60, fb)
        val dpi = 240
        val fdpi = AnnouncedDensityPolicy.fallbackDensity(dpi, pw, fw)
        assertTrue(Math.abs(AnnouncedDensityPolicy.canvasDp(pw - 120, dpi) - AnnouncedDensityPolicy.canvasDp(fw - m.width, fdpi)) <= 1)
        assertTrue(Math.abs(AnnouncedDensityPolicy.canvasDp(ph - 60, dpi) - AnnouncedDensityPolicy.canvasDp(fh - m.height, fdpi)) <= 1)
    }

    @Test
    fun `describe pins the rig's grep targets`() {
        assertEquals(
            "170 (dp canvas 752x451, requested 171, clamped to the 450 dp height floor)",
            AnnouncedDensityPolicy.describe(decide(171, small, 800, 480), 800, 480)
        )
        assertEquals(
            "150 (dp canvas 853x512, requested automatic, codec-based)",
            AnnouncedDensityPolicy.describe(decide(0, small, 800, 480), 800, 480)
        )
        assertEquals(
            "175 (dp canvas 731x1170, requested 198, clamped to the 730 dp portrait width floor)",
            AnnouncedDensityPolicy.describe(decide(198, small, 720, 1280, 11111), 720, 1280, 11111)
        )
    }

    @Test
    fun `the override never lifts the portrait touch cap`() {
        val d = decide(198, small, 720, 1280, 11111, floorOverride = true)
        assertEquals(AnnouncedDensityPolicy.Decision(175, 198, Reason.PORTRAIT_FLOOR), d)
        assertEquals(
            "175 (dp canvas 731x1170, requested 198, clamped to the 730 dp portrait width floor)",
            AnnouncedDensityPolicy.describe(d, 720, 1280, 11111)
        )
    }

    @Test
    fun `the override leaves a value under the floor and Automatic alone`() {
        assertEquals(
            AnnouncedDensityPolicy.Decision(160, 160, Reason.PORTRAIT),
            decide(160, small, 720, 1280, 11111, floorOverride = true)
        )
        assertEquals(
            decide(0, small, 720, 1280, 11111),
            decide(0, small, 720, 1280, 11111, floorOverride = true)
        )
    }

    @Test
    fun `the override sends a stored landscape value above the HUIG floor`() {
        assertEquals(
            AnnouncedDensityPolicy.Decision(171, 171, Reason.FLOOR_OVERRIDE),
            decide(171, small, 800, 480, floorOverride = true)
        )
        assertEquals(
            "171 (dp canvas 748x449, requested 171, the 748x450 dp HUIG floor overridden)",
            AnnouncedDensityPolicy.describe(decide(171, small, 800, 480, floorOverride = true), 800, 480)
        )
        assertEquals(
            decide(0, small, 800, 480),
            decide(0, small, 800, 480, floorOverride = true)
        )
    }

    @Test
    fun `the widescreen maximum per codec width`() {
        assertEquals(103, AnnouncedDensityPolicy.widescreenMaxDpi(800))
        assertEquals(165, AnnouncedDensityPolicy.widescreenMaxDpi(1280))
        assertEquals(247, AnnouncedDensityPolicy.widescreenMaxDpi(1920))
        assertEquals(0, AnnouncedDensityPolicy.widescreenMaxDpi(0))
    }

    @Test
    fun `the widescreen boundary is exact`() {
        for (w in listOf(800, 1280, 1920)) {
            val n = AnnouncedDensityPolicy.widescreenMaxDpi(w)
            assertTrue("$w at $n", AnnouncedDensityPolicy.canvasDp(w, n) >= 1240)
            assertTrue("$w at ${n + 1}", AnnouncedDensityPolicy.canvasDp(w, n + 1) < 1240)
        }
    }

    @Test
    fun `the widescreen line never names a value the clamp lowers`() {
        for (r in Settings.Resolution.values().filter { it != Settings.Resolution.AUTO }) {
            val n = AnnouncedDensityPolicy.widescreenMaxDpi(r.width)
            assertTrue("${r.resName}", n <= AnnouncedDensityPolicy.huigMaxDpi(r.width, r.height))
            assertEquals(Reason.AS_STORED, decide(n, small, r.width, r.height).reason)
        }
    }

    @Test
    fun `the widescreen hint skips a portrait or unknown codec`() {
        assertEquals(103, AnnouncedDensityPolicy.widescreenHintDpi(800, 480))
        assertEquals(0, AnnouncedDensityPolicy.widescreenHintDpi(480, 800))
        assertEquals(0, AnnouncedDensityPolicy.widescreenHintDpi(0, 0))
    }

    @Test
    fun `a resolution that matches the panel is fully visible`() {
        assertEquals(800 to 480, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 800, 480, 800, 480))
    }

    @Test
    fun `a resolution larger than a small panel loses its margins`() {
        assertEquals(1024 to 600, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 1024, 600, 1280, 720))
        assertEquals(1024 to 720, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.CONTAIN, 1024, 768, 1280, 720))
    }

    @Test
    fun `the preview of a margined resolution sends what connect sends`() {
        val (w, h) = AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.CONTAIN, 1024, 768, 1280, 720)
        assertTrue(decide(0, small, w, h).announced < decide(0, small, 1280, 720).announced)
    }

    @Test
    fun `the resolution turns to a portrait panel`() {
        assertEquals(480 to 800, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 480, 800, 800, 480))
    }

    @Test
    fun `an unknown panel leaves the resolution whole`() {
        assertEquals(800 to 480, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 0, 0, 800, 480))
    }

    @Test
    fun `a panel held in portrait previews a landscape projection`() {
        assertEquals(1440 to 720, AnnouncedDensityPolicy.orientPanel(720, 1440, portrait = false))
        assertEquals(720 to 1440, AnnouncedDensityPolicy.orientPanel(1440, 720, portrait = true))
        val (pw, ph) = AnnouncedDensityPolicy.orientPanel(720, 1440, portrait = false)
        assertEquals(800 to 480, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, pw, ph, 800, 480))
    }

    @Test
    fun `only an explicit portrait setting projects in portrait`() {
        for (o in Settings.ScreenOrientation.values()) {
            val portrait = o == Settings.ScreenOrientation.PORTRAIT || o == Settings.ScreenOrientation.PORTRAIT_REVERSE
            assertEquals(o.name, portrait, AnnouncedDensityPolicy.projectsPortrait(o))
        }
    }

    @Test
    fun `Auto inside a picked layout sends the top of that layout`() {
        val d = decide(0, small, 1440, 720, autoLayout = DisplayLayoutPolicy.Pane.MAP_AND_MEDIA)
        assertEquals(AnnouncedDensityPolicy.Decision(185, 0, Reason.AUTO_LAYOUT), d)
        assertEquals(
            "185 (dp canvas 1245x622, requested automatic, top of the WIDESCREEN layout)",
            AnnouncedDensityPolicy.describe(d, 1440, 720)
        )
        assertEquals(
            "WIDESCREEN (expected from defaults, dp canvas 1245x622)",
            AnnouncedDensityPolicy.describeLayout(d, 1440, 720)
        )
    }

    @Test
    fun `Auto with no layout or an unreachable one is today's Automatic`() {
        val today = decide(0, small, 1440, 720)
        assertEquals(today, decide(0, small, 1440, 720, autoLayout = DisplayLayoutPolicy.Pane.MAP_OVER_MEDIA))
        assertTrue(today.reason != Reason.AUTO_LAYOUT)
    }

    @Test
    fun `a stored value ignores the picked layout`() {
        assertEquals(
            AnnouncedDensityPolicy.Decision(157, 157, Reason.AS_STORED),
            decide(157, small, 1440, 720, autoLayout = DisplayLayoutPolicy.Pane.ONE_APP)
        )
    }

    @Test
    fun `the override does not lift Auto past the clamp`() {
        val d = decide(0, small, 1440, 720, floorOverride = true, autoLayout = DisplayLayoutPolicy.Pane.ONE_APP)
        assertEquals(AnnouncedDensityPolicy.Decision(256, 0, Reason.AUTO_LAYOUT), d)
    }

    @Test
    fun `the floor reads a height the phone stretches`() {
        assertEquals(273, AnnouncedDensityPolicy.clampDpi(1280, 720, 9000))
        assertEquals(256, AnnouncedDensityPolicy.clampDpi(1280, 720, 11250))
        val d = decide(0, small, 1280, 720, parE4 = 9000, autoLayout = DisplayLayoutPolicy.Pane.ONE_APP)
        assertEquals(AnnouncedDensityPolicy.Decision(273, 0, Reason.AUTO_LAYOUT), d)
        assertEquals(
            "273 (dp canvas 750x468, requested automatic, top of the CANONICAL layout)",
            AnnouncedDensityPolicy.describe(d, 1280, 720, 9000)
        )
        assertEquals(
            "CANONICAL (expected from defaults, dp canvas 750x468)",
            AnnouncedDensityPolicy.describeLayout(d, 1280, 720, 9000)
        )
    }

    @Test
    fun `a stored step on a stretched height is clamped at the canvas floor`() {
        assertEquals(AnnouncedDensityPolicy.Decision(273, 273, Reason.AS_STORED), decide(273, small, 1280, 720, parE4 = 9000))
        assertEquals(AnnouncedDensityPolicy.Decision(273, 280, Reason.WIDTH_FLOOR), decide(280, small, 1280, 720, parE4 = 9000))
    }

    @Test
    fun `portrait Auto sends the cap the picker names`() {
        assertEquals(
            AnnouncedDensityPolicy.Decision(236, 0, Reason.AUTO_LAYOUT),
            decide(0, small, 1080, 1920, autoLayout = DisplayLayoutPolicy.Pane.MAP_OVER_MEDIA)
        )
        assertEquals(Reason.PORTRAIT, decide(0, small, 1080, 1920).reason)
    }

    @Test
    fun `the widescreen edge agrees with the layout rule for a stricter flag`() {
        val t = DisplayLayoutPolicy.Thresholds.DEFAULT.copy(widescreenW = 1280)
        for ((w, h) in listOf(2400 to 1080, 1920 to 1080, 1440 to 720)) {
            val top = DisplayLayoutPolicy.runs(w, h, 1, 640, t).last { it.layout == DisplayLayoutPolicy.Layout.WIDESCREEN }.high
            assertEquals("$w x $h", top, AnnouncedDensityPolicy.widescreenHintDpi(w, h, t))
        }
        assertEquals(
            DisplayLayoutPolicy.layoutOf(1920, 1080, 240, t) == DisplayLayoutPolicy.Layout.WIDESCREEN,
            240 <= AnnouncedDensityPolicy.widescreenMaxDpi(1920, t)
        )
    }

    @Test
    fun `no layout threshold is left in the policy`() {
        val src = java.io.File("src/main/java/com/andrerinas/openheadunit/utils/AnnouncedDensityPolicy.kt").readText()
        assertFalse(src.contains("1240"))
        assertFalse(Regex("\\b730\\b").containsMatchIn(src))
    }

    @Test
    fun `the phone canvas keeps a width a float product rounds down`() {
        assertEquals(200, AnnouncedDensityPolicy.phoneCanvas(200, 100, 10025).first)
    }

    @Test
    fun `describeLayout and describe print the same dp canvas`() {
        val d = decide(0, small, 720, 1280, 11111)
        val canvas = Regex("dp canvas (\\d+x\\d+)")
        assertEquals(
            canvas.find(AnnouncedDensityPolicy.describe(d, 720, 1280, 11111))!!.groupValues[1],
            canvas.find(AnnouncedDensityPolicy.describeLayout(d, 720, 1280, 11111))!!.groupValues[1]
        )
    }

    @Test
    fun `the fallback keeps the primary layout across a width margin`() {
        val primary = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
        val fb = VideoFallbackPolicy.fallbackFor(primary)!!
        val (pw, ph) = VideoFallbackPolicy.sizeOf(primary)
        val (fw, fh) = VideoFallbackPolicy.sizeOf(fb)
        val m = VideoFallbackPolicy.scaledMargins(pw, ph, 4, 0, fb)
        val guess = AnnouncedDensityPolicy.fallbackDensity(247, pw, fw)
        assertEquals(165, guess)
        val decision = AnnouncedDensityPolicy.Decision(247, 0, Reason.AUTO_LAYOUT)
        val kept = AnnouncedDensityPolicy.fallbackDensityKeepingLayout(
            decision, guess, pw - 4, ph, fw - m.width, fh - m.height, 10000
        )
        assertEquals(164, kept)
        assertEquals(DisplayLayoutPolicy.Layout.WIDESCREEN, DisplayLayoutPolicy.layoutOf(fw - m.width, fh - m.height, kept))
    }

    @Test
    fun `the fallback matches the primary layout at every edge and step`() {
        val sizes = listOf(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
        )
        var cases = 0
        val unmatched = ArrayList<String>()
        for (primary in sizes) {
            val fb = VideoFallbackPolicy.fallbackFor(primary) ?: continue
            val (pw, ph) = VideoFallbackPolicy.sizeOf(primary)
            val (fw, fh) = VideoFallbackPolicy.sizeOf(fb)
            for (margin in 0..600 step 4) {
                val m = VideoFallbackPolicy.scaledMargins(pw, ph, margin, 0, fb)
                val cw = pw - margin
                val clamp = AnnouncedDensityPolicy.huigMaxDpi(cw, ph)
                for (choice in DisplayLayoutPolicy.choices(cw, ph, clamp)) {
                    for (d in DisplayLayoutPolicy.sizeSteps(choice)) {
                        cases++
                        val target = DisplayLayoutPolicy.layoutOf(cw, ph, d)
                        val guess = AnnouncedDensityPolicy.fallbackDensity(d, pw, fw)
                        val got = AnnouncedDensityPolicy.fallbackDensityKeepingLayout(
                            AnnouncedDensityPolicy.Decision(d, 0, Reason.AUTO_LAYOUT),
                            guess, cw, ph, fw - m.width, fh - m.height, 10000
                        )
                        if (DisplayLayoutPolicy.layoutOf(fw - m.width, fh - m.height, got) != target) {
                            unmatched.add("${pw}x$ph m$margin d$d -> $got")
                            assertEquals("kept the guess", guess, got)
                        }
                    }
                }
            }
        }
        assertTrue("cases $cases", cases > 1000)
        assertTrue("unmatched $unmatched", unmatched.size <= 4)
    }
}
