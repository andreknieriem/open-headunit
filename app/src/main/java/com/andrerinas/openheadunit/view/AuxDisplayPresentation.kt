package com.andrerinas.openheadunit.view

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The window that carries the auxiliary display's picture.
 *
 * A plain SurfaceView rather than an [IProjectionView]: the auxiliary stream is already sized to
 * this panel by the margins announced for it, so none of the main canvas's scaling applies.
 */
internal class AuxDisplayPresentation(
    outerContext: Context,
    display: Display,
    private val decoder: VideoDecoder,
    private val onSurfaceReady: () -> Unit,
) : Presentation(outerContext, display) {

    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = SurfaceView(context)
        surfaceView = view
        setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                AppLog.i("AuxDisplayPresentation: surface ready on display ${display.displayId}")
                decoder.setSurface(holder.surface)
                // A surface recreated mid-session restarts the decoder, which then needs a keyframe.
                onSurfaceReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                decoder.setSurface(holder.surface)
            }

            // Only if it is still ours: a decoder that has already moved on must not be stopped by
            // the window that used to own it.
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                decoder.stopIfCurrentSurface(holder.surface, "the auxiliary display went away")
            }
        })
    }

    override fun onStop() {
        surfaceView?.holder?.surface?.let { decoder.stopIfCurrentSurface(it, "the auxiliary display was dismissed") }
        surfaceView = null
        super.onStop()
    }
}
