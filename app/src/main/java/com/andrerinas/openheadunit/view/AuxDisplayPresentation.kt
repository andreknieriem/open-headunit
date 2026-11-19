package com.andrerinas.openheadunit.view

import android.app.Presentation
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The auxiliary display's window: a TextureView scaled about its centre by [cropScaleX]/[cropScaleY],
 * so the blank margin on each side of a frame falls off the panel instead of shrinking it.
 */
internal class AuxDisplayPresentation(
    outerContext: Context,
    display: Display,
    private val decoder: VideoDecoder,
    private val cropScaleX: Float,
    private val cropScaleY: Float,
    private val onSurfaceReady: () -> Unit,
) : Presentation(outerContext, display) {

    private var surface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextureView(context)
        setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                AppLog.i("AuxDisplayPresentation: surface ready on display ${display.displayId} " +
                    "(margin crop x$cropScaleX, x$cropScaleY)")
                // The phone centres the picture in the frame (it logs dispLeft = half the width margin), so
                // the crop grows from the middle; from the top-left it showed one bar and cut the far side.
                view.setTransform(Matrix().apply { setScale(cropScaleX, cropScaleY, width / 2f, height / 2f) })
                val created = Surface(texture)
                surface = created
                decoder.setSurface(created)
                // A surface recreated mid-session restarts the decoder, which then needs a keyframe.
                onSurfaceReady()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

            // Stops the decoder only while it still renders here, not after it moved to another surface.
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                release("the auxiliary display went away")
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
    }

    private fun release(reason: String) {
        val current = surface ?: return
        surface = null
        decoder.stopIfCurrentSurface(current, reason)
        current.release()
    }

    override fun onStop() {
        release("the auxiliary display was dismissed")
        super.onStop()
    }
}
