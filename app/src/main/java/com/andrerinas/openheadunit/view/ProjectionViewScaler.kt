package com.andrerinas.openheadunit.view

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        val settings = App.provide(view.context).settings
        HeadUnitScreenConfig.init(view.context, view.resources.displayMetrics, settings)

        val usableW = HeadUnitScreenConfig.getUsableWidth()
        val usableH = HeadUnitScreenConfig.getUsableHeight()

        val mirrorFactor = if (settings.hudMirroring) -1.0f else 1.0f

        if (HeadUnitScreenConfig.forcedScale && view is ProjectionView) {
            val lp = view.layoutParams
            var paramsChanged = false

            if (settings.videoFitMode == Settings.VideoFitMode.FILL) {
                // Stretch to fill the usable area exactly (ignores aspect ratio, no bars, no crop)
                if (lp.width != usableW || lp.height != usableH) {
                    lp.width = usableW
                    lp.height = usableH
                    paramsChanged = true
                }

                if (lp is FrameLayout.LayoutParams) {
                    val targetGravity = Gravity.TOP or Gravity.START
                    if (lp.gravity != targetGravity) {
                        lp.gravity = targetGravity
                        paramsChanged = true
                    }
                }

                if (paramsChanged) {
                    view.layoutParams = lp
                }

                view.scaleX = 1.0f * mirrorFactor
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                AppLog.i("[UI_DEBUG] FORCED FILL: Resized view to match screen exactly: ${usableW}x${usableH}")
            } else {
                // CONTAIN: size down to fit within the usable area, centered, letterboxed.
                // COVER: size up past the usable area, centered, the overflow runs past the usable area; nothing below API 24 clips a SurfaceView layer.
                // Both preserve aspect ratio (no distortion) - only the target size differs.
                val targetW: Int
                val targetH: Int
                if (settings.videoFitMode == Settings.VideoFitMode.COVER) {
                    targetW = HeadUnitScreenConfig.getCoverWidth()
                    targetH = HeadUnitScreenConfig.getCoverHeight()
                } else {
                    targetW = HeadUnitScreenConfig.getAdjustedWidth()
                    targetH = HeadUnitScreenConfig.getAdjustedHeight()
                }

                if (lp.width != targetW || lp.height != targetH) {
                    lp.width = targetW
                    lp.height = targetH
                    paramsChanged = true
                }

                // Center the view in the usable area
                if (lp is FrameLayout.LayoutParams) {
                    if (lp.gravity != Gravity.CENTER) {
                        lp.gravity = Gravity.CENTER
                        paramsChanged = true
                    }
                }

                if (paramsChanged) {
                    view.layoutParams = lp
                }

                view.scaleX = 1.0f * mirrorFactor
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                AppLog.i("[UI_DEBUG] FORCED ${settings.videoFitMode}: Resized view to ${targetW}x${targetH} (centered)")
            }
        } else {
            // Modern way / TextureView: Use View scaling properties on a full-screen view
            val finalScaleX = HeadUnitScreenConfig.getScaleX() * mirrorFactor
            val finalScaleY = HeadUnitScreenConfig.getScaleY()

            val lp = view.layoutParams
            var paramsChanged = false

            val parent = view.parent as? View
            val fit = SurfaceViewFitPolicy.decide(
                Build.VERSION.SDK_INT, view is ProjectionView, HeadUnitScreenConfig.forcedScale,
                parent?.paddingLeft ?: 0, parent?.paddingTop ?: 0,
                if (parent == null) 0 else parent.width - parent.paddingLeft - parent.paddingRight,
                if (parent == null) 0 else parent.height - parent.paddingTop - parent.paddingBottom,
                finalScaleX, finalScaleY,
            )
            if (fit is SurfaceViewFitPolicy.Fit.Layout && view is ProjectionView) {
                if (lp.width != fit.width || lp.height != fit.height) {
                    lp.width = fit.width
                    lp.height = fit.height
                    paramsChanged = true
                }
                if (lp is FrameLayout.LayoutParams && lp.gravity != Gravity.CENTER) {
                    lp.gravity = Gravity.CENTER
                    paramsChanged = true
                }
                if (paramsChanged) {
                    view.layoutParams = lp
                }
                view.translationX = 0f
                view.translationY = 0f
                view.setVideoScale(fit.scaleXSign, 1f)
                view.setBufferSize(fit.bufferWidth, fit.bufferHeight)
                AppLog.i("[UI_DEBUG] SurfaceView sized by layout: ${fit.width}x${fit.height} at (${fit.left},${fit.top}), buffer ${fit.bufferWidth}x${fit.bufferHeight}, scale ${finalScaleX}x${finalScaleY}")
                return
            }
            (view as? ProjectionView)?.setBufferSize(0, 0)

            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || 
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                paramsChanged = true
            }
            
            if (lp is FrameLayout.LayoutParams) {
                if (lp.gravity != Gravity.NO_GRAVITY) {
                    lp.gravity = Gravity.NO_GRAVITY
                    paramsChanged = true
                }
            }
            
            if (paramsChanged) {
                view.layoutParams = lp
            }

            // Normal centering for non-forced modes
            view.translationX = 0f
            view.translationY = 0f

            if (view is IProjectionView) {
                view.setVideoScale(finalScaleX, finalScaleY)
            } else {
                view.scaleX = finalScaleX
                view.scaleY = finalScaleY
            }
            AppLog.i("[UI_DEBUG] Normal Scale. scaleX: $finalScaleX, scaleY: $finalScaleY")
        }
    }
}
