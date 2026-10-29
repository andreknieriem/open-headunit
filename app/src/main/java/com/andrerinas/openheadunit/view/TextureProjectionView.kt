package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.andrerinas.openheadunit.decoder.video.VideoSurfaceKind
import com.andrerinas.openheadunit.utils.AppLog

class TextureProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), IProjectionView, TextureView.SurfaceTextureListener {
    override val videoSurfaceKind: VideoSurfaceKind get() = VideoSurfaceKind.TEXTURE_CONSUMER

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var surface: Surface? = null

    /**
     * When false the view fills its parent as it is and skips [ProjectionViewScaler]. The
     * onboarding video test shows a small preview instead of the projection layout, and its
     * marker geometry is read in that preview's own coordinates.
     */
    var appliesProjectionScale: Boolean = true

    private var videoWidth = 0
    private var videoHeight = 0

    // elapsedRealtime() of the last frame the TextureView actually displayed. Read by the
    // projection watchdog to detect a stalled display consumer (issue #650).
    @Volatile
    private var lastFrameDrawnMsValue: Long = 0L

    // Count of abnormally long gaps between displayed frames (issue #650), for catching a
    // throughput collapse where frames keep coming but very slowly.
    @Volatile
    private var longFrameEventsValue: Long = 0L
    private var prevDrawMs: Long = 0L
    private val longFrameThresholdMs = 1200L

    // Displayed-frame telemetry. VideoDecoder's throughput tick reports what it hands to the
    // surface; this reports what the compositor actually put on screen. Only the difference
    // between the two identifies a unit whose decoder keeps up while its picture does not, and
    // this callback is the sole place that difference is observable - a TextureView composites
    // through an external texture, so a consumer too slow to sample it silently loses frames
    // rather than applying backpressure the decoder could notice.
    private var drawnSinceLogValue: Long = 0L
    private var lastDrawLogMs: Long = 0L
    private val drawLogIntervalMs = 5000L

    @Volatile
    private var drawnFramesValue: Long = 0L

    init {
        surfaceTextureListener = this
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    override fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        // AppLog has no (tag, message) overload - the second argument was being consumed as a
        // format parameter, so this line only ever printed "TextureProjectionView".
        AppLog.i("TextureProjectionView: Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height
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
        this.translationX = 0f
        this.translationY = 0f
    }

    // ----------------------------------------------------------------
    // Lifecycle & SurfaceTextureListener
    // ----------------------------------------------------------------

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        AppLog.i("TextureProjectionView: Surface available: ${width}x$height")
        surface = Surface(surfaceTexture)
        surface?.let {
            callbacks.forEach { cb -> cb.onSurfaceCreated(it) }
            // The width and height of the view are passed here, but the decoder should
            // use the actual video dimensions it parses from the SPS.
            callbacks.forEach { cb -> cb.onSurfaceChanged(it, width, height, videoSurfaceKind) }
        }
        if (appliesProjectionScale) {
            ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        AppLog.i("TextureProjectionView: Surface size changed: ${width}x$height")
        if (appliesProjectionScale) {
            ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        AppLog.i("TextureProjectionView: Surface destroyed")
        surface?.let {
            callbacks.forEach { cb -> cb.onSurfaceDestroyed(it) }
        }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // Fired each time a new frame is displayed. Used as the display-consumer
        // liveness signal for stall detection (issue #650).
        val now = SystemClock.elapsedRealtime()
        val prev = prevDrawMs
        if (prev != 0L && now - prev > longFrameThresholdMs) {
            longFrameEventsValue++
        }
        prevDrawMs = now
        lastFrameDrawnMsValue = now
        drawnFramesValue++

        drawnSinceLogValue++
        if (lastDrawLogMs == 0L) {
            lastDrawLogMs = now
        } else {
            val elapsed = now - lastDrawLogMs
            if (elapsed >= drawLogIntervalMs) {
                val fps = drawnSinceLogValue * 1000 / elapsed
                AppLog.i("TextureProjectionView: displayed $drawnSinceLogValue frames in ${elapsed}ms (${fps}fps), longFrames=$longFrameEventsValue")
                drawnSinceLogValue = 0L
                lastDrawLogMs = now
            }
        }
    }

    override fun lastFrameDrawnMs(): Long = lastFrameDrawnMsValue

    override fun longFrameEvents(): Long = longFrameEventsValue

    override fun drawnFrames(): Long = drawnFramesValue

    /**
     * A copy of what this view is showing right now, or null before the surface exists.
     *
     * TextureView has had this since API 14, which is why the video test can read the picture
     * back on Android 4.x. Call it on the UI thread.
     */
    fun captureBitmap(): Bitmap? = if (isAvailable) runCatching { getBitmap() }.getOrNull() else null

    /**
     * The transform of the frame on screen, which carries the decoder's crop. [captureBitmap]
     * ignores it, so a padded buffer shows its padding there. Call it on the UI thread.
     */
    fun textureTransform(): FloatArray? {
        val texture = surfaceTexture ?: return null
        return runCatching { FloatArray(16).also { texture.getTransformMatrix(it) } }.getOrNull()
    }

    // ----------------------------------------------------------------
    // Callbacks
    // ----------------------------------------------------------------

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        // If surface is already available, notify immediately.
        surface?.let {
            callback.onSurfaceCreated(it)
            callback.onSurfaceChanged(it, width, height, videoSurfaceKind)
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }
}
