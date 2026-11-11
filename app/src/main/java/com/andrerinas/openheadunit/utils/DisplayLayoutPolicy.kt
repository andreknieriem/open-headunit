package com.andrerinas.openheadunit.utils

/**
 * Predicts the layout Android Auto picks from the content area and the density (Gearhead's
 * `oil.y`). Every threshold is a server flag, held here at its default.
 */
object DisplayLayoutPolicy {

    const val SLIDER_MIN = 80
    const val SLIDER_MAX = 640

    data class Thresholds(
        val widescreenW: Int, val widescreenRatio: Double, val portraitRatio: Double,
        val railW: Int, val portraitH: Int, val shortPortraitH: Int, val narrowW: Int, val horizontalRailH: Int
    ) {
        companion object {
            val DEFAULT = Thresholds(1240, 1.67, 1.2, 880, 900, 680, 730, 450)
        }
    }

    enum class Layout(val pane: Pane?) {
        WIDESCREEN(Pane.MAP_AND_MEDIA), PORTRAIT_NARROW(null), PORTRAIT(Pane.MAP_OVER_MEDIA),
        VERTICAL_RAIL(Pane.ONE_APP), PORTRAIT_SHORT(Pane.COMPACT), HORIZONTAL_RAIL(Pane.ONE_APP),
        CANONICAL(Pane.ONE_APP)
    }

    /** The layouts the user sees by name; the rail variants and CANONICAL share one. */
    enum class Pane {
        MAP_AND_MEDIA, ONE_APP, COMPACT, MAP_OVER_MEDIA;

        companion object {
            fun fromStored(value: String?): Pane? = values().firstOrNull { it.name == value }
        }
    }

    data class Run(val layout: Layout, val low: Int, val high: Int)
    data class Choice(val pane: Pane, val low: Int, val high: Int)
    data class Name(val layout: Layout, val pane: Pane?, val stepIndex: Int?)

    sealed class Pick {
        data class Auto(val pane: Pane) : Pick()
        data class Manual(val dpi: Int) : Pick()
    }

    data class Writes(val dpi: Int?, val layout: String?)

    /** Gearhead truncates in float, so an integer floor is wrong on some thresholds. */
    fun dp(px: Int, d: Int): Int = (px * (160f / d)).toInt()

    fun layoutOf(cw: Int, ch: Int, d: Int, t: Thresholds = Thresholds.DEFAULT): Layout {
        val w = dp(cw, d)
        val h = dp(ch, d)
        val ratio = w.toDouble() / h
        val tallPortrait = h >= t.portraitH && ratio < t.portraitRatio
        return when {
            w >= t.widescreenW && w > h && (ratio >= t.widescreenRatio || !tallPortrait) -> Layout.WIDESCREEN
            w <= t.narrowW && h >= t.shortPortraitH -> Layout.PORTRAIT_NARROW
            tallPortrait -> Layout.PORTRAIT
            w >= t.railW && ratio >= t.widescreenRatio -> Layout.VERTICAL_RAIL
            h >= t.shortPortraitH -> Layout.PORTRAIT_SHORT
            h < t.horizontalRailH -> Layout.HORIZONTAL_RAIL
            else -> Layout.CANONICAL
        }
    }

    /** The density ranges of one layout each, in density order. */
    fun runs(cw: Int, ch: Int, lo: Int, hi: Int, t: Thresholds = Thresholds.DEFAULT): List<Run> {
        val out = ArrayList<Run>()
        for (d in lo..hi) {
            val layout = layoutOf(cw, ch, d, t)
            val last = out.lastOrNull()
            if (last != null && last.layout == layout) out[out.size - 1] = last.copy(high = d)
            else out.add(Run(layout, d, d))
        }
        return out
    }

