package com.andrerinas.openheadunit.connection.carplaybox

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.SurfaceActivity
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.decoder.video.VideoSurfaceKind
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import kotlinx.coroutines.launch

/** Shows a CarPlay box session full screen. It follows the box session, never the Android Auto state. */
class CarPlayBoxActivity : SurfaceActivity(), IProjectionView.Callbacks {

    private val settings: Settings by lazy { Settings(this) }
    private val videoDecoder by lazy { App.provide(this).videoDecoder }
    private val manager: CarPlayBoxManager? get() = AapService.instance?.carPlayBoxManager
    private lateinit var container: FrameLayout
    private lateinit var projectionView: ProjectionView
    private var ownedSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After super.onCreate, as AapProjectionActivity does: the window must exist before it locks.
        requestedOrientation = if (settings.screenOrientation == Settings.ScreenOrientation.AUTO) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            settings.screenOrientation.androidOrientation
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_carplay_box)
        container = findViewById(R.id.carplay_box_container)
        projectionView = findViewById(R.id.carplay_box_view)
        projectionView.appliesProjectionScale = false

        val box = manager
        if (box == null || !box.isLive) {
            AppLog.i("CarPlayBoxActivity: no live box session, closing")
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                manager?.stop("user exit")
                finish()
            }
        })
        container.setOnTouchListener { _, event -> onTouch(event) }
        projectionView.addCallback(this)

        lifecycleScope.launch {
            box.state.collect { if (it is CarPlayBoxLinkSession.Phase.Ended) finish() }
        }
        lifecycleScope.launch {
            box.videoSize.collect { size -> if (size != null) fitView(size.first, size.second) }
        }
    }

    private fun fitView(videoW: Int, videoH: Int) {
        container.post {
            val (w, h) = CarPlayBoxViewFit.size(container.width, container.height, videoW, videoH, settings.videoFitMode)
            val lp = projectionView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                projectionView.layoutParams = lp
            }
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val box = manager ?: return false
        val size = box.videoSize.value ?: return true
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> CarPlayBoxTouchScaler.ACTION_DOWN
            MotionEvent.ACTION_MOVE -> CarPlayBoxTouchScaler.ACTION_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> CarPlayBoxTouchScaler.ACTION_UP
            else -> return true
        }
        // Single touch only: the first pointer. No HUD mirroring: this activity never mirrors the picture.
        val mapped = TouchCoordinateMapper.map(
            event.getX(0), event.getY(0), container.width.toFloat(), container.height.toFloat(),
            size.first, size.second, 0f, 0f, settings.videoFitMode, hudMirroring = false,
        )
        box.sendTouch(action, CarPlayBoxTouchScaler.scale(mapped, size.first, size.second))
        return true
    }

    override fun onSurfaceCreated(surface: Surface) = Unit

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int, kind: VideoSurfaceKind) {
        videoDecoder.setSurface(surface)
        ownedSurface = surface
        manager?.onSurfaceReady(width, height)
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        videoDecoder.detachSurfaceIfCurrent(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        if (ownedSurface === surface) ownedSurface = null
    }

    override fun onDestroy() {
        ownedSurface?.let { videoDecoder.detachSurfaceIfCurrent(it, DecoderStopPolicy.REASON_SURFACE_DESTROYED) }
        ownedSurface = null
        projectionView.removeCallback(this)
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, CarPlayBoxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
