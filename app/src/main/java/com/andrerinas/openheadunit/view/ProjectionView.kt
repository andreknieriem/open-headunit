package com.andrerinas.openheadunit.view

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings

class ProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), IProjectionView, SurfaceHolder.Callback {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var videoDecoder: VideoDecoder? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        videoDecoder = App.provide(context).videoDecoder
        holder.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The decoder is a singleton and this view may be a discarded instance detaching after a
        // replacement already claimed it, so only stop the decoder if this surface still owns it.
        videoDecoder?.detachSurfaceIfCurrent(holder.surface, DecoderStopPolicy.REASON_DETACHED_FROM_WINDOW)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        callbacks.forEach { it.onSurfaceCreated(holder.surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.i("holder $holder, format: $format, width: $width, height: $height")
        callbacks.forEach { it.onSurfaceChanged(holder.surface, width, height) }
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        videoDecoder?.detachSurfaceIfCurrent(holder.surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        callbacks.forEach { it.onSurfaceDestroyed(holder.surface) }
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        if (holder.surface.isValid) {
            callback.onSurfaceCreated(holder.surface)
            callback.onSurfaceChanged(holder.surface, width, height)
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }

    override fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        AppLog.i("Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height

        if (HeadUnitScreenConfig.forcedScale) {
            val settings = App.provide(context).settings
            if (settings.videoFitMode == Settings.VideoFitMode.FILL) {
                AppLog.i("FORCED SCALE: Setting fixed size to ${width}x$height")
                holder.setFixedSize(width, height)
            } else {
                // CONTAIN/COVER: the LayoutParams size set by ProjectionViewScaler (getAdjustedWidth/
                // Height or getCoverWidth/Height) IS the deliberate final pixel size - adopt it as
                // the buffer size directly rather than stretching from native.
                holder.setSizeFromLayout()
            }
        } else {
            holder.setSizeFromLayout()
        }

        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }

    // A plain SurfaceView is composited directly by SurfaceFlinger, so there is no
    // per-frame draw callback we can observe. Report "unsupported" so the projection
    // watchdog skips display-stall recovery for this backend (issue #650).
    //
    // This is also why this backend logs no displayed-frame rate while the other two do: there is
    // nothing to count. The decoder's `presented=` throughput field is the substitute - it counts
    // the buffers the surface actually keeps rather than the ones handed to it.
    override fun lastFrameDrawnMs(): Long = -1L

    override fun longFrameEvents(): Long = 0L
}
