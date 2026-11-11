package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy.Layout

/**
 * Draws the panes of one Android Auto layout in flat colours, at the aspect of the dp canvas.
 * The shapes are an approximation of the layout, not a screenshot.
 */
class DpiLayoutSketchView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var layout = Layout.CANONICAL
    private var canvasW = 16
    private var canvasH = 9
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** [dpW] x [dpH] is the dp canvas the phone lays out at the density being shown. */
    fun setLayout(layout: Layout, dpW: Int, dpH: Int) {
        this.layout = layout
        canvasW = dpW.coerceAtLeast(1)
        canvasH = dpH.coerceAtLeast(1)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w.toLong() * canvasH / canvasW).toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat() / canvasW
        val w = canvasW.toFloat()
        val h = canvasH.toFloat()
        val rail = minOf(RAIL_DP, minOf(w, h) * 0.14f)
        fill(canvas, s, 0f, 0f, w, h, COLOR_BACKDROP)
        when (layout) {
            Layout.WIDESCREEN -> {
                val body = h - rail
                val column = minOf(MEDIA_COLUMN_DP, w * 0.4f)
                fill(canvas, s, GAP, GAP, w - column - GAP, body, COLOR_MAP)
                val split = body * 0.55f
                fill(canvas, s, w - column + GAP, GAP, w - GAP, split - GAP, COLOR_CARD)
                fill(canvas, s, w - column + GAP, split + GAP, w - GAP, body, COLOR_MEDIA)
                fill(canvas, s, 0f, body, w, h, COLOR_RAIL)
            }
            Layout.VERTICAL_RAIL -> {
                fill(canvas, s, 0f, 0f, rail, h, COLOR_RAIL)
                fill(canvas, s, rail + GAP, GAP, w - GAP, h - GAP, COLOR_MAP)
            }
            Layout.HORIZONTAL_RAIL, Layout.CANONICAL, Layout.PORTRAIT_NARROW -> {
                fill(canvas, s, GAP, GAP, w - GAP, h - rail, COLOR_MAP)
                fill(canvas, s, 0f, h - rail, w, h, COLOR_RAIL)
            }
            Layout.PORTRAIT_SHORT -> {
                val strip = minOf(STRIP_DP, h * 0.12f)
                fill(canvas, s, GAP, GAP, w - GAP, h - rail - strip - GAP, COLOR_MAP)
                fill(canvas, s, GAP, h - rail - strip, w - GAP, h - rail - GAP, COLOR_MEDIA)
                fill(canvas, s, 0f, h - rail, w, h, COLOR_RAIL)
            }
            Layout.PORTRAIT -> {
                val body = h - rail
                fill(canvas, s, GAP, GAP, w - GAP, body * 0.6f - GAP, COLOR_MAP)
                fill(canvas, s, GAP, body * 0.6f + GAP, w - GAP, body, COLOR_MEDIA)
                fill(canvas, s, 0f, body, w, h, COLOR_RAIL)
            }
        }
    }

    private fun fill(canvas: Canvas, s: Float, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        rect.set(l * s, t * s, r * s, b * s)
        canvas.drawRoundRect(rect, CORNER_DP * s, CORNER_DP * s, paint)
    }

    private companion object {
        const val RAIL_DP = 72f
        const val STRIP_DP = 64f
        const val GAP = 6f
        const val CORNER_DP = 8f
        // The widescreen media column measured 408 dp wide on a unit.
        const val MEDIA_COLUMN_DP = 408f
        const val COLOR_BACKDROP = 0xFF14171C.toInt()
        const val COLOR_MAP = 0xFF3E6B57.toInt()
        const val COLOR_MEDIA = 0xFF37557F.toInt()
        const val COLOR_CARD = 0xFF6A5A8E.toInt()
        const val COLOR_RAIL = 0xFF4B515C.toInt()
    }
}
