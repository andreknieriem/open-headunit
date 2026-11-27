package com.andrerinas.openheadunit.connection.carplaynative

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.SurfaceActivity
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxViewFit
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.VideoSurfaceKind
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import kotlinx.coroutines.launch

/** Shows a native CarPlay session full screen. It follows the native session and holds no session state. */
class CarPlayNativeActivity : SurfaceActivity(), IProjectionView.Callbacks {

    private val settings: Settings by lazy { Settings(this) }
    private val videoDecoder by lazy { App.provide(this).videoDecoder }
    private val manager: CarPlayNativeManager? get() = AapService.instance?.carPlayNativeManager
    private val touch = CarPlayNativeTouch()
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

        val native = manager
        // Read before the live check, so an end between the two still moves the counter past this value.
        val startEnds = native?.ends?.value ?: 0
        if (native == null || !native.isLive) {
            AppLog.i("CarPlayNativeActivity: no live native session, closing")
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                manager?.stop(CarPlayNativeManager.USER_EXIT)
                finish()
            }
        })
        container.setOnTouchListener { _, event -> onTouch(event) }
        projectionView.addCallback(this)

        lifecycleScope.launch {
            native.phase.collect { if (CarPlayNativeScreenPolicy.finish(it, native.isLive)) finish() }
        }
        lifecycleScope.launch {
            native.ends.collect { if (CarPlayNativeScreenPolicy.finishOnEnds(startEnds, it)) finish() }
        }
        lifecycleScope.launch {
            native.videoSize.collect { size -> if (size != null) fitView(size.first, size.second) }
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
        val native = manager ?: return false
        val size = native.videoSize.value ?: return true
        val ending = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val lifting = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val points = (0 until event.pointerCount).map { i ->
            CarPlayNativeTouch.Point(event.getPointerId(i), event.getX(i), event.getY(i), down = !ending && i != lifting)
        }
        val contacts = touch.contacts(points, container.width.toFloat(), container.height.toFloat(), size.first, size.second, settings.videoFitMode)
        if (contacts.isNotEmpty()) native.sendTouch(contacts)
        if (ending) touch.reset()
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
            Intent(context, CarPlayNativeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
