package com.andrerinas.openheadunit.view

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), IProjectionView, SurfaceHolder.Callback {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var videoDecoder: VideoDecoder? = null
    private var videoWidth = 0
    private var videoHeight = 0

    /**
     * False when the caller owns the decoder and the layout. The onboarding video test uses this
     * view as a plain surface for its own decoder, so it must not touch the app's singleton or
     * the projection scaler.
     */
    var ownsDecoder: Boolean = true
    var appliesProjectionScale: Boolean = true

    private var fixedBufferWidth = 0
    private var fixedBufferHeight = 0
    private var lastContentWidth = 0
    private var lastContentHeight = 0
    private var observedParent: View? = null

    // Sizing by layout pins the view and buffer, so a container resize must re-fit from here.
    private val parentLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val cw = v.width - v.paddingLeft - v.paddingRight
        val ch = v.height - v.paddingTop - v.paddingBottom
        if (appliesProjectionScale && SurfaceViewFitPolicy.refitsOnParentLayout(
                fixedBufferWidth > 0, lastContentWidth, lastContentHeight, cw, ch)
        ) {
            AppLog.i("[UI_DEBUG] SurfaceView parent resized: ${lastContentWidth}x${lastContentHeight} -> ${cw}x${ch}, refitting")
            post { ProjectionViewScaler.updateScale(this, videoWidth, videoHeight) }
        }
        lastContentWidth = cw
        lastContentHeight = ch
    }

    val reportedSurfaceWidth: Int
        get() = SurfaceViewFitPolicy.reportedSurfaceSize(fixedBufferWidth, fixedBufferHeight, width, height).first
    val reportedSurfaceHeight: Int
        get() = SurfaceViewFitPolicy.reportedSurfaceSize(fixedBufferWidth, fixedBufferHeight, width, height).second

    init {
        videoDecoder = App.provide(context).videoDecoder
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.let {
            it.addOnLayoutChangeListener(parentLayoutListener)
            observedParent = it
        }
    }

    /** Pins the buffer to a fixed size, or hands it back to the layout when either side is 0. */
    fun setBufferSize(width: Int, height: Int) {
        if (width == fixedBufferWidth && height == fixedBufferHeight) return
        fixedBufferWidth = width
        fixedBufferHeight = height
        if (width > 0 && height > 0) holder.setFixedSize(width, height) else holder.setSizeFromLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        observedParent?.removeOnLayoutChangeListener(parentLayoutListener)
        observedParent = null
        // The decoder is a singleton and this view may be a discarded instance detaching after a
        // replacement already claimed it, so only stop the decoder if this surface still owns it.
        if (ownsDecoder) {
            videoDecoder?.detachSurfaceIfCurrent(holder.surface, DecoderStopPolicy.REASON_DETACHED_FROM_WINDOW)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (appliesProjectionScale) {
            ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        callbacks.forEach { it.onSurfaceCreated(holder.surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.i("holder $holder, format: $format, width: $width, height: $height")
        callbacks.forEach { it.onSurfaceChanged(holder.surface, width, height) }
        if (appliesProjectionScale) {
            ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        if (ownsDecoder) {
            videoDecoder?.detachSurfaceIfCurrent(holder.surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        }
        callbacks.forEach { it.onSurfaceDestroyed(holder.surface) }
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        if (holder.surface.isValid) {
            callback.onSurfaceCreated(holder.surface)
            callback.onSurfaceChanged(holder.surface, reportedSurfaceWidth, reportedSurfaceHeight)
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

        if (appliesProjectionScale && HeadUnitScreenConfig.forcedScale) {
            val settings = App.provide(context).settings
            if (settings.videoFitMode == Settings.VideoFitMode.FILL) {
                AppLog.i("FORCED SCALE: Setting fixed size to ${width}x$height")
                holder.setFixedSize(width, height)
                fixedBufferWidth = 0
                fixedBufferHeight = 0
            } else {
                // CONTAIN/COVER: the LayoutParams size set by ProjectionViewScaler (getAdjustedWidth/
                // Height or getCoverWidth/Height) IS the deliberate final pixel size - adopt it as
                // the buffer size directly rather than stretching from native.
                holder.setSizeFromLayout()
                fixedBufferWidth = 0
                fixedBufferHeight = 0
            }
        } else if (fixedBufferWidth == 0) {
            holder.setSizeFromLayout()
        }

        if (appliesProjectionScale) {
            ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
        } else {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        }
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

    /**
     * A copy of the current surface, or null when this Android version cannot copy a SurfaceView.
     *
     * PixelCopy arrived in Android 7.0; below that a plain SurfaceView cannot be read back at all,
     * which is why the video test reports the picture as unverified on those units. Call this from
     * a background thread: the copy completes on the main looper.
     */
    fun captureBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || width <= 0 || height <= 0) return null
        if (!holder.surface.isValid) return null
        return Api24.capture(this, width, height)
    }

    @TargetApi(Build.VERSION_CODES.N)
    private object Api24 {
        fun capture(view: ProjectionView, width: Int, height: Int): Bitmap? {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var success = false
            PixelCopy.request(
                view,
                bitmap,
                { result ->
                    success = result == PixelCopy.SUCCESS
                    latch.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            if (!latch.await(1, TimeUnit.SECONDS)) return null
            return if (success) bitmap else null
        }
    }
}
