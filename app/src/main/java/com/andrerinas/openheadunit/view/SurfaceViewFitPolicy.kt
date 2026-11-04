package com.andrerinas.openheadunit.view

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Below API 24 a SurfaceView layer takes the view's moved origin and its unscaled size, so the fit
 * must be a layout size. The buffer stays at the content size so the surface reads as the canvas.
 */
object SurfaceViewFitPolicy {

    sealed class Fit {
        object ViewScale : Fit()
        data class Layout(
            val width: Int, val height: Int,
            val left: Int, val top: Int,
            val bufferWidth: Int, val bufferHeight: Int,
            val scaleXSign: Float,
        ) : Fit()
    }

    fun decide(
        sdkInt: Int, isSurfaceView: Boolean, forcedScale: Boolean,
        paddingLeft: Int, paddingTop: Int, contentWidth: Int, contentHeight: Int,
        scaleX: Float, scaleY: Float,
    ): Fit {
        val scalesOk = scaleX.isFinite() && scaleY.isFinite() && scaleX != 0f && scaleY != 0f
        if (sdkInt >= 24 || !isSurfaceView || forcedScale || !scalesOk ||
            contentWidth <= 0 || contentHeight <= 0
        ) return Fit.ViewScale
        val width = (contentWidth * abs(scaleX)).roundToInt()
        val height = (contentHeight * abs(scaleY)).roundToInt()
        return Fit.Layout(
            width, height,
            paddingLeft + (contentWidth - width) / 2, paddingTop + (contentHeight - height) / 2,
            contentWidth, contentHeight,
            if (scaleX < 0f) -1f else 1f,
        )
    }

    fun reportedSurfaceSize(fixedWidth: Int, fixedHeight: Int, viewWidth: Int, viewHeight: Int): Pair<Int, Int> =
        if (fixedWidth > 0 && fixedHeight > 0) Pair(fixedWidth, fixedHeight) else Pair(viewWidth, viewHeight)

    fun refitsOnParentLayout(
        holdsLayoutFit: Boolean,
        lastContentWidth: Int, lastContentHeight: Int,
        contentWidth: Int, contentHeight: Int,
    ): Boolean = holdsLayoutFit && contentWidth > 0 && contentHeight > 0 &&
        (contentWidth != lastContentWidth || contentHeight != lastContentHeight)
}
