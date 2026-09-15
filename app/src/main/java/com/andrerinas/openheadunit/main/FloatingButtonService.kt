package com.andrerinas.openheadunit.main

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FloatingButtonService : Service() {

    private var overlayView: View?
        get() = activeOverlayView
        set(value) { activeOverlayView = value }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sessionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e: Exception) {
                AppLog.e("FloatingButtonService: startForeground failed: ${e.message}", e)
            }
        }

        val commManager = App.provide(this).commManager
        sessionJob = serviceScope.launch {
            commManager.connectionState.collect { _ ->
                mainHandler.post { showOrUpdateOverlay() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = App.provide(this).settings
        val enabled = settings.enableFloatingButton
        val permissionGranted = FloatingButtonManager.hasOverlayPermission(this)
        val shouldShow = enabled && permissionGranted && !FloatingButtonManager.isAppForeground

        if (!shouldShow) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        mainHandler.post { showOrUpdateOverlay() }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOrUpdateOverlay() {
        val appContext = applicationContext
        val settings = App.provide(appContext).settings
        val enabled = settings.enableFloatingButton
        val permissionGranted = FloatingButtonManager.hasOverlayPermission(appContext)
        val shouldShow = enabled && permissionGranted && !FloatingButtonManager.isAppForeground

        if (!shouldShow) {
            removeOverlay()
            return
        }

        val windowManager = (appContext.getSystemService(WINDOW_SERVICE) as? WindowManager) ?: return

        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }
        val density = displayMetrics.density

        val sizePx = (settings.floatingButtonSizeDp * density).roundToInt().coerceAtLeast((32 * density).roundToInt())
        val maxX = (displayMetrics.widthPixels - sizePx).coerceAtLeast(0)
        val maxY = (displayMetrics.heightPixels - sizePx).coerceAtLeast(0)

        val xPx = ((settings.floatingButtonXPercent / 100f) * maxX).roundToInt()
        val yPx = ((settings.floatingButtonYPercent / 100f) * maxY).roundToInt()

        val commManager = App.provide(appContext).commManager
        val isConnected = commManager.isConnected
        val isConnectionStatusMode = settings.floatingButtonConnectionStatusMode
        val configuredAlpha = (settings.floatingButtonOpacityPercent / 100f).coerceIn(0.0f, 1.0f)
        val targetAlpha = if (isConnectionStatusMode && !isConnected) {
            0.0f
        } else {
            configuredAlpha
        }

        if (overlayView == null) {
            val button = ImageView(appContext).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundResource(R.drawable.bg_floating_button)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = if (targetAlpha > 0f) 8f * density else 0f
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                }
            }

            val layoutParams = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = xPx
                y = yPx
            }

            button.setOnClickListener {
                try {
                    val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    appContext.startActivity(launchIntent)
                } catch (e: Exception) {
                    AppLog.e("FloatingButtonService: Failed to launch MainActivity", e)
                }
            }

            button.alpha = targetAlpha

            try {
                windowManager.addView(button, layoutParams)
                overlayView = button
                AppLog.i("FloatingButtonService: Added floating button overlay")
            } catch (e: Exception) {
                AppLog.w("FloatingButtonService: Failed to add overlay view (${e.message})")
            }
        } else {
            val button = (overlayView as? ImageView) ?: return
            val layoutParams = (button.layoutParams as? WindowManager.LayoutParams) ?: return

            layoutParams.width = sizePx
            layoutParams.height = sizePx
            layoutParams.x = xPx
            layoutParams.y = yPx
            button.animate().alpha(targetAlpha).setDuration(300).start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                button.elevation = if (targetAlpha > 0f) 8f * density else 0f
            }

            try {
                windowManager.updateViewLayout(button, layoutParams)
            } catch (e: Exception) {
                AppLog.w("FloatingButtonService: Failed to update overlay layout (${e.message})")
                overlayView = null
            }
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacksAndMessages(null)
        val view = overlayView ?: return
        try {
            val windowManager = applicationContext.getSystemService(WINDOW_SERVICE) as? WindowManager
            windowManager?.removeView(view)
            AppLog.i("FloatingButtonService: Removed floating button overlay")
        } catch (e: Exception) {
            AppLog.w("FloatingButtonService: Failed to remove overlay view (${e.message})")
        } finally {
            overlayView = null
        }
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        removeOverlay()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(getString(R.string.title))
            .setContentText("Floating Button Active")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.andrerinas.openheadunit.ACTION_STOP_FLOATING_BUTTON"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var activeOverlayView: View? = null

        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLog.e("FloatingButtonService: Failed to start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                try { context.stopService(intent) } catch (_: Exception) {}
            }
        }
    }
}