    /** One choice per pane the screen reaches, over its highest adjacent runs inside the clamp, by low density. */
    fun choices(cw: Int, ch: Int, clamp: Int, t: Thresholds = Thresholds.DEFAULT): List<Choice> {
        val top = minOf(clamp, SLIDER_MAX)
        if (cw <= 0 || ch <= 0 || top < SLIDER_MIN) return emptyList()
        val byPane = LinkedHashMap<Pane, Choice>()
        var current: Choice? = null
        for (r in runs(cw, ch, SLIDER_MIN, top, t)) {
            val pane = r.layout.pane
            if (pane == null) { current = null; continue }
            val c = current
            current = if (c != null && c.pane == pane) c.copy(high = r.high) else Choice(pane, r.low, r.high)
            byPane[pane] = current
        }
        return byPane.values.sortedBy { it.low }
    }

    /** Large is the top; medium and small sit 15 and 30 percent below it; a step outside the choice is dropped. */
    fun sizeSteps(choice: Choice): List<Int> =
        listOf(choice.high, choice.high * 85 / 100, choice.high * 70 / 100)
            .filter { it >= choice.low }.distinct()

    fun autoDensity(choice: Choice): Int = choice.high

    /** The layout at `d`, and the nearest size step of its pane (ties go to the larger step). */
    fun nameFor(cw: Int, ch: Int, d: Int, clamp: Int, t: Thresholds = Thresholds.DEFAULT): Name {
        val layout = layoutOf(cw, ch, d, t)
        val pane = layout.pane ?: return Name(layout, null, null)
        val choice = choices(cw, ch, clamp, t).firstOrNull { it.pane == pane } ?: return Name(layout, pane, null)
        val steps = sizeSteps(choice)
        var best = 0
        for (i in steps.indices) {
            if (Math.abs(steps[i] - d) < Math.abs(steps[best] - d)) best = i
        }
        return Name(layout, pane, best)
    }

    /** The pane to preselect: the widest picture when reachable, else the pane Automatic gives today. */
    fun initialPane(cw: Int, ch: Int, clamp: Int, automaticDensity: Int, t: Thresholds = Thresholds.DEFAULT): Pane? {
        val all = choices(cw, ch, clamp, t)
        if (all.any { it.pane == Pane.MAP_AND_MEDIA }) return Pane.MAP_AND_MEDIA
        val auto = if (automaticDensity > 0) layoutOf(cw, ch, automaticDensity, t).pane else null
        return if (auto != null && all.any { it.pane == auto }) auto else all.firstOrNull()?.pane
    }

    /** In portrait the slider stops at the touch cap. */
    fun sliderMax(portrait: Boolean, clamp: Int): Int = if (portrait) maxOf(SLIDER_MIN, clamp) else SLIDER_MAX

    /**
     * What a Save writes, each value null when it does not change. A confirmed, untouched Auto
     * writes its preselected pane when no pane in [reachable] is stored.
     */
    fun writes(
        pick: Pick, touched: Boolean, confirmed: Boolean, storedDpi: Int, storedLayout: String, reachable: Collection<Pane>
    ): Writes = when (pick) {
        is Pick.Auto -> when {
            touched -> Writes(
                if (storedDpi != 0) 0 else null,
                if (pick.pane.name != storedLayout) pick.pane.name else null
            )
            confirmed && storedDpi == 0 && Pane.fromStored(storedLayout).let { it == null || it !in reachable } ->
                Writes(null, pick.pane.name)
            else -> Writes(null, null)
        }
        is Pick.Manual -> Writes(if (touched && pick.dpi != storedDpi) pick.dpi else null, null)
    }

    /** The density nearest `guess`, within 2, that keeps `target`; `guess` when none does. */
    fun keepLayout(guess: Int, target: Layout, cw: Int, ch: Int, t: Thresholds = Thresholds.DEFAULT): Int {
        for (d in intArrayOf(guess, guess - 1, guess + 1, guess - 2, guess + 2)) {
            if (d > 0 && layoutOf(cw, ch, d, t) == target) return d
        }
        return guess
    }
}
