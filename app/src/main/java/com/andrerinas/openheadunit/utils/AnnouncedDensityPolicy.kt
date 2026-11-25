package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Thresholds
import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset

/**
 * Android Auto's design guide says a head unit MUST offer at least 748x450 dp; a canvas below it
 * gets a reduced layout. This decides the density that goes on the wire.
 */
object AnnouncedDensityPolicy {

    const val HUIG_MIN_WIDTH_DP = 748
    const val HUIG_MIN_HEIGHT_DP = 450
    private const val FALLBACK_DPI = 160

    enum class Reason { AS_STORED, AUTOMATIC, PANEL, WIDESCREEN_KEPT, WIDTH_FLOOR, HEIGHT_FLOOR, PORTRAIT, PORTRAIT_FLOOR, FLOOR_OVERRIDE, AUTO_LAYOUT }

    data class Decision(val announced: Int, val requested: Int, val reason: Reason)

    /** The highest density that keeps the visible area at or above the floor. */
    fun huigMaxDpi(w: Int, h: Int): Int = minOf(w * 160 / HUIG_MIN_WIDTH_DP, h * 160 / HUIG_MIN_HEIGHT_DP)

    /** The highest density that keeps a codec of this width at or above the widescreen breakpoint. */
    fun widescreenMaxDpi(codecWidth: Int, t: Thresholds = Thresholds.DEFAULT): Int {
        if (codecWidth <= 0) return 0
        var d = codecWidth * 160 / t.widescreenW + 2
        while (d > 0 && DisplayLayoutPolicy.dp(codecWidth, d) < t.widescreenW) d--
        return d
    }

    /** The top of the WIDESCREEN run for the canvas the phone lays out; 0 when no density gives it. */
    fun widescreenHintDpi(canvasWidth: Int, canvasHeight: Int, t: Thresholds = Thresholds.DEFAULT): Int {
        if (canvasWidth <= 0 || canvasHeight <= 0) return 0
        for (d in DisplayLayoutPolicy.SLIDER_MAX downTo 1) {
            if (DisplayLayoutPolicy.layoutOf(canvasWidth, canvasHeight, d, t) == DisplayLayoutPolicy.Layout.WIDESCREEN) return d
        }
        return 0
    }

    /** The highest density that keeps a portrait canvas this wide (phone pixels) out of the narrow layout. */
    fun portraitMaxDpi(phoneWidth: Int, t: Thresholds = Thresholds.DEFAULT): Int {
        if (phoneWidth <= 0) return 1
        var d = phoneWidth * 160 / (t.narrowW + 1) + 2
        while (d > 1 && DisplayLayoutPolicy.dp(phoneWidth, d) <= t.narrowW) d--
        return d
    }

    /**
     * The highest density a picker or Automatic may use: the touch cap in portrait, the HUIG floor otherwise.
     * Both read the canvas the phone lays out, so a stretched axis counts at its stretched size.
     */
    fun clampDpi(visibleW: Int, visibleH: Int, parE4: Int, t: Thresholds = Thresholds.DEFAULT): Int {
        val (cw, ch) = phoneCanvas(visibleW, visibleH, parE4)
        return if (visibleH > visibleW) portraitMaxDpi(cw, t) else huigMaxDpi(cw, ch)
    }

    /** The canvas the phone lays out: it stretches the codec by the pixel aspect ratio and never shrinks it. */
    fun phoneCanvas(w: Int, h: Int, parE4: Int): Pair<Int, Int> = when {
        parE4 <= 0 || parE4 == ProjectionGeometryPolicy.SQUARE_PIXELS_E4 -> w to h
        parE4 > ProjectionGeometryPolicy.SQUARE_PIXELS_E4 -> Math.round(w * (parE4 * 1.0E-4f)) to h
        else -> w to Math.round(h / (parE4 * 1.0E-4f))
    }

    /** The pixel aspect ratio connect announces for this geometry, with HeadUnitScreenConfig's rules. */
    fun pixelAspectE4(mode: Settings.VideoFitMode, panelW: Int, panelH: Int, resW: Int, resH: Int, manualE4: Int): Int {
        if (manualE4 > 0 && manualE4 != ProjectionGeometryPolicy.SQUARE_PIXELS_E4) return manualE4
        val (w, h) = if ((panelH > panelW) == (resH > resW)) resW to resH else resH to resW
        val (vw, vh) = visibleSize(mode, panelW, panelH, resW, resH)
        return ProjectionGeometryPolicy.pixelAspectRatioE4(mode, panelW, panelH, w, h, w - vw, h - vh)
    }

