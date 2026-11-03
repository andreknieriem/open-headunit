package com.andrerinas.openheadunit.view

import android.view.Surface
import com.andrerinas.openheadunit.decoder.video.VideoSurfaceKind

interface IProjectionView {
    /** Explicit consumer semantics; texture draw callbacks are not display clock measurements. */
    val videoSurfaceKind: VideoSurfaceKind

    interface Callbacks {
        fun onSurfaceCreated(surface: Surface)
        fun onSurfaceDestroyed(surface: Surface)
        fun onSurfaceChanged(surface: Surface, width: Int, height: Int, kind: VideoSurfaceKind)
    }

    fun addCallback(callback: Callbacks)
    fun removeCallback(callback: Callbacks)
    fun setVideoSize(width: Int, height: Int)
    fun setVideoScale(scaleX: Float, scaleY: Float)

    /**
     * elapsedRealtime() of the last frame this view's display consumer actually drew,
     * or 0 if none yet. Returns -1 when the backend cannot report it (a plain SurfaceView
     * is composited by SurfaceFlinger with no draw callback). The projection watchdog uses
     * this to detect a frozen display while the decoder keeps producing frames (issue #650):
     * decoder still releasing frames but consumer not drawing means the picture is stuck.
     */
    fun lastFrameDrawnMs(): Long

    /**
     * Monotonic count of abnormally long gaps between consecutive drawn frames since this view
     * was created. On MediaTek/GLES the consumer does not fully stop but collapses to a few fps
     * with single frames taking up to ~2s, so a plain "no frame for N seconds" check misses it;
     * this counter lets the watchdog catch that throughput collapse (issue #650). Returns 0 for
     * backends that cannot observe per-frame draws (SurfaceView).
     */
    fun longFrameEvents(): Long

    /**
     * Frames this view has drawn since it was created, or -1 when the backend cannot count them.
     *
     * A plain SurfaceView is composited by SurfaceFlinger with no draw callback, so it returns
     * -1. TextureView and the GLES view count every frame they display; the onboarding video
     * test uses the difference between this and the decoder's count to tell a slow decoder from
     * a renderer that drops the picture.
     */
    fun drawnFrames(): Long = -1L
}
