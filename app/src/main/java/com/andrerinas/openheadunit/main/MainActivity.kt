package com.andrerinas.openheadunit.main

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.app.BtAutoStartRearmPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionNetworkDetail
import com.andrerinas.openheadunit.connection.ConnectionNetworkDetailPolicy
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.PhoneExitQuietPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import android.content.res.Configuration
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.utils.SystemUI
import com.andrerinas.openheadunit.utils.ToastUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private var renderedStage: ConnectionStage? = null
    private var loggedStage: ConnectionStage? = null

    /** Ends the hold after a clean phone-side exit; the flow itself emits nothing at that moment. */
    private var phoneExitQuietJob: Job? = null
    private var renderedNetwork: ConnectionNetworkDetail? = null
    private var autoConnectKenBurnsAnim: ObjectAnimator? = null

    /**
     * Visual mode for an in-progress auto-connect attempt. PILL is a small,
     * non-blocking status indicator at the top of the home screen used for
     * fully automatic background attempts so the home buttons stay tappable.
     * OVERLAY is the full-screen custom loading screen used for connections
     * the user explicitly triggered with a button. PILL_THEN_OVERLAY starts as
     * the pill and becomes the overlay once the connection actually advances,
     * for an attempt whose phone still has to be woken and may never answer.
     */
    enum class ConnectionUiMode { PILL, OVERLAY, PILL_THEN_OVERLAY }

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
            bringProjectionToFront()

            // If we are auto-forwarding, hide the splash immediately to avoid flashing it twice
            if (savedInstanceState == null) {
                findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
            }
        }

        setTheme(R.style.AppTheme)
        val mainSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isExtremeDark = mainSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (mainSettings.useExtremeDarkMode && isNightActive)
        if (isExtremeDark) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (mainSettings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        if (isExtremeDark) {
            findViewById<View>(R.id.splash_overlay)?.setBackgroundColor(
                ContextCompat.getColor(this, R.color.extreme_dark_background)
            )
        }
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
                // Auto/P2P backgrounds its attempt; other overlays retain the Cancel action.
                if (overlayOwnsScreen()) {
                    dismissConnectionOverlay()
                    return
                }
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    ToastUtils.showToast(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT, force = true)
                }
            }
        })

        val launchSource = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) else null

        if (launchSource == "USB auto-start") {
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
            beginAutoConnect("USB auto-start", ConnectionUiMode.OVERLAY)
        } else if (launchSource == LAUNCH_SOURCE_BLUETOOTH) {
            // The connect pill and the Self Mode launch are raised from handleLaunchIntent, so a
            // warm activity handed the same intent behaves the same way.
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        } else if (savedInstanceState == null) {
            val elapsedSinceStart = SystemClock.elapsedRealtime() - App.appStartTime
            val targetTotalDuration = 1200L
            val actualDelay = (targetTotalDuration - elapsedSinceStart).coerceAtLeast(0L)
            findViewById<View>(R.id.splash_overlay)?.bringToFront()
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

        // The pill's X stops the whole bring-up. Auto/P2P can leave its overlay without stopping.
        findViewById<View>(R.id.auto_connect_pill_cancel)?.setOnClickListener {
            cancelBringUp()
        }
        findViewById<View>(R.id.auto_connect_loading_cancel)?.setOnClickListener {
            dismissConnectionOverlay()
        }

        observeConnectionStateForOverlay()
        observeConnectionStage()
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
     * @param statusTextIsWakeClaim true when [customStatusText] says the phone is
     *        disconnected and being woken; such a text is dropped once the phone
     *        answers, so it never follows onto the projection screen.
     */
    @JvmOverloads
    fun beginAutoConnect(
        reason: String,
        mode: ConnectionUiMode,
        customStatusText: String? = null,
        statusTextIsWakeClaim: Boolean = false,
        serverP2p: Boolean = false
    ) {
        // The flag is on the companion and the watchdog on this instance's scope, so an attempt
        // can reach here with nothing watching it at all: spend it if it is over, re-arm it if not.
        endAutoConnectIfExpired()
        if (autoConnectInProgress) {
            ensureAutoConnectWatchdog(rearmed = true)
            return
        }
        val commManager = App.provide(this).commManager
        // If we are already past the connection phase, no indicator is needed.
        if (commManager.isConnected) return
        AppLog.i("Auto-connect: begin ($reason, mode=$mode)")
        autoConnectInProgress = true
        autoConnectMode = mode
        autoConnectServerP2p = serverP2p
        autoConnectDeadlineElapsed =
            AutoConnectAttemptPolicy.deadlineAt(mode, SystemClock.elapsedRealtime())
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
        autoConnectStatusIsWakeClaim = statusTextIsWakeClaim
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
        // Here rather than in the show-methods, so no mode can start an attempt that outlives its
        // own UI. The promotion to OVERLAY comes back through here, and the bound belongs to the
        // attempt rather than to the UI it is wearing.
        ensureAutoConnectWatchdog(rearmed = false)
        when (autoConnectMode) {
            ConnectionUiMode.PILL,
            ConnectionUiMode.PILL_THEN_OVERLAY -> showAutoConnectPill()
            ConnectionUiMode.OVERLAY -> showAutoConnectOverlay()
        }
    }

    private fun dismissConnectionOverlay() {
        if (!autoConnectInProgress) return
        val backgroundMode = AutoConnectAttemptPolicy.modeAfterOverlayDismiss(autoConnectServerP2p)
        if (backgroundMode == null) {
            cancelAutoConnect()
            return
        }
        AppLog.i("Auto/P2P: returning to Home, keeping the connection attempt running")
        autoConnectMode = backgroundMode
        hideAutoConnectOverlay()
        showAutoConnectUi()
    }

    /**
     * Cancels an in-progress auto-connect attempt, regardless of whether it
     * has reached the Connecting state yet. Safe to call even if no attempt is
     * pending. The Auto/P2P overlay's background action deliberately bypasses this.
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
     * The pill's X: ends the attempt this activity may be tracking, then has the service stop the
     * stack and hold it down. [cancelAutoConnect] alone cannot reach the pre-handshake stages,
     * which are the ones the pill spends most of its life showing.
     */
    private fun cancelBringUp() {
        AppLog.i("MainActivity: status pill X pressed, stopping the wireless bring-up")
        cancelAutoConnect()
        ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
            action = AapService.ACTION_CANCEL_WIRELESS
        })
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
                            ConnectionStageTracker.report(
                                if (state is CommManager.ConnectionState.StartingTransport) {
                                    ConnectionStage.SECURING
                                } else {
                                    ConnectionStage.CONNECTING
                                }
                            )
                            // The pill/overlay should already be visible (set when auto-connect
                            // was requested); ensure it is in case the request raced with
                            // setContentView or the activity was recreated mid-attempt.
                            if (autoConnectInProgress) {
                                // A deferred attempt has now proven a phone is answering, so it
                                // may take the full screen.
                                if (autoConnectMode == ConnectionUiMode.PILL_THEN_OVERLAY) {
                                    AppLog.i("Auto-connect: a phone is answering, taking the full screen.")
                                    autoConnectMode = ConnectionUiMode.OVERLAY
                                    hideAutoConnectPill()
                                    // A pill that said this phone was disconnected is answered now,
                                    // so that text must not follow it onto the projection screen.
                                    if (autoConnectStatusIsWakeClaim) {
                                        autoConnectStatusText = null
                                        AapProjectionActivity.pendingStatusText = null
                                    }
                                }
                                showAutoConnectUi()
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete,
                        is CommManager.ConnectionState.TransportStarted -> {
                            ConnectionStageTracker.report(ConnectionStage.STARTING_PROJECTION)
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
        autoConnectServerP2p = false
        autoConnectDeadlineElapsed = 0L
        hasAdvancedToActiveState = false
        autoConnectStatusText = null
        autoConnectStatusIsWakeClaim = false
        if (!success) {
            // Failure path: AAP is not going to launch, so clear the handover
            // value so it can't appear on a later, unrelated connection. On
            // success we leave it alone, AAP either already consumed it in
            // onCreate or is about to.
            AapProjectionActivity.pendingStatusText = null
            // The stack is still armed after a failure, so the pill stays up and falls back to its
            // resting line. beginAttempt, not report: ARMED ranks below whatever it reached.
            if (AutoConnectAttemptPolicy.resetsStageOnFailure(autoConnectMode) &&
                ConnectionStageTracker.stage.value != null
            ) {
                ConnectionStageTracker.beginAttempt(ConnectionStage.ARMED)
            }
            renderStagePill(ConnectionStageTracker.stage.value)
        }
        if (success) {
            // Launch the projection activity directly rather than waiting for the
            // phone to request video focus (AapControl.gainVideoFocus() -> AapBroadcastReceiver).
            // That broadcast only fires once the phone gets around to its VIDEO
            // mediaSinkSetupRequest, which can lag well behind HandshakeComplete -
            // during that gap this overlay had already hidden itself, leaving the
            // user stuck on HomeFragment with only a relabeled button to notice.
            bringProjectionToFront()
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
        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        val pillText = findViewById<TextView>(R.id.auto_connect_pill_text)
        pillText?.text = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        if (pill.visibility == View.VISIBLE) return

        // After the guard, so a pill that is merely changing step still gets the crossfade below.
        // Reading the tracker here is what restores the step line after an activity recreate.
        applyStageText(ConnectionStageTracker.stage.value, animate = false)
        applyNetworkText(ConnectionStageTracker.network.value, animate = false)
        pill.visibility = View.VISIBLE
        pill.bringToFront()
        // If the splash overlay is still showing, keep it on top of the pill until the splash finishes
        findViewById<View>(R.id.splash_overlay)?.let { splash ->
            if (splash.visibility == View.VISIBLE) {
                splash.bringToFront()
            }
        }
    }

    private fun hideAutoConnectPill() {
        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        if (pill.visibility != View.VISIBLE) return
        pill.visibility = View.GONE
    }

    /**
     * Drives the pill off [ConnectionStageTracker]: it is up whenever the connection stack is
     * armed, not only during an auto-connect attempt, and its second line names the current step.
     */
    private fun observeConnectionStage() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStageTracker.stage.collect { renderStagePill(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStageTracker.network.collect { renderNetworkLine(it) }
            }
        }
    }

    /** The pill's third line, logged like the step so a screenshot can be read against the log. */
    private fun renderNetworkLine(detail: ConnectionNetworkDetail?) {
        if (detail != renderedNetwork) {
            renderedNetwork = detail
            AppLog.i("MainActivity: status pill network: ${detail?.let { networkText(it) } ?: "none"}")
        }
        applyNetworkText(detail, animate = true)
    }

    private fun networkText(detail: ConnectionNetworkDetail): String {
        val line = ConnectionNetworkDetailPolicy.lineFor(detail)
        return getString(line.id, *line.args.toTypedArray())
    }

    /** Sets the pill's third line. Not announced: the channel is not a step. */
    private fun applyNetworkText(detail: ConnectionNetworkDetail?, animate: Boolean) {
        val networkText = findViewById<TextView>(R.id.auto_connect_pill_network_text) ?: return
        if (detail == null) {
            networkText.animate().cancel()
            networkText.visibility = View.GONE
            return
        }
        val label = networkText(detail)
        if (networkText.visibility == View.VISIBLE && networkText.text == label) return

        networkText.animate().cancel()
        if (!animate || networkText.visibility != View.VISIBLE) {
            networkText.text = label
            networkText.alpha = if (animate) 0f else STAGE_TEXT_ALPHA
            networkText.visibility = View.VISIBLE
            if (animate) networkText.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
            return
        }
        networkText.animate().alpha(0f).setDuration(STAGE_FADE_OUT_MS).withEndAction {
            networkText.text = label
            networkText.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
        }.start()
    }

    /**
     * Applies the current stage to the pill. Called on every emission and again whenever an
     * attempt ends, because the flow conflates a repeat of the value it already holds.
     */
    private fun renderStagePill(stage: ConnectionStage?) {
        val now = SystemClock.elapsedRealtime()
        val phoneLeftQuiet =
            PhoneExitQuietPolicy.suppressesPill(ConnectionStageTracker.phoneLeftAtMs, now)
        // PILL_THEN_OVERLAY hands the screen to the overlay part-way through an attempt.
        // Re-raising the pill under it would undo that promotion.
        val shown = if (stage == null || overlayOwnsScreen() || phoneLeftQuiet) null else stage
        // What the user is actually being told, which no other line records. A reporter's
        // screenshot and their log can then be read against each other. A step the overlay hides
        // is still named: without that, a session where an auto-connect happened to be in flight
        // and one where it did not leave different traces of the same bring-up.
        if (stage != loggedStage || shown != renderedStage) {
            loggedStage = stage
            renderedStage = shown
            val step = when {
                shown != null -> shown.name
                stage != null && phoneLeftQuiet -> "${stage.name} (not shown, the phone ended the session)"
                stage != null -> "${stage.name} (not shown, the overlay owns the screen)"
                else -> "hidden"
            }
            AppLog.i("MainActivity: status pill step: $step")
        }
        // Nothing emits at the end of the hold, so the step that was withheld needs asking for
        // again; without this the pill stays down until the stack's next step.
        phoneExitQuietJob?.cancel()
        if (phoneLeftQuiet) {
            val waitMs = PhoneExitQuietPolicy.remainingMs(ConnectionStageTracker.phoneLeftAtMs, now)
            phoneExitQuietJob = lifecycleScope.launch {
                delay(waitMs)
                renderStagePill(ConnectionStageTracker.stage.value)
            }
        }
        if (shown == null) {
            hideAutoConnectPill()
        } else {
            showAutoConnectPill()
            applyStageText(shown, animate = true)
        }
    }

    /**
     * Sets the pill's second line. The crossfade doubles as cover for the pill resizing, which it
     * does on every step because it wraps its content and the labels are translated.
     */
    private fun applyStageText(stage: ConnectionStage?, animate: Boolean) {
        val stageText = findViewById<TextView>(R.id.auto_connect_pill_stage_text) ?: return
        if (stage == null) {
            stageText.animate().cancel()
            stageText.visibility = View.GONE
            return
        }
        val label = getString(stage.label)
        if (stageText.visibility == View.VISIBLE && stageText.text == label) return

        stageText.animate().cancel()
        if (!animate) {
            stageText.text = label
            stageText.alpha = STAGE_TEXT_ALPHA
            stageText.visibility = View.VISIBLE
            return
        }
        // A discrete announcement per step, not an accessibilityLiveRegion: the pill is now
        // permanently on screen, and a live region on one of those floods a screen reader.
        findViewById<View>(R.id.auto_connect_pill)?.announceForAccessibility(label)
        if (stageText.visibility != View.VISIBLE) {
            stageText.text = label
            stageText.alpha = 0f
            stageText.visibility = View.VISIBLE
            stageText.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
            return
        }
        stageText.animate().alpha(0f).setDuration(STAGE_FADE_OUT_MS).withEndAction {
            stageText.text = label
            stageText.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
        }.start()
    }

    /**
     * Arms the bound when nothing is watching it. The bound lives on the activity that armed it and
     * that activity does not survive a rebuild, so whoever is alive re-arms what is left of it.
     */
    private fun ensureAutoConnectWatchdog(rearmed: Boolean) {
        if (!autoConnectInProgress) return
        if (autoConnectWatchdog?.isActive == true) return
        startAutoConnectWatchdog(rearmed)
    }

    /** @param rearmed whether this activity inherited the attempt rather than starting it. */
    private fun startAutoConnectWatchdog(rearmed: Boolean) {
        autoConnectWatchdog?.cancel()
        val deadline = autoConnectDeadlineElapsed
        val left = AutoConnectAttemptPolicy.remainingMs(deadline, SystemClock.elapsedRealtime())
        val inherited = if (rearmed) ", re-armed" else ""
        AppLog.i(
            "Auto-connect: this attempt gives up in ${left / 1000}s (mode=$autoConnectMode$inherited)"
        )
        autoConnectWatchdog = lifecycleScope.launch {
            // delay() is a postDelayed on the uptime clock, which stops while the unit is
            // suspended, so the deadline is re-read after each sleep rather than assumed spent.
            var remaining = left
            while (autoConnectInProgress && remaining > 0L) {
                delay(remaining)
                remaining = AutoConnectAttemptPolicy.remainingMs(
                    deadline, SystemClock.elapsedRealtime()
                )
            }
            endAutoConnectIfExpired()
        }
    }

    /** Ends an attempt whose bound has passed, wherever that is first noticed. */
    private fun endAutoConnectIfExpired() {
        if (!autoConnectInProgress) return
        if (!AutoConnectAttemptPolicy.hasExpired(
                autoConnectDeadlineElapsed, SystemClock.elapsedRealtime()
            )
        ) return
        AppLog.w("Auto-connect: nothing answered this attempt (mode=$autoConnectMode), ending it")
        endAutoConnect(success = false)
    }

    private fun showAutoConnectOverlay() {
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_cancel)?.setText(
            if (autoConnectServerP2p) R.string.server_p2p_in_background else R.string.cancel
        )
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
            overlay?.setBackgroundColor(Color.BLACK)
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
                        overlay?.setBackgroundColor(Color.BLACK)
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
            overlay?.setBackgroundColor(Color.BLACK)
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

    fun dismissSplashImmediately() {
        val overlay = findViewById<View>(R.id.splash_overlay) ?: return
        overlay.animate().cancel()
        overlay.visibility = View.GONE
    }

    private fun setupWifiDirectInfo() {
        val tvInfo = findViewById<android.widget.TextView>(R.id.wifi_direct_info)
        val settings = Settings(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(AapService.wifiDirectName, AapService.serverWifiDirectStatus) { name, server ->
                    when {
                        settings.usesServerWifiDirect() && server.groupAvailable ->
                            server.deviceName?.let { getString(R.string.server_p2p_device_name, it) }
                                ?: getString(R.string.server_p2p_active)
                        settings.usesServerWifiDirect() -> null
                        settings.usesVisibleWifiDirect() && name != null -> "WiFi Direct: $name"
                        else -> null
                    }
                }.collectLatest { label ->
                    if (label != null) {
                        tvInfo.text = label
                        tvInfo.visibility = View.VISIBLE
                    } else {
                        tvInfo.visibility = View.GONE
                    }
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

    private fun forceSelfModeLaunch() {
        HomeFragment.forceSelfModeLaunch = true
        val selfModeIntent = Intent(this, AapService::class.java).apply {
            this.action = AapService.ACTION_START_SELF_MODE
        }
        ContextCompat.startForegroundService(this, selfModeIntent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(EXTRA_SHOW_DRIVER_SELECTOR, false)) {
            AppLog.i("MainActivity: EXTRA_SHOW_DRIVER_SELECTOR received")
            HomeFragment.requestDriverSelection = true
            intent.removeExtra(EXTRA_SHOW_DRIVER_SELECTOR)
        }

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
            forceSelfModeLaunch()
        }

        if (intent.getStringExtra(EXTRA_LAUNCH_SOURCE) == LAUNCH_SOURCE_BLUETOOTH) {
            // The service has already been told to arm wireless; this is the non-blocking pill
            // while it does, and the Self Mode launch, which only an activity can start because
            // HomeFragment owns the VPN consent and the projection needs a foreground window.
            val commManager = App.provide(this).commManager
            val settings = App.provide(this).settings
            // The service refuses this arrival while the pill's X holds, and a pill with nothing
            // behind it would otherwise sit there for the watchdog's full deadline.
            if (!commManager.isConnected && AapService.instance?.wirelessCancelledByUser() != true) {
                beginAutoConnect(LAUNCH_SOURCE_BLUETOOTH, ConnectionUiMode.PILL)
            }
            val launchesSelfMode = BtAutoStartRearmPolicy.launchesSelfMode(
                selfSelected = settings.showsSelf(),
                wirelessSelected = settings.showsWifi(),
                mode = settings.wifiConnectionMode,
                sessionUp = commManager.isConnected,
                nativeAttemptInFlight = AapService.instance?.nativeAttemptInFlight()
            )
            if (launchesSelfMode) {
                AppLog.i("MainActivity: Bluetooth auto-start: forcing a Self Mode launch")
                forceSelfModeLaunch()
            } else {
                AppLog.i("MainActivity: Bluetooth auto-start: leaving Self Mode alone")
            }
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
        if (hasRequestedPermissionsThisSession) return
        // Single source of truth: the same registry the wizard/Settings permissions screen use.
        val permissionsToRequest = AppPermissions.missingNormalPermissions(this)

        if (permissionsToRequest.isNotEmpty()) {
            hasRequestedPermissionsThisSession = true
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            hasRequestedPermissionsThisSession = true
            AppLog.i("MainActivity: Permissions request completed.")
        }
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        window.peekDecorView()?.let { v ->
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        }
        setFullscreen()
        applyCustomHomeBackground()

        checkSetupFlow()

        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        ContextCompat.registerReceiver(this, orientationReceiver, android.content.IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive && !AapProjectionActivity.isForeground) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            bringProjectionToFront()
        }

        // Every coroutine bound dies with the activity that owned it, and a resume is the one
        // thing a recreated one always does, so the deadline is read here and re-armed here.
        endAutoConnectIfExpired()
        ensureAutoConnectWatchdog(rearmed = true)

        // Coming back from a failed attempt lands here, so this is where the reason gets said.
        updateConnectionIssueBanner()

        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        val settings = App.provide(this).settings
        if (settings.enableFloatingButton && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                FloatingButtonManager.requestOverlayPermission(this)
            }
        }
    }

    /**
     * Show why the last connection attempt failed, if anything is still wrong.
     *
     * Reads four stored stamps and nothing else - no sockets, no binder - so it is safe to run on
     * every resume. The conditions themselves are detected far from here, on the connection path,
     * and merely recorded; see [ConnectionIssues].
     *
     * Deliberately not driven by CommManager.connectionState. Its Error value is never delivered
     * to a collector - the flow is conflated and startHandshake() overwrites Error with
     * Disconnected before any collector resumes - so a banner hung off it would never appear.
     */
    /** The condition the banner is currently reporting, so the log line is not repeated. */
    private var loggedBannerIssue: ConnectionIssue? = null

    private fun updateConnectionIssueBanner() {
        val banner = findViewById<View>(R.id.connection_issue_banner) ?: return
        val text = findViewById<android.widget.TextView>(R.id.connection_issue_text)
        val dismiss = findViewById<View>(R.id.connection_issue_dismiss)

        val settings = Settings(this)
        val issue = try {
            ConnectionIssueBannerPolicy.bannerFor(
                standing = ConnectionIssues.standing(this),
                dismissedAtEpochMs = settings.connectionIssueDismissedAtEpochMs,
                sessionConnected = App.provide(this).commManager.isConnected,
                onboardingComplete =
                    settings.onboardingVersion >= OnboardingActivity.CURRENT_ONBOARDING_VERSION,
                relevant = ConnectionIssueBannerPolicy.relevantNow(
                    mode = settings.wifiConnectionMode.id,
                    transport = settings.nativeApStrategy,
                    wirelessSelected = settings.showsWifi()
                ),
                remedyApplied = ConnectionIssueBannerPolicy.remedyApplied(
                    hotspotSsid = settings.hotspotSsid,
                    hotspotPassword = settings.hotspotPassword,
                    staticBssid = settings.staticBSSID
                )
            )
        } catch (e: Exception) {
            AppLog.w("MainActivity: could not read the connection issue record: ${e.message}")
            null
        }

        if (issue == null) {
            banner.visibility = View.GONE
            loggedBannerIssue = null
            return
        }

        text?.setText(
            when (issue) {
                ConnectionIssue.BLUETOOTH_SENT_NO_DATA -> R.string.connection_issue_banner_bt_silent
                ConnectionIssue.BSSID_UNAVAILABLE -> R.string.connection_issue_banner_bssid
                ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE -> R.string.connection_issue_banner_hotspot_config
                ConnectionIssue.HOTSPOT_NOT_RUNNING -> R.string.connection_issue_banner_hotspot_off
                ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED ->
                    R.string.connection_issue_banner_wifi_direct_refused
                ConnectionIssue.WIFI_DIRECT_STACK_CYCLED ->
                    R.string.connection_issue_banner_wifi_direct_cycled
                ConnectionIssue.WIFI_RADIO_OFF ->
                    R.string.connection_issue_banner_wifi_radio_off
                ConnectionIssue.VIDEO_LINK_TOO_SLOW ->
                    R.string.connection_issue_banner_video_link_too_slow
                ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED ->
                    R.string.connection_issue_banner_five_ghz_channel_refused
                ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING ->
                    R.string.connection_issue_banner_headunit_server_deaf
                ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE ->
                    R.string.connection_issue_banner_hands_free_held
            }
        )
        banner.setOnClickListener { openRemedyFor(issue) }
        dismiss?.setOnClickListener {
            // Per occurrence, not permanent: the stamp is compared against the next raise, so the
            // banner comes back on its own the next time the same thing happens.
            Settings(this).connectionIssueDismissedAtEpochMs = System.currentTimeMillis()
            banner.visibility = View.GONE
        }
        banner.visibility = View.VISIBLE
        // Once per condition rather than once per resume: this screen is resumed constantly and a
        // line repeated fifty times in a reporter's log buries the one that explains the failure.
        if (loggedBannerIssue != issue) {
            loggedBannerIssue = issue
            AppLog.i("MainActivity: showing the connection issue banner for $issue")
        }
    }

    /**
     * Open Settings on the row that fixes [issue].
     *
     * Seeds the search box rather than using EXTRA_DESTINATION, which targets a whole screen: every
     * remedy is a row inside the settings list, and search is the only thing that reaches them
     * regardless of the Basic/Advanced tier. Static BSSID is Advanced-only, so a Basic-mode user
     * sent to Settings without this would not find the row the banner just named.
     *
     * The hotspot query is a phrase carried in both override rows' search keywords rather than
     * either row's title, because that condition needs *both* of them: with a manual name set the
     * device's own configuration is never read, so a blank password is sent as an open network
     * instead of falling back. Seeding the name alone landed the user on one row, with nothing on
     * screen saying the other was also required.
     */
    private fun openRemedyFor(issue: ConnectionIssue) {
        val query = when (issue) {
            // The remedy is on the phone, so there is no row here to send anyone to.
            ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING -> return
            // The remedy is the other device's Bluetooth connection, which no setting here reaches.
            ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE -> return
            ConnectionIssue.BLUETOOTH_SENT_NO_DATA -> getString(R.string.wireless_mode)
            ConnectionIssue.BSSID_UNAVAILABLE -> getString(R.string.static_bssid_title)
            ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE ->
                getString(R.string.connection_issue_remedy_hotspot_query)
            ConnectionIssue.HOTSPOT_NOT_RUNNING -> getString(R.string.auto_enable_hotspot)
            ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED -> getString(R.string.native_ap_transport)
            ConnectionIssue.WIFI_DIRECT_STACK_CYCLED -> getString(R.string.native_ap_transport)
            ConnectionIssue.WIFI_RADIO_OFF -> getString(R.string.native_ap_transport)
            ConnectionIssue.VIDEO_LINK_TOO_SLOW -> getString(R.string.fps_limit)
            ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED -> getString(R.string.wifi_direct_band)
        }
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SEARCH_QUERY, query)
        )
    }

    /**
     * The one place the projection relaunch intent is built. The guards stay at the call sites
     * on purpose - they differ (post-connect must launch unconditionally, onResume checks
     * foreground) - but the flag recipe must not drift between copies: this launch is what
     * rebuilds the projection after singleTask semantics tore it down, and the torn-down
     * instance's late surface callback is exactly the stale-owner case the decoder's ownership
     * gate exists for.
     */
    private fun bringProjectionToFront() {
        val aapIntent = AapProjectionActivity.intent(this).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(aapIntent)
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
        // Registered in stopAutoConnectVideo(), so without this it outlives the activity that owns
        // it: every destroyed instance leaves a receiver behind that answers the next recreate
        // request by calling recreate() on an activity that is already gone.
        if (isRecreateReceiverRegistered) {
            unregisterReceiver(recreateReceiver)
            isRecreateReceiverRegistered = false
        }
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
            hasRequestedPermissionsThisSession = false
        }
    }

    fun applyCustomHomeBackground() {
        findViewById<ImageView>(R.id.custom_home_background)?.let {
            try { Glide.with(this).clear(it) } catch (_: Exception) {}
            it.setImageDrawable(null)
            it.visibility = View.GONE
        }
        applyWindowBackground()
    }

    companion object {
        private const val permissionRequestCode = 97
        @Volatile var hasRequestedPermissionsThisSession: Boolean = false
        const val EXTRA_LAUNCH_SOURCE = "launch_source"
        const val LAUNCH_SOURCE_BLUETOOTH = "Bluetooth auto-start"
        const val EXTRA_SHOW_DRIVER_SELECTOR = "show_driver_selector"

        /** Launch sources that mean the app opened itself, with nobody necessarily watching. */
        private val AUTOMATIC_LAUNCH_SOURCES = setOf(
            "Boot auto-start", "USB auto-start", "WiFi auto-start", LAUNCH_SOURCE_BLUETOOTH
        )

        private const val STAGE_TEXT_ALPHA = 0.8f
        private const val STAGE_FADE_OUT_MS = 100L
        private const val STAGE_FADE_IN_MS = 150L

        /**
         * `true` while the loading indicator should be (or is) covering the home
         * screen during an automatic connection attempt. Set by entry points
         * that initiate an auto-connect; cleared by [endAutoConnect].
         */
        @Volatile var autoConnectInProgress: Boolean = false
        @Volatile private var autoConnectServerP2p: Boolean = false

        /**
         * Visual mode for the in-progress attempt. Kept on the companion so a
         * recreated activity (e.g. after rotation) can re-apply the same UI
         * mode the original [beginAutoConnect] caller asked for.
         */
        @Volatile var autoConnectMode: ConnectionUiMode = ConnectionUiMode.OVERLAY

        /**
         * When the in-progress attempt runs out, on the clock that keeps running while the unit
         * sleeps. On the companion with the flag it bounds, so a recreated activity re-arms for
         * the time that is left instead of starting the bound again or losing it.
         */
        @Volatile var autoConnectDeadlineElapsed: Long = 0L

        /**
         * Whether the full-screen overlay is up, which is the only auto-connect UI that takes the
         * screen. The pill is not: it is up whenever the stack is armed, so treating it as busy
         * suppresses anything that waits for a free screen for as long as the app runs.
         */
        @JvmStatic
        fun overlayOwnsScreen(): Boolean =
            autoConnectInProgress && autoConnectMode == ConnectionUiMode.OVERLAY

        /**
         * Optional override for the status text (e.g. "Connecting to Pixel 8…"
         * from the Nearby selector). When `null`, the default
         * `R.string.android_auto_starting` is used. Kept on the companion so
         * the customized text isn't lost if the activity is recreated mid
         * attempt.
         */
        @Volatile var autoConnectStatusText: String? = null

        /** Whether [autoConnectStatusText] claims the phone is disconnected and being woken. */
        @Volatile var autoConnectStatusIsWakeClaim: Boolean = false

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