    /**
     * The density that makes one dp 1/160 inch on this panel: its pixels per inch at the preset's
     * diagonal, per row of the canvas the phone lays out. 0 when the panel is unknown.
     */
    fun panelDpi(preset: DisplaySizePreset, panelW: Int, panelH: Int, visibleW: Int, visibleH: Int, parE4: Int): Int {
        if (panelW <= 0 || panelH <= 0 || visibleW <= 0 || visibleH <= 0) return 0
        val ppi = Math.hypot(panelW.toDouble(), panelH.toDouble()) / preset.diagonalInch
        val shownRows = minOf(panelH.toDouble(), visibleH * minOf(panelW.toDouble() / visibleW, panelH.toDouble() / visibleH))
        return Math.round(phoneCanvas(visibleW, visibleH, parE4).second * ppi / shownRows).toInt()
    }

    fun canvasDp(px: Int, dpi: Int): Int = if (dpi <= 0) 0 else DisplayLayoutPolicy.dp(px, dpi)

    /** The panel as the projection holds it: a settings screen can be held the other way round. */
    fun orientPanel(w: Int, h: Int, portrait: Boolean): Pair<Int, Int> = if ((h > w) == portrait) w to h else h to w

    /** Only an explicit portrait setting projects in portrait; every other choice previews landscape. */
    fun projectsPortrait(o: Settings.ScreenOrientation): Boolean =
        o == Settings.ScreenOrientation.PORTRAIT || o == Settings.ScreenOrientation.PORTRAIT_REVERSE

    /**
     * The area Android Auto draws in for a resolution on this panel: turned to the panel's
     * orientation, less the margins connect announces, with the same math as HeadUnitScreenConfig.
     */
    fun visibleSize(mode: Settings.VideoFitMode, panelW: Int, panelH: Int, resW: Int, resH: Int): Pair<Int, Int> {
        val (w, h) = if ((panelH > panelW) == (resH > resW)) resW to resH else resH to resW
        if (panelW <= 0 || panelH <= 0 || w <= 0 || h <= 0) return w to h
        if (MarginStrategyPolicy.select(mode, panelW, panelH, w, h) == MarginStrategyPolicy.Strategy.PAR) return w to h
        val scale = ProjectionGeometryPolicy.fit(panelW, panelH, w, h).scaleFactor
        return (w - ProjectionGeometryPolicy.widthMargin(w, panelW, scale)) to
            (h - ProjectionGeometryPolicy.heightMargin(h, panelH, scale))
    }

    fun decide(
        stored: Int, preset: DisplaySizePreset, visibleW: Int, visibleH: Int,
        parE4: Int = ProjectionGeometryPolicy.SQUARE_PIXELS_E4, floorOverride: Boolean = false,
        panelW: Int = 0, panelH: Int = 0, autoLayout: DisplayLayoutPolicy.Pane?, t: Thresholds = Thresholds.DEFAULT
    ): Decision {
        if (visibleW <= 0 || visibleH <= 0) {
            return if (stored > 0) Decision(stored, stored, Reason.AS_STORED)
            else Decision(FALLBACK_DPI, 0, Reason.AUTOMATIC)
        }
        if (stored <= 0 && autoLayout != null) {
            val (cw, ch) = phoneCanvas(visibleW, visibleH, parE4)
            val choice = DisplayLayoutPolicy.choices(cw, ch, clampDpi(visibleW, visibleH, parE4, t), t)
                .firstOrNull { it.pane == autoLayout }
            if (choice != null) return Decision(DisplayLayoutPolicy.autoDensity(choice), 0, Reason.AUTO_LAYOUT)
        }
        if (visibleH > visibleW) {
            val max = portraitMaxDpi(phoneCanvas(visibleW, visibleH, parE4).first, t)
            if (stored <= 0) {
                val auto = OnboardingDisplayPolicy.recommendedDpi(preset, visibleW, visibleH, true)
                return Decision(minOf(auto, max), 0, Reason.PORTRAIT)
            }
            // Above the cap Maps takes no touch, so "Use anyway" never lifts it in portrait.
            return if (stored <= max) Decision(stored, stored, Reason.PORTRAIT) else Decision(max, stored, Reason.PORTRAIT_FLOOR)
        }
        if (stored <= 0) {
            // The codec-based value is too small on a dense panel, so Automatic never goes below the panel's own,
            // but it never costs a canvas the widescreen layout it had either.
            val codec = OnboardingDisplayPolicy.recommendedDpi(preset, visibleW, visibleH, false)
            val panel = minOf(panelDpi(preset, panelW, panelH, visibleW, visibleH, parE4), huigMaxDpi(visibleW, visibleH))
            if (panel <= codec) return Decision(codec, 0, Reason.AUTOMATIC)
            val (canvasW, canvasH) = phoneCanvas(visibleW, visibleH, parE4)
            val wide = widescreenHintDpi(canvasW, canvasH, t)
            return if (codec <= wide && panel > wide) Decision(wide, 0, Reason.WIDESCREEN_KEPT)
            else Decision(panel, 0, Reason.PANEL)
        }
        // The floor is in the phone's dp, so a size step the picker offers is never clamped on the wire.
        val (canvasW, canvasH) = phoneCanvas(visibleW, visibleH, parE4)
        val max = huigMaxDpi(canvasW, canvasH)
        if (stored <= max) return Decision(stored, stored, Reason.AS_STORED)
        if (floorOverride) return Decision(stored, stored, Reason.FLOOR_OVERRIDE)
        val heightBound = canvasH * 160 / HUIG_MIN_HEIGHT_DP < canvasW * 160 / HUIG_MIN_WIDTH_DP
        return Decision(max, stored, if (heightBound) Reason.HEIGHT_FLOOR else Reason.WIDTH_FLOOR)
    }

