package com.andrerinas.openheadunit.utils

/**
 * Which rectangle the projection is measured against. The phone is told about this canvas once, at
 * service discovery, so it has to be the one the video is really drawn into: a display-level API
 * can describe neither a window a ROM sidebar has narrowed nor a decoration it reports on the wrong
 * axis.
 */
object ProjectionCanvasPolicy {

    enum class Source {
        /** The projection surface itself, this process. */
        SURFACE,
        /** A laid-out activity window in the current screen mode. */
        WINDOW,
        /** A surface measured by an earlier session under the same settings. */
        CACHE,
        /** Nothing has been measured yet: the display metrics. */
        DISPLAY,
    }

    /** A canvas someone measured, and the settings hash it was measured under. */
    data class Measurement(val width: Int, val height: Int, val hash: Int)

    data class Choice(val width: Int, val height: Int, val source: Source)

    data class Rect(val width: Int, val height: Int)

    /** A cached canvas may exceed the panel it is checked against by this much and still be it. */
    private const val PANEL_SLACK = 1.25f

    /**
     * The rectangle a measured canvas sits in. A measurement is the area inside the insets, so the
     * anchor carries them; freezing an anchor while the insets move subtracts a bar from a
     * rectangle that never contained it, and the canvas is announced short by a bar's width.
     */
    fun anchor(canvasW: Int, canvasH: Int, insetW: Int, insetH: Int): Rect =
        Rect(canvasW + insetW, canvasH + insetH)

    /** The canvas inside an anchor. Insets that would empty it are ignored rather than obeyed. */
    fun canvas(anchorW: Int, anchorH: Int, insetW: Int, insetH: Int): Rect {
        val w = anchorW - insetW
        val h = anchorH - insetH
        return if (w <= 0 || h <= 0) Rect(anchorW, anchorH) else Rect(w, h)
    }

    /**
     * Whether a stored canvas can still belong to this panel. A decoration counted on the wrong
     * axis moves a display reading by tens of px, so the test is a ratio: it has to reject another
     * panel without rejecting a metric that wobbled.
     */
    fun fitsPanel(canvasW: Int, canvasH: Int, panelW: Int, panelH: Int): Boolean {
        if (panelW <= 0 || panelH <= 0) return true
        return canvasW <= panelW * PANEL_SLACK && canvasH <= panelH * PANEL_SLACK
    }

    /** A layout/inset transition retires surfaces measured inside the previous window canvas. */
    fun windowChanged(previous: Measurement?, current: Measurement): Boolean =
        previous != null && previous != current

    private fun usable(m: Measurement?, hash: Int): Boolean =
        m != null && m.width > 0 && m.height > 0 && m.hash == hash

    /**
     * The canvas to anchor on. A measurement only counts for the settings it was taken under, so a
     * screen-mode change drops every reading that described the old window.
     */
    fun choose(
        immersive: Boolean,
        realW: Int,
        realH: Int,
        usableW: Int,
        usableH: Int,
        surface: Measurement?,
        window: Measurement?,
        cached: Measurement?,
        hash: Int,
    ): Choice {
        if (usable(surface, hash)) return Choice(surface!!.width, surface.height, Source.SURFACE)
        if (usable(window, hash)) return Choice(window!!.width, window.height, Source.WINDOW)
        if (usable(cached, hash)) return Choice(cached!!.width, cached.height, Source.CACHE)
        return if (immersive) Choice(realW, realH, Source.DISPLAY) else Choice(usableW, usableH, Source.DISPLAY)
    }
}
