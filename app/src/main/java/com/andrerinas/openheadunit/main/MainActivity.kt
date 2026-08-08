package com.andrerinas.openheadunit.main

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import android.content.res.Configuration
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.SystemUI
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null

    private var isOrientationReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isRecreateReceiverRegistered = false

    private val viewModel: MainViewModel by viewModels()

    private var autoConnectWatchdog: Job? = null
    private var autoConnectKenBurnsAnim: ObjectAnimator? = null

    /**
     * Visual mode for an in-progress auto-connect attempt. PILL is a small,
     * non-blocking status indicator at the top of the home screen used for
     * fully automatic background attempts so the home buttons stay tappable.
     * OVERLAY is the full-screen custom loading screen used for connections
     * the user explicitly triggered with a button.
     */
    enum class ConnectionUiMode { PILL, OVERLAY }

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("MainActivity: Received finish request. Closing.")
                finishAffinity()
            }
        }
    }

    private val recreateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RECREATE_MAIN) {
                AppLog.i("MainActivity: Received recreate request. Recreating.")
                try {
                    recreate()
                } catch (e: Exception) {
                    AppLog.e("MainActivity: Failed to recreate activity", e)
                }
            }
        }
    }

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("MainActivity: Orientation change broadcast received. Updating.")
                requestedOrientation = Settings(this@MainActivity).screenOrientation.androidOrientation
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val settings  = Settings(newBase)
        val scale = settings.uiScaleHomePercent / 100.0f
        if (scale != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cfg = Configuration(newBase.resources.configuration)
            val metrics = newBase.resources.displayMetrics
            cfg.densityDpi = (metrics.densityDpi * scale).toInt()
            val ctx = newBase.createConfigurationContext(cfg)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        super.onCreate(savedInstanceState)

        logLaunchSource()
        clearBootLoopGuardIfOpenedByHand()

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive) {
            AppLog.i("MainActivity: Active session detected in onCreate, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
            
            // If we are auto-forwarding, hide the splash immediately to avoid flashing it twice
            if (savedInstanceState == null) {
                findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
            }
        }

        setTheme(R.style.AppTheme)
        val mainSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (mainSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (mainSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (mainSettings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyCustomHomeBackground()

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Sync UsbAttachedActivity component state with the listen for USB devices setting.
        // This covers first install, app updates (manifest may reset component state),
        // and ensures the USB system modal only appears when the user has opted in to listen for ALL USB devices.
        lifecycleScope.launch(Dispatchers.IO) {
            Settings.setUsbAttachedActivityEnabled(applicationContext, appSettings.listenForUsbDevices)
        }

        // Start main service immediately to handle connections and wireless server
        val serviceIntent = Intent(this, AapService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // While the full-screen overlay is up, treat Back as cancel so the
                // user isn't trapped if a manual connection attempt hangs. Pill
                // mode is non-blocking, so Back falls through to its normal
                // navigation behavior there.
                if (autoConnectInProgress && autoConnectMode == ConnectionUiMode.OVERLAY) {
                    cancelAutoConnect()
                    return
                }
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (savedInstanceState == null) {
            val elapsedSinceStart = SystemClock.elapsedRealtime() - App.appStartTime
            val targetTotalDuration = 1200L
            val actualDelay = (targetTotalDuration - elapsedSinceStart).coerceAtLeast(0L)
            
            showSplashWithDelay(actualDelay)
        } else {
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        }

        // Runtime permissions are requested by the setup wizard's permissions step on fresh
        // installs; for users who already finished onboarding we request them from checkSetupFlow().
        viewModel.register()
        handleLaunchIntent(intent)
        setupWifiDirectInfo()

        ContextCompat.registerReceiver(
            this, finishReceiver,
            android.content.IntentFilter("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isFinishReceiverRegistered = true

        // USB auto-attach is the one auto-connect path that pre-launches MainActivity
        // before any state transition occurs. HomeFragment-driven auto-connects will
        // call beginAutoConnect() directly from their entry points.
        if (savedInstanceState == null &&
            intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) == "USB auto-start") {
            beginAutoConnect("USB auto-start", ConnectionUiMode.PILL)
        }

        // Wire cancel affordances. Pill click and overlay cancel button both
        // route through the same cancellation path.
        findViewById<View>(R.id.auto_connect_pill)?.setOnClickListener {
            cancelAutoConnect()
        }
        findViewById<View>(R.id.auto_connect_loading_cancel)?.setOnClickListener {
            cancelAutoConnect()
        }

        observeConnectionStateForOverlay()
    }

    /**
     * Mark that an automatic connection attempt has started and surface a status
     * indicator over the home screen. Called from HomeFragment auto-connect paths
     * and from MainActivity itself when launched via USB auto-attach.
     *
     * @param reason Diagnostic label written to the log.
     * @param mode PILL for non-blocking background attempts (home buttons stay
     *        usable), OVERLAY for user-initiated attempts (full-screen custom
     *        loading screen with cancel button).
     * @param customStatusText Optional override for the status text. If provided
     *        and the user has the show-text option enabled, this string is shown
     *        instead of the generic "Android Auto is starting…". Used by the
     *        Nearby selector to surface the picked device name.
     */
    @JvmOverloads
    fun beginAutoConnect(reason: String, mode: ConnectionUiMode, customStatusText: String? = null) {
        if (autoConnectInProgress) return
        val commManager = App.provide(this).commManager
        // If we are already past the connection phase, no indicator is needed.
        if (commManager.isConnected) return
        AppLog.i("Auto-connect: begin ($reason, mode=$mode)")
        autoConnectInProgress = true
        autoConnectMode = mode
        // Seed hasAdvancedToActiveState from the current connection state. If
        // something else (e.g. AapService responding to a USB attach) already
        // moved the state into Connecting before we got here, the StateFlow
        // will not re-emit it, so the observer would never flip the flag to
        // true on its own. Without this seed, a subsequent failure transition
        // to Disconnected would be misread as the initial Disconnected on
        // launch and ignored until the 30 s watchdog kicks in.
        val currentState = commManager.connectionState.value
        hasAdvancedToActiveState = currentState is CommManager.ConnectionState.Connecting ||
                currentState is CommManager.ConnectionState.Connected ||
                currentState is CommManager.ConnectionState.StartingTransport
        autoConnectStatusText = customStatusText
        // Hand the status text off to AapProjectionActivity so its own loading
        // screen continues to show the same context-specific label after the
        // handshake completes and AAP takes over the UI. AAP reads and clears
        // this on its first launch; we always overwrite it here (even with
        // null) so a stale value from a prior attempt can't leak across.
        AapProjectionActivity.pendingStatusText = customStatusText
        showAutoConnectUi()
    }

    /**
     * Dispatches to the pill or overlay show-method based on [autoConnectMode].
     */
    private fun showAutoConnectUi() {
        when (autoConnectMode) {
            ConnectionUiMode.PILL -> showAutoConnectPill()
            ConnectionUiMode.OVERLAY -> showAutoConnectOverlay()
        }
    }

    /**
     * Cancels an in-progress auto-connect attempt, regardless of whether it
     * has reached the Connecting state yet. Safe to call even if no attempt is
     * pending. Invoked by pill tap, overlay cancel button, and back-press when
     * the overlay is up.
     */
    private fun cancelAutoConnect() {
        if (!autoConnectInProgress) return
        AppLog.i("Auto-connect: cancelled by user")
        // disconnect() handles all states including the Connecting state where
        // ACTION_DISCONNECT in AapService used to be a no-op. Setting state to
        // Disconnected here also feeds the observer, but we end the UI
        // immediately rather than waiting for the round-trip.
        App.provide(this).commManager.disconnect()
        endAutoConnect(success = false)
    }

    /**
     * Subscribes to [CommManager.connectionState] to drive the auto-connect overlay.
     *
     * Show: when the overlay is requested via [beginAutoConnect] and the connection
     * advances out of the initial Disconnected state.
     *
     * Hide: on terminal states (HandshakeComplete/TransportStarted = success — AAP
     * projection activity will take over with its own overlay; Error/Disconnected =
     * failure — drop back to HomeFragment so the user can intervene).
     */
    private fun observeConnectionStateForOverlay() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.provide(this@MainActivity).commManager.connectionState.collect { state ->
                    when (state) {
                        is CommManager.ConnectionState.Connecting,
                        is CommManager.ConnectionState.Connected,
                        is CommManager.ConnectionState.StartingTransport -> {
                            hasAdvancedToActiveState = true
                            // The pill/overlay should already be visible (set when auto-connect
                            // was requested); ensure it is in case the request raced with
                            // setContentView or the activity was recreated mid-attempt.
                            if (autoConnectInProgress) {
                                showAutoConnectUi()
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete,
                        is CommManager.ConnectionState.TransportStarted -> {
                            // AapProjectionActivity is launching (HandshakeComplete) or
                            // has launched (TransportStarted). Hide our overlay so we
                            // don't keep video/animation resources alive while AAP
                            // covers us.
                            if (autoConnectInProgress) {
                                AppLog.i("Auto-connect overlay: handshake complete, handing off to projection")
                                endAutoConnect(success = true)
                            }
                        }
                        is CommManager.ConnectionState.Error -> {
                            if (autoConnectInProgress) {
                                AppLog.w("Auto-connect overlay: connection error: ${state.message}")
                                endAutoConnect(success = false)
                            }
                        }
                        is CommManager.ConnectionState.Disconnected -> {
                            // Initial Disconnected on app launch is normal; only treat
                            // as failure if we previously advanced to an active state.
                            if (autoConnectInProgress && hasAdvancedToActiveState) {
                                AppLog.w("Auto-connect overlay: disconnected mid-attempt")
                                endAutoConnect(success = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun endAutoConnect(success: Boolean) {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = null
        autoConnectInProgress = false
        hasAdvancedToActiveState = false
        autoConnectStatusText = null
        if (!success) {
            // Failure path: AAP is not going to launch, so clear the handover
            // value so it can't appear on a later, unrelated connection. On
            // success we leave it alone, AAP either already consumed it in
            // onCreate or is about to.
            AapProjectionActivity.pendingStatusText = null
        }
        // Pill cleanup is cheap and safe to run regardless of mode.
        hideAutoConnectPill()
        if (success) {
            // Launch the projection activity directly rather than waiting for the
            // phone to request video focus (AapControl.gainVideoFocus() -> AapBroadcastReceiver).
            // That broadcast only fires once the phone gets around to its VIDEO
            // mediaSinkSetupRequest, which can lag well behind HandshakeComplete -
            // during that gap this overlay had already hidden itself, leaving the
            // user stuck on HomeFragment with only a relabeled button to notice.
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
            // Hiding without an animation avoids the fade competing with the
            // activity transition.
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
        } else {
            hideAutoConnectOverlay()
        }
    }

    private fun showAutoConnectPill() {
        // Watchdog backstop applies to pill mode too so the indicator can't
        // get stuck if the connection attempt never produces an event.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        val pillText = findViewById<android.widget.TextView>(R.id.auto_connect_pill_text)
        pillText?.text = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        if (pill.visibility == View.VISIBLE) return

        pill.visibility = View.VISIBLE
        pill.bringToFront()
        // Suppress the launch splash if it is still up so the pill is visible
        // immediately on auto-connect from a cold start.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
    }

    private fun hideAutoConnectPill() {
        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        if (pill.visibility != View.VISIBLE) return
        pill.visibility = View.GONE
    }

    private fun startAutoConnectWatchdog() {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = lifecycleScope.launch {
            delay(AUTO_CONNECT_WATCHDOG_MS)
            if (autoConnectInProgress) {
                AppLog.w("Auto-connect overlay: watchdog timeout, hiding")
                endAutoConnect(success = false)
            }
        }
    }

    private fun showAutoConnectOverlay() {
        // Watchdog is owned by this activity's lifecycleScope, so it must be
        // (re)started here to survive configuration changes that recreate the
        // activity while autoConnectInProgress remains true.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility == View.VISIBLE) return

        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        // Once the auto-connect overlay is up there is no point keeping the
        // launch splash around — they would just stack the same dark background.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE

        setupAutoConnectMedia()
    }

    private fun setupAutoConnectMedia() {
        val settings = App.provide(this).settings
        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType

        val defaultContent = findViewById<View>(R.id.auto_connect_loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.auto_connect_loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.auto_connect_loading_custom_video)
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay)

        // Apply the optional custom status text (e.g. "Connecting to Pixel 8…").
        // Falls back to the default "Android Auto is starting…" when no override
        // is set. Both the default and custom-media text overlays share the same
        // string so the wording stays consistent regardless of media presence.
        val statusText = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_default_text)?.text = statusText
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_custom_text)?.text = statusText

        if (mediaPath.isEmpty() || mediaType.isEmpty()) {
            // No custom media — show default text + spinner over the dark backdrop.
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        val file = File(mediaPath)
        if (!file.exists()) {
            // Stored path is stale — reset and fall back to default.
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
        } else {
            customTextOverlay?.visibility = View.GONE
        }

        val keepRatio = settings.loadingScreenKeepAspectRatio
        val scalePercent = settings.loadingScreenScalePercent
        val scale = scalePercent / 100f

        val ov = overlay
        val img = customImage
        if (ov != null && img != null) {
            ov.post {
                val cw = ov.width
                val ch = ov.height
                if (cw > 0 && ch > 0) {
                    val lp = img.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.width = (cw * scale).toInt()
                        lp.height = (ch * scale).toInt()
                        lp.gravity = android.view.Gravity.CENTER
                        img.layoutParams = lp
                    }
                }
            }
        }
        customImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            when (mediaType) {
                "image" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).load(file).into(customImage)
                        if (keepRatio) {
                            autoConnectKenBurnsAnim?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                customImage,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            autoConnectKenBurnsAnim = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).asGif().load(file).into(customImage)
                    }
                }
                "video" -> {
                    customImage?.visibility = View.GONE
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            if (overlay != null) {
                                val cw = overlay.width
                                val ch = overlay.height
                                if (cw > 0 && ch > 0) {
                                    val lp = customVideo.layoutParams as FrameLayout.LayoutParams
                                    if (keepRatio && vw > 0 && vh > 0) {
                                        val videoRatio = vw.toFloat() / vh
                                        val containerRatio = cw.toFloat() / ch
                                        val baseWidth: Int
                                        val baseHeight: Int
                                        if (videoRatio > containerRatio) {
                                            baseWidth = cw
                                            baseHeight = (cw / videoRatio).toInt()
                                        } else {
                                            baseHeight = ch
                                            baseWidth = (ch * videoRatio).toInt()
                                        }
                                        lp.width = (baseWidth * scale).toInt()
                                        lp.height = (baseHeight * scale).toInt()
                                    } else {
                                        lp.width = (cw * scale).toInt()
                                        lp.height = (ch * scale).toInt()
                                    }
                                    lp.gravity = android.view.Gravity.CENTER
                                    customVideo.layoutParams = lp
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Auto-connect overlay: could not resize video: ${e.message}")
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Auto-connect overlay: error playing custom video")
                        // Fall back to default text on video error.
                        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
                        customTextOverlay?.visibility = View.GONE
                        defaultContent?.visibility = View.VISIBLE
                        overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
                        true
                    }
                    customVideo?.start()
                }
            }
        } catch (e: Exception) {
            AppLog.e("Auto-connect overlay: failed to load media: ${e.message}")
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            customTextOverlay?.visibility = View.GONE
            defaultContent?.visibility = View.VISIBLE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
        }
    }

    private fun hideAutoConnectOverlay() {
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility != View.VISIBLE) return

        // Stop video FIRST — VideoView's SurfaceView ignores parent alpha animations
        // and would otherwise stay visible during the fade-out.
        stopAutoConnectVideo()
        autoConnectKenBurnsAnim?.cancel()
        autoConnectKenBurnsAnim = null
        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)?.visibility = View.GONE

        val hasCustomVideo = App.provide(this).settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            overlay.visibility = View.GONE
        } else {
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    private fun stopAutoConnectVideo() {
        findViewById<VideoView>(R.id.auto_connect_loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }

        // Register recreate receiver so SettingsFragment can request MainActivity recreate
        ContextCompat.registerReceiver(
            this, recreateReceiver,
            android.content.IntentFilter(ACTION_RECREATE_MAIN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isRecreateReceiverRegistered = true
    }

    private fun showSplashWithDelay(delayMs: Long) {
        val overlay = findViewById<View>(R.id.splash_overlay) ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupWifiDirectInfo() {
        val tvInfo = findViewById<android.widget.TextView>(R.id.wifi_direct_info)
        val settings = Settings(this)

        lifecycleScope.launch {
            AapService.wifiDirectName.collectLatest { name ->
                val isHelperMode = settings.wifiConnectionMode == 2
                if (isHelperMode && name != null) {
                    tvInfo.text = "WiFi Direct: $name"
                    tvInfo.visibility = View.VISIBLE
                } else {
                    tvInfo.visibility = View.GONE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        // Free media resources whenever the activity is backgrounded. Covers user
        // pressing Home, AapProjectionActivity coming to front (success), and
        // navigating to SettingsActivity. The connection itself keeps running in
        // AapService; this only tears down the visual indicator. The watchdog is
        // intentionally NOT cancelled here so the flag is still cleared if every
        // remaining state transition happens while the activity is stopped.
        if (findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility == View.VISIBLE) {
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
        }
        // Pill has no media resources but should also be hidden so it doesn't
        // briefly flash on resume before the observer re-applies the UI.
        findViewById<View>(R.id.auto_connect_pill)?.visibility = View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * Releases the boot-loop guard, but only when a person opened the app.
     *
     * The distinction matters: the service launches this activity itself on every boot auto-start
     * ([AapService] passes "Boot auto-start"), so clearing unconditionally would reset the guard on
     * exactly the runs it exists to count. Tapping the guard's own notification does count as
     * opening it by hand — that is a person reading the notice and acting on it.
     */
    private fun clearBootLoopGuardIfOpenedByHand() {
        // No source at all is a launcher tap, which is as human as it gets.
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) ?: ""
        if (AUTOMATIC_LAUNCH_SOURCES.contains(source)) return
        Settings.clearBootLoopState(this)
    }

    private fun logLaunchSource() {
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE)
        if (source != null) {
            AppLog.i("App launched via: $source")
            return
        }

        val referrer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.toString()
        } else null

        val isLauncherTap = intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)

        if (isLauncherTap) {
            AppLog.i("App launched by user tap (referrer: ${referrer ?: "none"})")
        } else if (referrer != null) {
            AppLog.i("App launched by third party: $referrer (action: ${intent?.action})")
        } else {
            AppLog.i("App launched, source unknown (action: ${intent?.action})")
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        val intentData = intent.data
        val intentAction = intent.action

        if (intentAction == "com.andrerinas.openheadunit.ACTION_EXIT") {
            AppLog.i("MainActivity: Received exit action")
            val exitIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(this, exitIntent)
            finishAffinity()
            return
        }

        if (intentAction == AapService.ACTION_START_SELF_MODE || 
           (intentData?.scheme == "headunit" && intentData.host == "selfmode")) {
            AppLog.i("MainActivity: Forced self-mode start requested")
            HomeFragment.forceSelfModeLaunch = true
            val selfModeIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_START_SELF_MODE
            }
            ContextCompat.startForegroundService(this, selfModeIntent)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            if (intentData?.scheme == "headunit" && intentData.host == "connect") {
                val ip = intentData.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    AppLog.i("Received connect intent for IP: $ip")
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@MainActivity).commManager.connect(ip, 5277) }
                } else {
                    AppLog.i("Received connect intent without IP -> triggering last session auto-connect")
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            } else if (intentData?.scheme == "headunit" && intentData.host == "disconnect") {
                AppLog.i("Received disconnect intent")
                val stopIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            } else if (intentData?.scheme == "headunit" && intentData.host == "exit") {
                AppLog.i("Received full exit intent via deep link")
                val exitIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                finishAffinity()
            }
        }
    }

    private fun requestPermissions() {
        // Single source of truth: the same registry the wizard/Settings permissions screen use.
        val permissionsToRequest = AppPermissions.missingNormalPermissions(this)

        if (permissionsToRequest.isNotEmpty()) {
            AppLog.i("Requesting missing permissions: $permissionsToRequest")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                permissionRequestCode
            )
        } else {
            AppLog.d("All required permissions already granted.")
        }
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
        applyCustomHomeBackground()

        checkSetupFlow()

        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        ContextCompat.registerReceiver(this, orientationReceiver, android.content.IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive && !AapProjectionActivity.isForeground) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
    }

    fun checkSetupFlow() {
        val appSettings = Settings(this)
        // Show the intelligent onboarding wizard once whenever the stored version is
        // older than the current one (covers fresh installs and upgraders alike).
        val wizardPending = appSettings.onboardingVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION
        if (wizardPending && !OnboardingActivity.deferredThisSession) {
            // The wizard's permissions step handles runtime permissions, so don't also prompt here.
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else if (!wizardPending) {
            // Onboarding already completed (e.g. upgraders): request runtime permissions directly.
            requestPermissions()
        }
        // Wizard deferred this session ("Do it later"): wait until it runs on the next launch.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AppLog.i("dispatchKeyEvent: keyCode=%d, action=%d", event.keyCode, event.action)
        
        // Always give the KeymapFragment (if active) a chance to see the key
        val handled = keyListener?.onKeyEvent(event) ?: false
        
        // If the key was handled by our listener (e.g. in KeymapFragment), stop here
        if (handled) return true
        
        // Otherwise continue with standard handling
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
        }
    }

    fun applyCustomHomeBackground() {
        val customBgImageView = findViewById<ImageView>(R.id.custom_home_background) ?: return
        val path = Settings(this).homeBackgroundImagePath
        val file = if (path.isNotEmpty()) File(path) else null

        if (file != null && file.exists() && file.length() > 0) {
            customBgImageView.visibility = View.VISIBLE
            Glide.with(this)
                .load(file)
                .centerCrop()
                .into(customBgImageView)
        } else {
            try { Glide.with(this).clear(customBgImageView) } catch (_: Exception) {}
            customBgImageView.visibility = View.GONE
        }
    }

    companion object {
        private const val permissionRequestCode = 97
        const val EXTRA_LAUNCH_SOURCE = "launch_source"

        /** Launch sources that mean the app opened itself, with nobody necessarily watching. */
        private val AUTOMATIC_LAUNCH_SOURCES = setOf(
            "Boot auto-start", "USB auto-start", "WiFi auto-start", "Bluetooth auto-start"
        )

        /**
         * Hard upper bound for how long the auto-connect overlay may stay visible
         * without the connection state advancing through the success path. Covers
         * USB open hangs and silent AOA-mode-switch failures, which today produce
         * no event for the observer to react to.
         */
        private const val AUTO_CONNECT_WATCHDOG_MS = 30_000L

        /**
         * `true` while the loading indicator should be (or is) covering the home
         * screen during an automatic connection attempt. Set by entry points
         * that initiate an auto-connect; cleared by [endAutoConnect].
         */
        @Volatile var autoConnectInProgress: Boolean = false

        /**
         * Visual mode for the in-progress attempt. Kept on the companion so a
         * recreated activity (e.g. after rotation) can re-apply the same UI
         * mode the original [beginAutoConnect] caller asked for.
         */
        @Volatile var autoConnectMode: ConnectionUiMode = ConnectionUiMode.OVERLAY

        /**
         * Optional override for the status text (e.g. "Connecting to Pixel 8…"
         * from the Nearby selector). When `null`, the default
         * `R.string.android_auto_starting` is used. Kept on the companion so
         * the customized text isn't lost if the activity is recreated mid
         * attempt.
         */
        @Volatile var autoConnectStatusText: String? = null

        /**
         * Tracks whether the connection attempt has reached an active state
         * (Connecting/Connected/StartingTransport). Used by the connection
         * observer to distinguish a genuine mid-attempt Disconnect (failure)
         * from the initial Disconnected state on app launch (expected). Kept
         * on the companion so a recreated activity does not lose this signal
         * and mistakenly treat a real failure as the initial state.
         */
        @Volatile var hasAdvancedToActiveState: Boolean = false

        const val ACTION_RECREATE_MAIN = "com.andrerinas.openheadunit.ACTION_RECREATE_MAIN"
    }
}