    /** The fallback keeps the primary's dp canvas: its margins scale by the same width ratio. */
    fun fallbackDensity(primaryDensity: Int, primaryWidth: Int, fallbackWidth: Int): Int =
        if (primaryWidth <= 0) primaryDensity
        else Math.round(primaryDensity.toFloat() * fallbackWidth / primaryWidth)

    /** The fallback's density, moved by up to 2 so its canvas lays out in the primary's layout. */
    fun fallbackDensityKeepingLayout(
        decision: Decision, guess: Int, primaryW: Int, primaryH: Int, fallbackW: Int, fallbackH: Int, parE4: Int,
        t: Thresholds = Thresholds.DEFAULT
    ): Int {
        val (pcw, pch) = phoneCanvas(primaryW, primaryH, parE4)
        val (fcw, fch) = phoneCanvas(fallbackW, fallbackH, parE4)
        if (decision.announced <= 0 || pcw <= 0 || pch <= 0 || fcw <= 0 || fch <= 0) return guess
        val target = DisplayLayoutPolicy.layoutOf(pcw, pch, decision.announced, t)
        return DisplayLayoutPolicy.keepLayout(guess, target, fcw, fch, t)
    }

    private fun layoutName(decision: Decision, visibleW: Int, visibleH: Int, parE4: Int, t: Thresholds): String {
        val (w, h) = phoneCanvas(visibleW, visibleH, parE4)
        return DisplayLayoutPolicy.layoutOf(w, h, decision.announced, t).name
    }

    /** The `Layout is:` log text; the rig compares it with the phone's own layout line. */
    fun describeLayout(
        decision: Decision, visibleW: Int, visibleH: Int,
        parE4: Int = ProjectionGeometryPolicy.SQUARE_PIXELS_E4, t: Thresholds = Thresholds.DEFAULT
    ): String {
        val (w, h) = phoneCanvas(visibleW, visibleH, parE4)
        val layout = layoutName(decision, visibleW, visibleH, parE4, t)
        return "$layout (expected from defaults, dp canvas ${canvasDp(w, decision.announced)}x${canvasDp(h, decision.announced)})"
    }

    /** The `Density is:` log text; the rig greps these exact strings. */
    fun describe(
        decision: Decision, visibleW: Int, visibleH: Int,
        parE4: Int = ProjectionGeometryPolicy.SQUARE_PIXELS_E4, t: Thresholds = Thresholds.DEFAULT
    ): String {
        val requested = if (decision.requested > 0) decision.requested.toString() else "automatic"
        val why = when (decision.reason) {
            Reason.AS_STORED -> "within the HUIG floor"
            Reason.AUTOMATIC -> "codec-based"
            Reason.PANEL -> "panel-based"
            Reason.WIDESCREEN_KEPT -> "panel-based, held at the ${t.widescreenW} dp widescreen line"
            Reason.WIDTH_FLOOR -> "clamped to the $HUIG_MIN_WIDTH_DP dp width floor"
            Reason.HEIGHT_FLOOR -> "clamped to the $HUIG_MIN_HEIGHT_DP dp height floor"
            Reason.PORTRAIT -> "portrait, within the ${t.narrowW} dp width floor"
            Reason.PORTRAIT_FLOOR -> "clamped to the ${t.narrowW} dp portrait width floor"
            Reason.FLOOR_OVERRIDE -> "the ${HUIG_MIN_WIDTH_DP}x$HUIG_MIN_HEIGHT_DP dp HUIG floor overridden"
            Reason.AUTO_LAYOUT -> "top of the ${layoutName(decision, visibleW, visibleH, parE4, t)} layout"
        }
        val (w, h) = phoneCanvas(visibleW, visibleH, parE4)
        return "${decision.announced} (dp canvas ${canvasDp(w, decision.announced)}x" +
            "${canvasDp(h, decision.announced)}, requested $requested, $why)"
    }
}
