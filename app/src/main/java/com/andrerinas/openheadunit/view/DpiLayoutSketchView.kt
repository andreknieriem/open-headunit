package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Layout

/**
 * Draws a mock-up of one Android Auto layout at the aspect of the dp canvas: the rail, a map with
 * a route, and the media cards. Marks keep their dp size, so a higher density draws them larger.
 * It is a drawing, not a screenshot.
 */
class DpiLayoutSketchView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var layout = Layout.CANONICAL
    private var canvasW = 16
    private var canvasH = 9
    private var s = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    /** A tall canvas shrinks to this height and keeps its aspect; 0 is no limit. */
    var maxHeightPx = 0
        set(value) { field = value; requestLayout() }

    /** [dpW] x [dpH] is the dp canvas the phone lays out at the density being shown. */
    fun setLayout(layout: Layout, dpW: Int, dpH: Int) {
        this.layout = layout
        canvasW = dpW.coerceAtLeast(1)
        canvasH = dpH.coerceAtLeast(1)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var w = MeasureSpec.getSize(widthSpec)
        var h = (w.toLong() * canvasH / canvasW).toInt()
        if (maxHeightPx in 1 until h) {
            h = maxHeightPx
            w = (h.toLong() * canvasW / canvasH).toInt()
        }
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        s = width.toFloat() / canvasW
        val w = canvasW.toFloat()
        val h = canvasH.toFloat()
        val rail = minOf(RAIL_DP, minOf(w, h) * 0.16f)
        box(canvas, 0f, 0f, w, h, COLOR_BACKDROP)
        when (layout) {
            Layout.WIDESCREEN -> {
                // Android Auto's default order: the rail at the side, the map, then the cards.
                rail(canvas, 0f, 0f, rail, h)
                val left = w - GAP - w * DASHBOARD_PART
                val split = h * 0.56f
                map(canvas, rail + GAP, GAP, left - GAP, h - GAP)
                media(canvas, left, GAP, w - GAP, split - GAP / 2)
                note(canvas, left, split + GAP / 2, w - GAP, h - GAP)
            }
            Layout.VERTICAL_RAIL -> {
                rail(canvas, 0f, 0f, rail, h)
                map(canvas, rail + GAP, GAP, w - GAP, h - GAP)
            }
            Layout.HORIZONTAL_RAIL, Layout.CANONICAL, Layout.PORTRAIT_NARROW -> {
                map(canvas, GAP, GAP, w - GAP, h - rail - GAP)
                rail(canvas, 0f, h - rail, w, h)
            }
            Layout.PORTRAIT_SHORT -> {
                val strip = minOf(STRIP_DP, h * 0.14f)
                map(canvas, GAP, GAP, w - GAP, h - rail - strip - GAP * 2)
                media(canvas, GAP, h - rail - strip - GAP, w - GAP, h - rail - GAP)
                rail(canvas, 0f, h - rail, w, h)
            }
            Layout.PORTRAIT -> {
                val split = (h - rail) * 0.62f
                map(canvas, GAP, GAP, w - GAP, split - GAP / 2)
                media(canvas, GAP, split + GAP / 2, w - GAP, h - rail - GAP)
                rail(canvas, 0f, h - rail, w, h)
            }
        }
    }

    private fun box(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, corner: Float = CORNER_DP) {
        if (r <= l || b <= t) return
        paint.color = color
        rect.set(l * s, t * s, r * s, b * s)
        val c = minOf(corner, (r - l) / 2, (b - t) / 2) * s
        canvas.drawRoundRect(rect, c, c, paint)
    }

    private fun dot(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.color = color
        canvas.drawCircle(cx * s, cy * s, radius * s, paint)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width * s
        stroke.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, stroke)
    }

    /** The rail: the launcher button at one end and three app icons in the middle. */
    private fun rail(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        box(canvas, l, t, r, b, COLOR_RAIL)
        val across = r - l > b - t
        val thick = if (across) b - t else r - l
        val radius = minOf(ICON_DP, thick * 0.6f) / 2
        val mid = if (across) (l + r) / 2 else (t + b) / 2
        fun at(along: Float, color: Int, size: Float) =
            if (across) dot(canvas, along, (t + b) / 2, size, color) else dot(canvas, (l + r) / 2, along, size, color)
        APP_COLORS.forEachIndexed { i, color -> at(mid + thick * (i - 1), color, radius) }
        val end = if (across) l + thick / 2 else b - thick / 2
        at(end, COLOR_TEXT, radius * 0.75f)
        at(end, COLOR_RAIL, radius * 0.45f)
    }

    /** The map: streets, a park, a route with a position dot, and the next-turn card. */
    private fun map(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        if (r <= l || b <= t) return
        box(canvas, l, t, r, b, COLOR_MAP)
        val w = r - l
        val h = b - t
        val x1 = l + w * 0.34f
        val x2 = l + w * 0.68f
        val y1 = t + h * 0.38f
        val y2 = t + h * 0.72f
        box(canvas, x1 + STREET_DP, y1 + STREET_DP, x2 - STREET_DP, y2 - STREET_DP, COLOR_PARK)
        line(canvas, l, y1, r, y1, STREET_DP, COLOR_STREET)
        line(canvas, l, y2, r, y2, STREET_DP, COLOR_STREET)
        line(canvas, x1, t, x1, b, STREET_DP, COLOR_STREET)
        line(canvas, x2, t, x2, b, STREET_DP, COLOR_STREET)
        val start = t + h * 0.9f
        stroke.color = COLOR_ROUTE
        stroke.strokeWidth = ROUTE_DP * s
        stroke.strokeCap = Paint.Cap.ROUND
        path.rewind()
        path.moveTo(x1 * s, start * s)
        path.lineTo(x1 * s, y2 * s)
        path.lineTo(x2 * s, y2 * s)
        path.lineTo(x2 * s, y1 * s)
        path.lineTo((r - ROUTE_DP) * s, y1 * s)
        canvas.drawPath(path, stroke)
        dot(canvas, x1, start, ROUTE_DP * 1.5f, COLOR_TEXT)
        dot(canvas, x1, start, ROUTE_DP, COLOR_ROUTE)
        val pad = minOf(PAD_DP, w * 0.04f)
        val turnW = minOf(TURN_W_DP, w * 0.5f)
        val turnH = minOf(TURN_H_DP, h * 0.26f)
        box(canvas, l + pad, t + pad, l + pad + turnW, t + pad + turnH, COLOR_TURN)
        dot(canvas, l + pad + turnH / 2, t + pad + turnH / 2, turnH * 0.24f, COLOR_TEXT)
        text(canvas, l + pad + turnH, t + pad + turnH * 0.34f, turnW - turnH - pad, turnH)
    }

    /** A media card: cover art, a title and an artist line, and a progress bar. */
    private fun media(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        if (r <= l || b <= t) return
        box(canvas, l, t, r, b, COLOR_CARD)
        val w = r - l
        val h = b - t
        val pad = minOf(PAD_DP, w * 0.07f, h * 0.14f)
        val bar = minOf(BAR_DP, h * 0.05f)
        val art = minOf(ART_DP, h - pad * 3 - bar, w * 0.36f)
        box(canvas, l + pad, t + pad, l + pad + art, t + pad + art, COLOR_ART, CORNER_DP / 2)
        text(canvas, l + pad * 2 + art, t + pad + art * 0.2f, w - art - pad * 3, art)
        val y = b - pad - bar / 2
        line(canvas, l + pad, y, r - pad, y, bar, COLOR_STREET)
        line(canvas, l + pad, y, l + pad + (w - pad * 2) * 0.4f, y, bar, COLOR_TEXT)
    }

    /** A second card: a round picture and two lines, as a message or a suggestion shows. */
    private fun note(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        if (r <= l || b <= t) return
        box(canvas, l, t, r, b, COLOR_CARD)
        val w = r - l
        val h = b - t
        val pad = minOf(PAD_DP, w * 0.07f, h * 0.14f)
        val size = minOf(ICON_DP, h - pad * 2, w * 0.2f)
        dot(canvas, l + pad + size / 2, t + pad + size / 2, size / 2, COLOR_AVATAR)
        text(canvas, l + pad * 2 + size, t + pad + size * 0.15f, w - size - pad * 3, size)
    }

    /** Two text lines, a bright one over a dim one, inside a box of [width] x [height]. */
    private fun text(canvas: Canvas, l: Float, t: Float, width: Float, height: Float) {
        if (width <= 0f) return
        val bar = minOf(TEXT_DP, height * 0.22f)
        box(canvas, l, t, l + width * 0.8f, t + bar, COLOR_TEXT, bar / 2)
        box(canvas, l, t + bar * 1.8f, l + width * 0.5f, t + bar * 2.6f, COLOR_TEXT_DIM, bar / 2)
    }

    private companion object {
        const val RAIL_DP = 72f
        const val STRIP_DP = 88f
        const val GAP = 8f
        const val CORNER_DP = 16f
        const val PAD_DP = 16f
        const val ICON_DP = 44f
        const val ART_DP = 96f
        const val TEXT_DP = 16f
        const val BAR_DP = 6f
        const val STREET_DP = 14f
        const val ROUTE_DP = 9f
        const val TURN_W_DP = 260f
        const val TURN_H_DP = 76f
        // The card column took 26 to 32 percent of the width on the units measured.
        const val DASHBOARD_PART = 0.29f
        // Fixed colours: the sketch shows Android Auto's own dark screen, in either app theme.
        const val COLOR_BACKDROP = 0xFF0B0D10.toInt()
        const val COLOR_RAIL = 0xFF16191E.toInt()
        const val COLOR_MAP = 0xFF1D2731.toInt()
        const val COLOR_STREET = 0xFF34424F.toInt()
        const val COLOR_PARK = 0xFF1F3A30.toInt()
        const val COLOR_ROUTE = 0xFF5B9BFF.toInt()
        const val COLOR_TURN = 0xFF0E6B47.toInt()
        const val COLOR_CARD = 0xFF242830.toInt()
        const val COLOR_ART = 0xFFC8743A.toInt()
        const val COLOR_AVATAR = 0xFF7C6BB8.toInt()
        const val COLOR_TEXT = 0xE6FFFFFF.toInt()
        const val COLOR_TEXT_DIM = 0x80FFFFFF.toInt()
        val APP_COLORS = intArrayOf(0xFF4CAF7A.toInt(), 0xFFE0823D.toInt(), 0xFF4F8DF5.toInt())
    }
}
