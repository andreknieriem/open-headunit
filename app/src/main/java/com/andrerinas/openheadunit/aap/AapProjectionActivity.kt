package com.andrerinas.openheadunit.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.app.SurfaceActivity
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.contract.KeyIntent
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.decoder.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.SoftwareYuvFrameSink
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.decoder.VideoDimensionsListener
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.IntentFilters
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.GlProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.TextureProjectionView
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.view.OverlayTouchView
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.SystemUI
import com.andrerinas.openheadunit.aap.AapService
import android.content.IntentFilter
import com.andrerinas.openheadunit.view.ProjectionViewScaler
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.widget.ImageView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.andrerinas.openheadunit.main.QuickSettingsFragment
import com.andrerinas.openheadunit.main.RenameNotice
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private enum class OverlayState { STARTING, RECONNECTING, HIDDEN }

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private val cachedKeyCodes: Map<Int, Int> by lazy { settings.keyCodes }
    private var isSurfaceSet = false
    private var overlayState = OverlayState.STARTING
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Ken Burns scale animation applied to a static image loading screen.
     * Stored in a field so it can be cancelled when the loading overlay is
     * torn down or the activity is destroyed — otherwise the infinite-repeat
     * animator keeps consuming frame callbacks even when the view is gone.
     */
    private var kenBurnsAnimator: ObjectAnimator? = null

    private var initialX = 0f
    private var initialY = 0f
    private var isPotentialGesture = false

    // Activity-local override for fullscreen mode. If non-null, setFullscreen() will use this
    // instead of persisting to Settings. This keeps toggles local to the Activity lifecycle.
    private var activityFullscreenOverride: Settings.FullscreenMode? = null
    private var fpsTextView: TextView? = null
    private var touchOverlayView: OverlayTouchView? = null
    private var currentFps: Int? = null

    // Named rather than inline so onDestroy can tell this instance's listener apart from a
    // relaunched instance's before clearing it - lambdas have no usable identity across instances.
    private val fpsListener: (Int) -> Unit = { fps -> currentFps = fps }
    private val performanceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val performanceSampler = PerformanceSampler()
    private val performanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PerformanceSampler").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val performanceSampleInFlight = AtomicBoolean(false)
    private val performanceOverlayRunnable = object : Runnable {
        override fun run() {
            requestPerformanceOverlayUpdate()
            performanceHandler.postDelayed(this, 1000L)
        }
    }

    private var isOrientationReceiverRegistered = false
    private var isNightModeReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isKeyEventReceiverRegistered = false
    private var isSettingsReceiverRegistered = false

    /**
     * Asks for video while the surface the decoder holds has never shown a picture.
     *
     * Deliberately **not** gated on the loading overlay any more. It was, and that let a cosmetic
     * decision end recovery: `onCreate` hides the overlay when the previous instance had rendered,
     * and this runnable does not re-post when it declines, so one relaunch in two lost the loop on
     * its first tick and sat black with nothing asking. See
     * [ProjectionWatchdogPolicy.shouldNudgeForFirstFrame].
     *
     * It still hides the overlay when it finds a picture, because that is a fact about the picture
     * rather than a condition on running.
     */
    private val videoWatchdogRunnable = object : Runnable {
        override fun run() {
            val rendered = videoDecoder.lastFrameRenderedMs > 0
            if (rendered) {
                val loadingOverlay = findViewById<View>(R.id.loading_overlay)
                if (loadingOverlay?.visibility == View.VISIBLE) {
                    AppLog.i("Watchdog: Decoder is already rendering frames. Hiding overlay.")
                    hideLoadingOverlay(loadingOverlay)
                }
            }
            if (!ProjectionWatchdogPolicy.shouldNudgeForFirstFrame(
                    sessionLive = ProjectionWatchdogPolicy.isSessionLive(commManager.connectionState.value),
                    surfaceSet = isSurfaceSet,
                    renderedSinceSurfaceSet = rendered,
                    warmRelaunchCycleSpent = warmRelaunchCycleSpent,
                )
            ) return

            AppLog.w("Watchdog: No video received yet. Requesting Keyframe (Unsolicited Focus)...")
            // Shares one throttle clock with the reconnecting watchdog's mid-session
            // re-request, so the two never double-fire across the overlay transition.
            lastVideoFocusRequestMs = SystemClock.elapsedRealtime()
            commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            watchdogHandler.postDelayed(this, 1500)
        }
    }
    private val reconnectingWatchdog = object : Runnable {
        override fun run() {
            // Bail without re-posting only when the session is genuinely over; onResume re-arms
            // on the next entry. The steady state during projection is TransportStarted, and
            // checking only HandshakeComplete here killed this watchdog on its first tick of
            // every session - which is why a video stream that died mid-session stayed black
            // with nothing ever asking for it back.
            if (!ProjectionWatchdogPolicy.isSessionLive(commManager.connectionState.value)) {
                return
            }
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame == 0L) {
                // First frame hasn't arrived yet — handled by the starting overlay. If the phone is
                // streaming video but nothing draws, offer to switch renderer (issue #767).
                maybeOfferRendererConfirm()
                // A relaunch lands here too, and used to get nothing else: the overlay is already
                // hidden (the previous instance had rendered), so its keyframe watchdog never
                // re-arms, and maybeRequestVideoFocus below is never reached.
                maybeRecoverWarmRelaunch()
                watchdogHandler.postDelayed(this, ProjectionWatchdogPolicy.WATCHDOG_TICK_MS)
                return
            }
            val gap = SystemClock.elapsedRealtime() - lastFrame
            val linkQuiet = linkQuietMs()
            val pictureStopped = gap > ProjectionWatchdogPolicy.FRAME_GAP_MS
            if (overlayState == OverlayState.HIDDEN &&
                ProjectionWatchdogPolicy.shouldShowReconnecting(gap, linkQuiet)
            ) {
                AppLog.w(
                    "AapProjectionActivity: picture idle for ${gap}ms and the link has been silent " +
                        "for ${describeQuiet(linkQuiet)} - treating this as a lost connection"
                )
                showReconnectingOverlay()
            } else if (overlayState == OverlayState.RECONNECTING && gap < 2000) {
                hideReconnectingOverlay()
            } else {
                if (!pictureStopped) {
                    lastIdleReportMs = 0L
                } else if (overlayState == OverlayState.HIDDEN) {
                    reportIdlePicture(gap, linkQuiet)
                }
                // Decoder producing but display possibly frozen (issue #650).
                maybeRecoverFromDisplayStall()
            }
            maybeRequestVideoFocus(pictureStopped)
            watchdogHandler.postDelayed(this, ProjectionWatchdogPolicy.WATCHDOG_TICK_MS)
        }
    }

    private var lastVideoFocusRequestMs = 0L

    // Throttle for the idle-picture line. Cleared the moment frames resume, so each idle stretch
    // reports immediately instead of inheriting the previous one's window.
    private var lastIdleReportMs = 0L
    private val idleReportCooldownMs = 10000L

    // Age and escalation state of the surface the decoder currently renders to. Reset together in
    // onSurfaceChanged, so each relaunch gets exactly one focus cycle.
    private var lastSurfaceSetMs = 0L
    private var warmRelaunchCycleSpent = false

    /**
     * A relaunch handed the decoder a fresh surface and no picture has followed it.
     *
     * The phone is the bottleneck here, not the rebuild: measured across ten TextureView/GLES
     * returns, the relaunch took 384-507 ms and codec creation 49-308 ms, while waiting for a
     * decodable picture afterwards took 6.3-115.9 s - 97-99% of the whole return. On the SurfaceView
     * backend, whose teardown releases video focus and so makes the phone re-run sink setup, the
     * same wait was 42-96 ms.
     *
     * Repeating the unsolicited gain does not close that gap; dozens go out per slow return and none
     * is ever followed by a picture. [WarmRelaunchKeyframePolicy] decides when to escalate to the
     * release/regain cycle instead, and why each gate is there.
     */
    private fun maybeRecoverWarmRelaunch() {
        if (lastSurfaceSetMs == 0L) return
        val now = SystemClock.elapsedRealtime()
        val action = WarmRelaunchKeyframePolicy.decide(
            sessionHasRendered = videoDecoder.hasRenderedThisSession,
            renderedSinceSurfaceSet = videoDecoder.lastFrameRenderedMs != 0L,
            transportStarted = commManager.connectionState.value is CommManager.ConnectionState.TransportStarted,
            msSinceSurfaceSet = now - lastSurfaceSetMs,
            // The link, not the video channel. An idle Android Auto screen sends no video for
            // minutes at a time and still answers on the link, and reading video bytes here shut
            // this escalation for the whole of exactly the case it is needed in.
            msSinceLinkActivity = linkQuietMs(),
            linkQuietThresholdMs = ProjectionWatchdogPolicy.LINK_QUIET_MS,
            cycleAlreadySpent = warmRelaunchCycleSpent,
            msSinceLastRequest = now - lastVideoFocusRequestMs
        )
        when (action) {
            WarmRelaunchKeyframePolicy.Action.NONE -> return
            WarmRelaunchKeyframePolicy.Action.NUDGE -> {
                lastVideoFocusRequestMs = now
                AppLog.w("AapProjectionActivity: relaunched surface still has no picture - requesting video focus (unsolicited)")
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            WarmRelaunchKeyframePolicy.Action.CYCLE_FOCUS -> {
                // The transport's own escalation spends the same lever. If it holds it, no release
                // went out for this policy to complete, so nothing here may be marked as spent -
                // and the keyframe that cycle brings is the one this was asking for anyway.
                if (!commManager.releaseVideoFocusForKeyframe()) {
                    AppLog.w("AapProjectionActivity: relaunched surface has no picture, but a focus cycle is already in flight - waiting for it")
                    return
                }
                warmRelaunchCycleSpent = true
                lastVideoFocusRequestMs = now
                AppLog.w("AapProjectionActivity: relaunched surface has no picture after ${now - lastSurfaceSetMs}ms - cycling video focus")
                focusCycleGainPending = true
                watchdogHandler.removeCallbacks(focusCycleGainRunnable)
                watchdogHandler.postDelayed(
                    focusCycleGainRunnable,
                    WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS
                )
            }
        }
    }

    /**
     * Runs the escalation check one window after a surface is claimed, instead of waiting for the
     * reconnecting watchdog to reach it.
     *
     * That watchdog is the backstop, not the trigger: it first ticks 5 s after onResume and every
     * 2 s after that, so leaving the escalation to it would put a floor under the recovery well
     * above the window the policy actually specifies. Driving it from the surface keeps the cost at
     * the window itself.
     */
    private val warmRelaunchCheckRunnable = Runnable { maybeRecoverWarmRelaunch() }

    private var focusCycleGainPending = false

    /**
     * Second half of the focus cycle - see [WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS] for why
     * it is not sent with the release.
     */
    private val focusCycleGainRunnable = Runnable {
        focusCycleGainPending = false
        AppLog.w("AapProjectionActivity: retaking video focus to complete the keyframe cycle")
        commManager.retakeVideoFocusForKeyframe()
    }

    /**
     * Completes a focus cycle early rather than dropping it.
     *
     * The release and the gain are one operation split across a delay, so anything that cancels the
     * pending half has to send it instead of forgetting it. Left released, the phone stops the video
     * sink and nothing ever asks for it back - a permanent black screen with audio still playing,
     * which is a worse outcome than the slow return the cycle exists to fix.
     */
    private fun settleFocusCycle() {
        if (!focusCycleGainPending) return
        watchdogHandler.removeCallbacks(focusCycleGainRunnable)
        focusCycleGainRunnable.run()
    }

    /**
     * How long ago the phone last sent anything at all, on any AAP channel.
     *
     * [Long.MAX_VALUE] when it has not sent anything yet this session. That case has to be spelled
     * out rather than left to arithmetic: `lastAapMessageMs` is 0 until the first message arrives,
     * and `now - 0` is a very large number that reads as a *silent* link - so the obvious
     * subtraction would report a session that has merely not started as one that has died.
     */
    private fun linkQuietMs(): Long {
        val last = commManager.lastAapMessageMs
        if (last == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - last
    }

    /** Renders [linkQuietMs] for a log line, so "not yet" never prints as 9223372036854775807ms. */
    private fun describeQuiet(quietMs: Long): String =
        if (quietMs == Long.MAX_VALUE) "the whole session" else "${quietMs}ms"

    /**
     * Records that the picture stopped without the link doing so - the case that used to be shown
     * to the user as a lost connection.
     *
     * Android Auto sends no video at all while nothing on screen animates, so this is the normal
     * state of a paused full-screen music player, and the overlay it produced was the whole of
     * issue #852. The line stays because [ProjectionWatchdogPolicy.LINK_QUIET_MS] is a judgement
     * and not yet a measurement: every one of these carries the phone's real idle cadence, so two
     * reporter logs are enough to replace the guess with a number.
     *
     * Rate-limited to one per idle stretch rather than one per 2s tick.
     */
    private fun reportIdlePicture(gapMs: Long, quietMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastIdleReportMs != 0L && now - lastIdleReportMs < idleReportCooldownMs) return
        lastIdleReportMs = now
        AppLog.w(
            "AapProjectionActivity: picture idle for ${gapMs}ms but the link spoke " +
                "${describeQuiet(quietMs)} ago - Android Auto has stopped sending, not disconnected"
        )
    }

    /**
     * Mid-session recovery: the connection is proven live (the state check above) but no frame has
     * arrived for [ProjectionWatchdogPolicy.FRAME_GAP_MS], so ask the phone for video again.
     * Without this, the one unsolicited focus gain sent when a surface appears was the only request
     * in the whole session - if the stream stopped after that, nothing ever asked for it back and
     * the screen stayed black until the app was killed.
     *
     * Gated on [pictureStopped] and deliberately **not** on the reconnecting overlay, which is what
     * it used to read. The overlay now also requires the link to have gone quiet (issue #852), and
     * an idle-looking stream is exactly the shape a genuinely stalled one has - so tying recovery
     * to the overlay would leave the stalled case with nothing asking for video back, which is the
     * failure this watchdog was revived to fix.
     */
    private fun maybeRequestVideoFocus(pictureStopped: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!ProjectionWatchdogPolicy.shouldRequestVideoFocus(
                pictureStopped, now, lastVideoFocusRequestMs
            )
        ) return
        lastVideoFocusRequestMs = now
        AppLog.w("AapProjectionActivity: connected but no frames - requesting video focus (unsolicited)")
        commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
    }
    private val exitRunnable = Runnable {
        if (commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
            AppLog.i("AapProjectionActivity: Reconnect timed out (20s). Finishing activity.")
            hideReconnectingOverlay()
            finish()
        }
    }
    private val watchdogRunnable = Runnable {
        if (!isSurfaceSet) {
            AppLog.w("Watchdog: Surface not set after 2s. Checking view state...")
            checkAndForceSurface()
        }
    }
    private fun checkAndForceSurface() {
        AppLog.i("Watchdog: checkAndForceSurface executing...")
        if (projectionView is TextureView) {
            val tv = projectionView as TextureView
            if (tv.isAvailable) {
                AppLog.w("Watchdog: TextureView IS available. Forcing onSurfaceChanged.")
                onSurfaceChanged(android.view.Surface(tv.surfaceTexture), tv.width, tv.height)
            } else {
                AppLog.e("Watchdog: TextureView NOT available. Vis=${tv.visibility}, W=${tv.width}, H=${tv.height}")
            }
        } else if (projectionView is GlProjectionView) {
             val gles = projectionView as GlProjectionView
             if (gles.isSurfaceValid()) {
                 AppLog.w("Watchdog: GlProjectionView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(gles.getSurface()!!, gles.width, gles.height)
             } else {
                 AppLog.e("Watchdog: GlProjectionView NOT valid.")
             }
        } else if (projectionView is ProjectionView) {
             val sv = projectionView as ProjectionView
             if (sv.holder.surface.isValid) {
                 AppLog.w("Watchdog: SurfaceView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(sv.holder.surface, sv.width, sv.height)
             } else {
                 AppLog.e("Watchdog: SurfaceView NOT valid.")
             }
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val needsViewRecreate = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_NEEDS_VIEW_RECREATE, false)
            val needsAudioRestart = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_NEEDS_AUDIO_RESTART, false)
            val sensorRefresh = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_SENSOR_REFRESH, false)
            val applyFullscreen = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_APPLY_FULLSCREEN, false)

            if (needsViewRecreate) {
                recreateProjectionView()
            }
            if (applyFullscreen) {
                setFullscreen()
            }
            if (sensorRefresh) {
                sendBroadcast(Intent(AapService.ACTION_REFRESH_SENSORS).apply {
                    setPackage(packageName)
                })
            }
            if (needsAudioRestart) {
                sendBroadcast(Intent(AapService.ACTION_RESTART_AUDIO).apply {
                    setPackage(packageName)
                })
            }

            updateDesaturation(com.andrerinas.openheadunit.utils.NightMode(settings, false).current)

            if (settings.showFpsCounter && fpsTextView == null) {
                setupFpsCounter()
            } else if (!settings.showFpsCounter && fpsTextView != null) {
                fpsTextView?.visibility = View.GONE
                stopPerformanceOverlayUpdates()
            } else if (settings.showFpsCounter && fpsTextView != null) {
                fpsTextView?.visibility = View.VISIBLE
                startPerformanceOverlayUpdates()
            }
        }
    }

    private fun recreateProjectionView() {
        runOnUiThread {
            AppLog.i("Recreating projection view due to settings change...")
            val container = findViewById<FrameLayout>(R.id.container)
            if (::projectionView.isInitialized) {
                // Deregister before discarding the view: the GLES backend posts its
                // onSurfaceDestroyed to the main looper, so a still-registered callback from the
                // discarded view would land after the replacement's onSurfaceChanged and release
                // video focus for the new view's running stream.
                projectionView.removeCallback(this)
                videoDecoder.softwareYuvFrameSink = null
                videoDecoder.stop(DecoderStopPolicy.REASON_PROJECTION_VIEW_RECREATE)
                container.removeView(projectionView as View)
            }
            isSurfaceSet = false
            setupProjectionView()
            val mirror = if (settings.hudMirroring) -1.0f else 1.0f
            findViewById<View>(R.id.loading_overlay)?.scaleX = mirror
            fpsTextView?.scaleX = mirror
        }
    }

    // Issue #650: on some head units (notably MediaTek in GLES mode) the display consumer
    // stops putting frames on screen while the phone keeps streaming video, so the picture
    // freezes (often on Android Auto's boot logo) even though audio keeps playing. The known
    // manual workaround is Home + reopen, which rebuilds the surface. This reproduces that
    // automatically, and if a device keeps stalling it escalates to SurfaceView (the most
    // direct path, which avoids the external-texture route that is the demonstrated bottleneck)
    // for the rest of the session.
    //
    // Detection is gated on the phone still sending video bytes (videoDecoder.lastInputBytesReceivedMs)
    // so it never fights a genuine phone-side pause (which stops the input too and is left to the
    // reconnecting overlay). It then looks for two failure shapes on the consumer side:
    //   - a full freeze: nothing drawn for ProjectionWatchdogPolicy.DISPLAY_FREEZE_MS, and
    //   - a throughput collapse: several abnormally long frames within a sliding window. On MediaTek
    //     the GL consumer does not fully stop but drops to 2-5fps with single frames taking ~2s, so
    //     a plain "no frame for N seconds" check misses it (issue #650).
    // "The phone is still streaming video" - for [maybeRecoverFromDisplayStall] only, where that is
    // the right question: that path is about the display consumer freezing while video flows. The
    // warm-relaunch escalation used to share it and asks about the link instead, because there a
    // stream with no video is the normal idle screen rather than evidence of anything.
    private val phoneAliveThresholdMs = 1500L
    private val displayStallRecoveryCooldownMs = 10000L
    private val displayStallRecoveryResetMs = 60000L
    private val maxDisplayStallRecoveries = 4
    private val collapseLongFrameFloor = 10L
    private var displayStallRecoveries = 0
    private var lastDisplayStallRecoveryMs = 0L
    private var firstUndrawnMs = 0L
    // Sliding window of long frames per tick, with the clock reading each slot was written at.
    // The times are what make it a window: this check returns early whenever the phone is not
    // currently sending video, so the ticks that run are not evenly spaced and counting slots alone
    // turns ten seconds into however long five surviving ticks happened to span. See
    // ProjectionWatchdogPolicy.longFramesInWindow.
    private val longFrameTickWindow = LongArray(5)
    private val longFrameTickTimes = LongArray(5)
    private var longFrameTickIndex = 0
    private var prevLongFrameCount = 0L
    private var prevLongFrameTickMs = 0L
    // Session-scoped backend override applied after repeated stalls. Never persisted, so the
    // user's chosen viewMode is restored on the next launch.
    private var forcedViewModeOverride: Settings.ViewMode? = null

    // Renderer confirmation banner (issue #767): lets the user escape a wrong/broken renderer that
    // leaves a black screen while audio keeps working. A broken SurfaceView cannot be detected
    // automatically (it reports no drawn frames), so we ask the user directly.
    private var rendererBanner: View? = null
    private var rendererConfirmResolved = false
    private var projectionStartMs = 0L
    private val rendererConfirmNoFrameMs = 6000L
    // "Phone still streaming" window for the offer. Kept comfortably above the 2s watchdog interval
    // so a burst of keyframes (~every 2s on the AA logo) doesn't fall outside the window and hide
    // the offer for a genuinely connected phone.
    private val rendererConfirmPhoneAliveMs = 4000L

    private fun maybeRecoverFromDisplayStall() {
        if (!::projectionView.isInitialized) return
        if (overlayState != OverlayState.HIDDEN) return
        // SurfaceView has no per-frame draw callback (-1) and is already the robust fallback path.
        val drawn = projectionView.lastFrameDrawnMs()
        if (drawn < 0L) return
        val input = videoDecoder.lastInputBytesReceivedMs
        if (input <= 0L) return
        val now = SystemClock.elapsedRealtime()
        // Only act while the phone is still streaming video.
        if (now - input > phoneAliveThresholdMs) return

        // If the view is actively drawing frames (drawn within the last 2s), DO NOT tear down the surface.
        // Recreating the view mid-stream causes black screen flashes, EGL disconnection, and touch loss.
        if (drawn > 0L && (now - drawn < 2000L)) {
            return
        }

        // Slide the long-frame window (this runs ~every 2s from the reconnecting watchdog, but only
        // on the ticks that get past the gates above, which is why each slot carries its time).
        val longFrames = projectionView.longFrameEvents()
        longFrameTickWindow[longFrameTickIndex] = ProjectionWatchdogPolicy.longFramesThisTick(
            longFrameEvents = longFrames,
            previousEvents = prevLongFrameCount,
            previousTickMs = prevLongFrameTickMs,
            nowMs = now
        )
        longFrameTickTimes[longFrameTickIndex] = now
        longFrameTickIndex = (longFrameTickIndex + 1) % longFrameTickWindow.size
        prevLongFrameCount = longFrames
        prevLongFrameTickMs = now
        val longFramesInWindow = ProjectionWatchdogPolicy.longFramesInWindow(
            longFrameTickWindow, longFrameTickTimes, now
        )

        // Baseline for the case where the consumer never drew a single frame after the overlay was
        // dismissed (drawn stays 0): time it from when that state was first seen.
        if (drawn == 0L) {
            if (firstUndrawnMs == 0L) firstUndrawnMs = now
        } else {
            firstUndrawnMs = 0L
        }
        val effectiveDrawn = if (drawn == 0L) firstUndrawnMs else drawn

        val frozen = effectiveDrawn > 0L && now - effectiveDrawn >= ProjectionWatchdogPolicy.DISPLAY_FREEZE_MS
        val collapsed = longFramesInWindow >= collapseLongFrameFloor

        if (!frozen && !collapsed) {
            // Healthy: after a sustained good period, re-arm recovery so a later stall on a long
            // drive is still handled.
            if (displayStallRecoveries > 0 && now - lastDisplayStallRecoveryMs > displayStallRecoveryResetMs) {
                displayStallRecoveries = 0
            }
            return
        }

        if (now - lastDisplayStallRecoveryMs < displayStallRecoveryCooldownMs) return
        if (displayStallRecoveries >= maxDisplayStallRecoveries) return
        displayStallRecoveries++
        lastDisplayStallRecoveryMs = now
        val reason = if (collapsed) "$longFramesInWindow slow frames in window" else "no draw for ${now - effectiveDrawn}ms"

        val effectiveMode = forcedViewModeOverride ?: settings.viewMode
        // After a plain rebuild fails to stick, escalate away from a non-SurfaceView backend
        // (unless the bundled software HEVC decoder is active, which needs the GLES YUV sink).
        val shouldFallBack = displayStallRecoveries >= 2 &&
            effectiveMode != Settings.ViewMode.SURFACE &&
            !videoDecoder.usingBundledSoftwareHevc
        if (shouldFallBack) {
            AppLog.w("Display stall ($reason) again on $effectiveMode. Falling back to SurfaceView for this session. See issue #650.")
            forcedViewModeOverride = Settings.ViewMode.SURFACE
            com.andrerinas.openheadunit.utils.ToastUtils.showToast(this, R.string.renderer_fallback_surface, duration = android.widget.Toast.LENGTH_LONG, force = true)
            // SurfaceView can't be observed for stalls (issue #767); if it too shows black, offer the
            // manual escape so the user isn't stranded on the terminal fallback.
            showRendererConfirmBanner("the SurfaceView fallback is also showing black")
        } else {
            AppLog.w("Display stall ($reason). Rebuilding projection view (attempt $displayStallRecoveries). See issue #650.")
        }
        // The rebuilt view starts its counters from zero.
        firstUndrawnMs = 0L
        prevLongFrameCount = 0L
        prevLongFrameTickMs = 0L
        longFrameTickWindow.fill(0L)
        longFrameTickTimes.fill(0L)
        recreateProjectionView()
    }

    /** Offer the renderer confirmation when the phone is actively streaming video but nothing has
     * been drawn for a while (the broken-renderer case that cannot be detected automatically). */
    private fun maybeOfferRendererConfirm() {
        if (rendererConfirmResolved || rendererBanner != null) return
        if (videoDecoder.lastFrameRenderedMs > 0L) return
        val input = videoDecoder.lastInputBytesReceivedMs
        if (input <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - input > rendererConfirmPhoneAliveMs) return
        if (projectionStartMs == 0L || now - projectionStartMs < rendererConfirmNoFrameMs) return
        showRendererConfirmBanner("the phone is streaming and nothing has drawn")
    }

    /**
     * A dismissible bottom bar: "Do you see the screen?" with Yes / Switch renderer.
     *
     * @param reason which of the four situations armed it, for the log. They are four different
     *   things - a terminal fallback that is also black, a phone streaming into a blank screen, a
     *   renderer just switched, and the setup wizard asking for confirmation - and they used to
     *   produce one indistinguishable line. A round saw the banner with `pendingRendererConfirm`
     *   persisted false and had to reason out which path had armed it.
     */
    private fun showRendererConfirmBanner(reason: String) {
        runOnUiThread {
            if (rendererBanner != null || rendererConfirmResolved) return@runOnUiThread
            val container = findViewById<FrameLayout>(R.id.container) ?: return@runOnUiThread
            // Said out loud because until now nothing was. A session sitting behind this banner
            // logs exactly like a connected one showing a static screen, and a hardware round lost
            // a whole capture to the difference - only a screenshot told the two apart.
            AppLog.w(
                "AapProjectionActivity: the renderer confirmation banner is up (%s) - projection " +
                    "is waiting on the user to answer it, and the screen will not change until " +
                    "they do.",
                reason
            )
            fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xE6000000.toInt())
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val label = TextView(this).apply {
                text = getString(R.string.renderer_confirm_question)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val yes = Button(this).apply {
                text = getString(R.string.renderer_confirm_yes)
                setOnClickListener {
                    settings.pendingRendererConfirm = false
                    rendererConfirmResolved = true
                    dismissRendererConfirmBanner()
                }
            }
            val switch = Button(this).apply {
                text = getString(R.string.renderer_confirm_switch)
                setOnClickListener { cycleRenderer() }
            }
            bar.addView(label)
            bar.addView(yes)
            bar.addView(switch)
            bar.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            container.addView(bar)
            rendererBanner = bar
            bar.bringToFront()
            // One-shot: offering the escape once is enough. Clearing the flag here (not only on
            // "Yes") stops the banner from re-appearing on every future session if the user drives
            // off without tapping. A genuinely broken renderer still re-offers via the no-frame path.
            settings.pendingRendererConfirm = false
        }
    }

    private fun dismissRendererConfirmBanner() {
        runOnUiThread {
            rendererBanner?.let { (it.parent as? FrameLayout)?.removeView(it) }
            rendererBanner = null
        }
    }

    /** Rotate the rendering backend live (TextureView -> SurfaceView -> GLES) and rebuild, so the
     * user can find one that draws. Clears any stall-recovery override so the manual choice wins. */
    private fun cycleRenderer() {
        val order = listOf(Settings.ViewMode.TEXTURE, Settings.ViewMode.SURFACE, Settings.ViewMode.GLES)
        val current = forcedViewModeOverride ?: settings.viewMode
        val next = order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
        forcedViewModeOverride = null
        settings.viewMode = next
        settings.commit()
        // Reset stall recovery so the #650 watchdog does not immediately re-escalate the new backend.
        displayStallRecoveries = 0
        lastDisplayStallRecoveryMs = 0L
        val label = when (next) {
            Settings.ViewMode.SURFACE -> "SurfaceView"
            Settings.ViewMode.TEXTURE -> "TextureView"
            Settings.ViewMode.GLES -> "GLES20"
        }
        ToastUtils.showToast(this, getString(R.string.renderer_switched_to, label), Toast.LENGTH_SHORT)
        // Drop the current bar and rebuild on the new backend, then re-offer so the user can keep
        // cycling if this one is also blank (the new backend may decode frames yet still show black,
        // which nothing can detect automatically).
        dismissRendererConfirmBanner()
        recreateProjectionView()
        showRendererConfirmBanner("the renderer was just switched and may still be blank")
    }

    private val nightModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isNight = intent.getBooleanExtra("isNight", false)
            updateDesaturation(isNight)
        }
    }

    private fun updateDesaturation(isNight: Boolean) {
        if (settings.aaMonochromeEnabled && projectionView is GlProjectionView) {
            val level = if (isNight) settings.aaDesaturationLevel / 100f else 0f
            (projectionView as GlProjectionView).setDesaturation(level)
        } else if (projectionView is GlProjectionView) {
            (projectionView as GlProjectionView).setDesaturation(0f)
        }
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("AapProjectionActivity: Orientation change broadcast received. Updating.")
                applyOrientationSettings()
            }
        }
    }

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = IntentCompat.getParcelableExtra(intent, KeyIntent.extraEvent, KeyEvent::class.java)
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("AapProjectionActivity: Received finish request. Closing.")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [FIX] applyOrientationSettings() must be called AFTER super.onCreate() so that the
        // Activity window is fully initialized before we lock the orientation. Calling it before
        // super.onCreate() caused SCREEN_ORIENTATION_LOCKED to inherit the orientation context
        // from the launching task (e.g. MainActivity in portrait), resulting in the projection
        // Activity locking to portrait on a landscape head unit when started via the Self Mode
        // button in the app. With the long-press shortcut the bug was absent because the Activity
        // started without an existing task context. Moving this call after super.onCreate()
        // ensures the window manager has correctly resolved the display's physical orientation
        // before we lock it.
        applyOrientationSettings()


        setContentView(R.layout.activity_headunit)

        if (settings.showFpsCounter) {
            setupFpsCounter()
        }

        videoDecoder.dimensionsListener = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })

        var isFirstEmission = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { state ->
                    val first = isFirstEmission
                    isFirstEmission = false

                    if (first && state is CommManager.ConnectionState.Disconnected) {
                        AppLog.i("AapProjectionActivity: Ignoring initial Disconnected state from StateFlow replay.")
                        return@collect
                    }

                    when (state) {
                        is CommManager.ConnectionState.Disconnected -> {
                            watchdogHandler.removeCallbacksAndMessages(null)
                            // Don't leave the renderer bar floating over the reconnecting/exit UI;
                            // if the renderer is still broken after a reconnect it re-offers itself.
                            dismissRendererConfirmBanner()
                            if (!state.isClean && !state.isUserExit) {
                                AppLog.w("AapProjectionActivity: Disconnected unexpectedly.")
                                ToastUtils.showToast(this@AapProjectionActivity, getString(R.string.wifi_disconnect_toast), Toast.LENGTH_LONG)
                            }
                            // Only finish immediately if the user explicitly exited, it was a clean close, or killOnDisconnect is enabled.
                            if (state.isUserExit || state.isClean || settings.killOnDisconnect) {
                                AppLog.i("AapProjectionActivity: Finishing because state isUserExit=${state.isUserExit}, isClean=${state.isClean}, killOnDisconnect=${settings.killOnDisconnect}")
                                hideReconnectingOverlay()
                                finish()
                            } else {
                                // For unexpected disconnects (especially Wireless), show the reconnecting overlay immediately
                                // and wait up to 20 seconds (or 8 seconds for USB) to see if the connection recovers.
                                val timeoutMs = if (settings.lastConnectionType == Settings.CONNECTION_TYPE_USB) 8000L else 20000L
                                AppLog.i("AapProjectionActivity: Unexpected disconnect. Showing reconnecting overlay and waiting up to ${timeoutMs / 1000}s for recovery.")
                                showReconnectingOverlay()

                                // Re-initialize the first frame listener to hide the reconnecting overlay when video starts flowing
                                videoDecoder.onFirstFrameListener = {
                                    runOnUiThread {
                                        hideReconnectingOverlay()
                                    }
                                }

                                watchdogHandler.removeCallbacks(exitRunnable)
                                watchdogHandler.postDelayed(exitRunnable, timeoutMs)
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete -> {
                            watchdogHandler.removeCallbacks(exitRunnable)

                            // Restart the video watchdog so it can request keyframes for the new session
                            watchdogHandler.removeCallbacks(videoWatchdogRunnable)
                            watchdogHandler.postDelayed(videoWatchdogRunnable, 1000)

                            // Lock the resolution so that orientation changes don't cause re-negotiation
                            HeadUnitScreenConfig.lockResolution()

                            // Handshake done. If the surface is already ready (e.g. reconnect
                            // while the activity is in the foreground), start reading immediately.
                            // If not, onSurfaceChanged() will call startReading() when the surface
                            // becomes available.
                            if (isSurfaceSet) {
                                commManager.startReading()
                            }
                        }
                        is CommManager.ConnectionState.TransportStarted -> {
                            watchdogHandler.removeCallbacks(exitRunnable)
                        }
                        else -> {}
                    }
                }
            }
        }

        ContextCompat.registerReceiver(this, finishReceiver, android.content.IntentFilter("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES"), ContextCompat.RECEIVER_NOT_EXPORTED)
        isFinishReceiverRegistered = true

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid., since 2025 André Rinas All Rights Reserved...")

        val container = findViewById<FrameLayout>(R.id.container)
        setupProjectionView()

        val overlayView = OverlayTouchView(this)
        this.touchOverlayView = overlayView
        overlayView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true

        overlayView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    overlayView.requestFocus()
                }
                sendTouchEvent(event)
                true
            }

        container.addView(overlayView)
        overlayView.requestFocus()
        setFullscreen() // Call setFullscreen here as well

        val loadingOverlay = findViewById<View>(R.id.loading_overlay)
        if (settings.hudMirroring) {
            loadingOverlay?.scaleX = -1.0f
        }

        // [FIX] If we are already connected and frames are flowing (e.g. activity recreation),
        // hide the overlay immediately to prevent the "Android Auto is starting" flicker.
        if (commManager.isConnected && videoDecoder.lastFrameRenderedMs > 0) {
            loadingOverlay?.visibility = View.GONE
            overlayState = OverlayState.HIDDEN
        }

        // Ensure loading overlay is on top of everything
        loadingOverlay?.bringToFront()

        // Set up custom loading screen if configured
        setupCustomLoadingScreen()

        findViewById<Button>(R.id.disconnect_button)?.setOnClickListener {
            commManager.disconnect()
            finish()
        }

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                hideLoadingOverlay(loadingOverlay)

                // The wizard changed the renderer: the picture is up, so ask the user to confirm it
                // (issue #767). If they don't, the auto-offer above still catches a broken renderer.
                if (settings.pendingRendererConfirm && !rendererConfirmResolved) {
                    showRendererConfirmBanner("the setup wizard changed the renderer")
                }

                // Show one-time gesture hint
                if (!settings.gestureHintShown) {
                    Toast.makeText(this@AapProjectionActivity, R.string.gesture_hint, Toast.LENGTH_LONG).show()
                    settings.gestureHintShown = true
                }
            }
        }

        commManager.onUpdateUiConfigReplyReceived = {
            AppLog.i("[UI_DEBUG_FIX] UpdateUiConfig reply received. AA acknowledged new margins.")
        }
    }

    override fun onPause() {
        isForeground = false
        AppLog.i("AapProjectionActivity: onPause")
        super.onPause()
        RenameNotice.dismiss()
        // Clear any activity-local fullscreen override when leaving the Activity so
        // the stored settings remain authoritative on next resume.
        activityFullscreenOverride = null
        // Before the handler is cleared below, and never after it.
        settleFocusCycle()
        watchdogHandler.removeCallbacks(warmRelaunchCheckRunnable)
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(reconnectingWatchdog)
        watchdogHandler.removeCallbacks(exitRunnable)
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
        if (isNightModeReceiverRegistered) {
            unregisterReceiver(nightModeReceiver)
            isNightModeReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
        if (isSettingsReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver)
            isSettingsReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        AppLog.i("AapProjectionActivity: onResume")
        // Show the one-time rename notice even here, on top of an active projection.
        RenameNotice.maybeShow(this, App.provide(this).settings)
        applyStickyOrientation()
        watchdogHandler.postDelayed(watchdogRunnable, 2000)
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)
        watchdogHandler.postDelayed(reconnectingWatchdog, 5000)


        if (!isKeyEventReceiverRegistered) {
            ContextCompat.registerReceiver(this, keyEventReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_EXPORTED)
            isKeyEventReceiverRegistered = true
        }

        // Register orientation receiver
        ContextCompat.registerReceiver(this, orientationReceiver, IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // Register night mode receiver for AA monochrome filter
        ContextCompat.registerReceiver(this, nightModeReceiver, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isNightModeReceiverRegistered = true

        if (!isSettingsReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, IntentFilter(QuickSettingsFragment.ACTION_SETTINGS_CHANGED))
            isSettingsReceiverRegistered = true
        }

        // Request current night mode state for initial desaturation
        sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(packageName)
        })

        setFullscreen()
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i("AapProjectionActivity: onNewIntent received")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
            touchOverlayView?.requestFocus()
        }
    }

    private fun showReconnectingOverlay() {
        AppLog.i("Showing reconnecting overlay")
        overlayState = OverlayState.RECONNECTING
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        if (settings.hudMirroring) {
            overlay.scaleX = -1.0f
        }

        // Ensure default content is shown, custom media is hidden
        findViewById<View>(R.id.loading_default_content)?.visibility = View.VISIBLE
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val title = findViewById<TextView>(R.id.overlay_text)
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.VISIBLE
        title?.text = getString(R.string.connection_interrupted)
        detail?.text = getString(R.string.connection_interrupted_detail)
        detail?.visibility = View.VISIBLE
        button?.visibility = View.VISIBLE
    }

    private fun hideReconnectingOverlay() {
        AppLog.i("Hiding reconnecting overlay — frames resumed")
        overlayState = OverlayState.HIDDEN
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.GONE
        detail?.visibility = View.GONE
        button?.visibility = View.GONE
        stopCustomLoadingMedia()
        touchOverlayView?.requestFocus()
    }

    private fun setupCustomLoadingScreen() {
        // Apply any context-specific status text handed over by MainActivity
        // (e.g. "Connecting to Pixel 8…") to BOTH the default-content text and
        // the custom-media text overlay. Done before the early-return paths so
        // the override applies whether or not custom media is configured. Read
        // once and cleared so the value can't leak into a later connection.
        val handover = pendingStatusText
        pendingStatusText = null
        if (handover != null) {
            findViewById<TextView>(R.id.overlay_text)?.text = handover
            findViewById<TextView>(R.id.loading_custom_text)?.text = handover
        }

        val overlay = findViewById<View>(R.id.loading_overlay)
        if (settings.hudMirroring) {
            overlay?.scaleX = -1.0f
        }

        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType
        if (mediaPath.isEmpty() || mediaType.isEmpty()) return

        val file = File(mediaPath)
        if (!file.exists()) {
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            return
        }

        val defaultContent = findViewById<View>(R.id.loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.loading_custom_video)

        // Always hide the default content when custom media is active
        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        // Show the dedicated custom text overlay if the user wants status text
        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
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
                    customImage?.visibility = View.VISIBLE
                    customImage?.let { Glide.with(this).load(file).into(it) }
                    if (keepRatio) {
                        customImage?.let { imageView ->
                            kenBurnsAnimator?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                imageView,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            kenBurnsAnimator = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customImage?.visibility = View.VISIBLE
                    customImage?.let { Glide.with(this).asGif().load(file).into(it) }
                }
                "video" -> {
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            val ov = overlay
                            val cv = customVideo
                            if (ov != null && cv != null) {
                                val cw = ov.width
                                val ch = ov.height
                                if (cw > 0 && ch > 0) {
                                    val lp = cv.layoutParams as FrameLayout.LayoutParams
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
                                    cv.layoutParams = lp
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Could not resize video: ${e.message}")
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Error playing custom loading video")
                        fallbackToDefaultOverlay()
                        true
                    }
                    customVideo?.start()
                }
                else -> return
            }
        } catch (e: Exception) {
            AppLog.e("Failed to load custom loading screen: ${e.message}")
            fallbackToDefaultOverlay()
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? {
        return true
    }

    private fun applyVirtualDisplayFix() {
        // fixes projected picture being frozen within DUDU PiP
        // does not fix the root cause, where there is a redraw (or something?) of the whole launcher
        //  right before the first frame is shown
        // there is also no public API to get the type of the display
        // if this also causes issues with other virtual displays, try to obtain #getType() via reflection
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return
        if (intent.getBooleanExtra("applied_vd_fix", false))
            return
        if (display?.name?.startsWith("DUDU-launcher-split") != true)
            return

        intent.putExtra("applied_vd_fix", true) // avoid infinite-loop

        AppLog.i("Detected VirtualDisplay: Recreating projection to fix stuck picture shortly")

        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }

    private fun hideLoadingOverlay(loadingOverlay: View?) {
        overlayState = OverlayState.HIDDEN
        AppLog.i("Hiding loading overlay after first video frame")

        // CRITICAL: Stop custom video FIRST — VideoView/SurfaceView has its own
        // rendering layer that ignores parent alpha animations and can stay visible
        // even when the parent is animated to alpha=0
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE

        // Now hide the overlay — if no custom video, do a smooth fade
        val hasCustomVideo = settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            // Direct hide — animation won't work with SurfaceView
            loadingOverlay?.visibility = View.GONE
            touchOverlayView?.requestFocus()
        } else {
            // Smooth fade for images/GIFs
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                    loadingOverlay?.alpha = 1f
                    touchOverlayView?.requestFocus()
                }?.start()
                ?: run {
                    loadingOverlay?.visibility = View.GONE
                    touchOverlayView?.requestFocus()
                }
        }

        applyVirtualDisplayFix()
        touchOverlayView?.requestFocus()
    }

    private fun fallbackToDefaultOverlay() {
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE
        findViewById<View>(R.id.loading_default_content)?.visibility = View.VISIBLE
        findViewById<View>(R.id.loading_overlay)?.setBackgroundColor(Color.parseColor("#CC000000"))
    }

    private fun stopCustomLoadingMedia() {
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null
        findViewById<VideoView>(R.id.loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)

        // Use activity-local override if present, otherwise use stored settings
        val mode = activityFullscreenOverride ?: settings.fullscreenMode

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && mode != Settings.FullscreenMode.NONE) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        SystemUI.apply(window, container, mode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }

        // Workaround for API < 19 (Jelly Bean) where Sticky Immersive Mode doesn't exist.
        // If bars appear (e.g. on touch), hide them again after a delay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && mode != Settings.FullscreenMode.NONE) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // Bars are visible. Hide them again.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val effectiveMode = activityFullscreenOverride ?: settings.fullscreenMode
                        SystemUI.apply(window, container, effectiveMode) {
                            if (::projectionView.isInitialized) {
                                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
                            }
                        }
                    }, 2000)
                }
            }
        }
    }

    private data class ExitOption(val titleResId: Int, val iconResId: Int, val iconColor: Int)

    private fun showExitDialog() {
        val options = mutableListOf<ExitOption>()
        options.add(ExitOption(R.string.exit_dialog_stop, R.drawable.ic_stop, Color.RED))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.add(ExitOption(R.string.exit_dialog_pip, R.drawable.ic_pip, Color.LTGRAY))
        }

        options.add(ExitOption(R.string.exit_dialog_background, R.drawable.ic_home, Color.LTGRAY))
        options.add(ExitOption(R.string.exit_dialog_settings, R.drawable.ic_settings_quick, Color.LTGRAY))

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.dialog_exit_item, parent, false)
                val option = options[position]
                val iconView = view.findViewById<android.widget.ImageView>(R.id.icon)
                val textView = view.findViewById<android.widget.TextView>(R.id.text)

                textView.setText(option.titleResId)
                iconView.setImageResource(option.iconResId)
                iconView.setColorFilter(option.iconColor)

                return view
            }
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.exit_dialog_title)
            .setAdapter(adapter) { _, which ->
                val selected = options[which]
                when (selected.titleResId) {
                    R.string.exit_dialog_stop -> {
                        commManager.disconnect(sendByeBye = true)
                        finish()
                    }
                    R.string.exit_dialog_pip -> {
                        enterPiP()
                    }
                    R.string.exit_dialog_background -> {
                        moveToBackground()
                    }
                    R.string.exit_dialog_settings -> {
                        showQuickSettings()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        if (settings.hudMirroring) {
            val root = dialog.window?.findViewById<View>(android.R.id.content) ?: dialog.window?.decorView
            root?.scaleX = -1.0f
        }
    }

    private fun showQuickSettings() {
        // We will implement QuickSettingsFragment as a DialogFragment for easy overlay
        val quickSettings = com.andrerinas.openheadunit.main.QuickSettingsFragment()
        quickSettings.show(supportFragmentManager, "quick_settings")
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                var width = videoDecoder.videoWidth.coerceAtLeast(1).toFloat()
                var height = videoDecoder.videoHeight.coerceAtLeast(1).toFloat()
                val ratio = width / height

                // Android supports PiP aspect ratios between 1/2.39 (0.418) and 2.39.
                // If we exceed this (e.g. on ultrawide headunits), PiP entry will fail.
                if (ratio > 2.39f) {
                    AppLog.i("PiP: Aspect ratio $ratio is too wide, clamping to 2.39")
                    width = height * 2.39f
                } else if (ratio < 0.418f) {
                    AppLog.i("PiP: Aspect ratio $ratio is too narrow, clamping to 0.418")
                    height = width / 0.418f
                }

                val paramsBuilder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(width.toInt(), height.toInt()))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Smooth transition for Android 12+
                    paramsBuilder.setAutoEnterEnabled(true)
                    paramsBuilder.setSeamlessResizeEnabled(true)
                }

                App.isPiPActive = true
                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                AppLog.e("Failed to enter PiP mode: ${e.message}")
                e.printStackTrace()
                Toast.makeText(this, "PiP failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            AppLog.w("PiP mode not supported on this Android version (SDK < 26)")
        }
    }

    private fun moveToBackground() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        App.isPiPActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Hide UI elements during PiP (like FPS counter, loading overlay)
            findViewById<View>(R.id.loading_overlay)?.visibility = View.GONE
            stopCustomLoadingMedia()
            fpsTextView?.visibility = View.GONE
        } else {
            // Restore UI if needed
            fpsTextView?.visibility = if (settings.showFpsCounter) View.VISIBLE else View.GONE
            setFullscreen()
        }
    }

    override fun onUserLeaveHint() {
        // Optional: Auto-enter PiP if user presses home

        // For now, we only enter via dialog as requested.
        super.onUserLeaveHint()
    }

    private val commManager get() = App.provide(this).commManager

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 1. 2-finger swipe detection from the left or right edge (to open exit menu or toggle fullscreen)
        if (ev.pointerCount == 2) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    initialX = ev.getX(0)
                    initialY = ev.getY(0)
                    isPotentialGesture = initialX < 100 || initialX > HeadUnitScreenConfig.getUsableWidth() - 100
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPotentialGesture) {
                        val deltaX = ev.getX(0) - initialX
                        val deltaY = abs(ev.getY(0) - initialY)
                        if (deltaX > 200 && deltaY < 100) {
                            isPotentialGesture = false
                            showExitDialog()
                            return true // Consume
                        }

                        if (deltaX < -200 && deltaY < 100) {
                            isPotentialGesture = false
                            val currentEffective = activityFullscreenOverride ?: settings.fullscreenMode
                            if (currentEffective != Settings.FullscreenMode.NONE) {
                                activityFullscreenOverride = Settings.FullscreenMode.NONE
                                setFullscreen()
                            } else {
                                activityFullscreenOverride = null
                                setFullscreen()
                            }
                            return true // Consume
                        }
                    }
                }
            }
        }

        // 2. Legacy Touch handling for older devices (API < 19)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            sendTouchEvent(ev)
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        isSurfaceSet = true

        videoDecoder.setSurface(surface)
        // A surface arriving inside a cycle's own gap must not strand the pending gain.
        settleFocusCycle()
        lastSurfaceSetMs = SystemClock.elapsedRealtime()
        warmRelaunchCycleSpent = false
        watchdogHandler.removeCallbacks(warmRelaunchCheckRunnable)
        watchdogHandler.postDelayed(
            warmRelaunchCheckRunnable,
            WarmRelaunchKeyframePolicy.ESCALATE_AFTER_SURFACE_MS
        )

        // --- Surface Mismatch Detection ---
        // Compare actual surface dimensions with what HeadUnitScreenConfig negotiated.
        // If they differ (e.g. system bars appeared/disappeared), update margins.
        val prevUsableW = HeadUnitScreenConfig.getUsableWidth()
        val prevUsableH = HeadUnitScreenConfig.getUsableHeight()

        if (HeadUnitScreenConfig.updateSurfaceDimensions(width, height)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch! Expected: ${prevUsableW}x${prevUsableH}, Actual: ${width}x${height}")

            // Cache the real surface size for next session
            settings.cachedSurfaceWidth = width
            settings.cachedSurfaceHeight = height
            settings.cachedSurfaceSettingsHash = HeadUnitScreenConfig.computeSettingsHash(settings)

            if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                // AA is already running → send corrected per-side margins dynamically
                commManager.sendUpdateUiConfigRequest(
                    HeadUnitScreenConfig.getLeftMargin(),
                    HeadUnitScreenConfig.getTopMargin(),
                    HeadUnitScreenConfig.getRightMargin(),
                    HeadUnitScreenConfig.getBottomMargin()
                )
                AppLog.i("[UI_DEBUG_FIX] AA is already running, send corrected via sendUpdateUiConfigRequest")
            }
            // If transport not started yet, ServiceDiscoveryResponse will use the corrected values automatically.
        }

        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> {
                // AapService should have started the handshake already, but as a fallback
                // (e.g. service restarted) kick it off here. The HandshakeComplete observer
                // will call startReading() once the handshake finishes.
                lifecycleScope.launch { commManager.startHandshake() }
            }
            is CommManager.ConnectionState.StartingTransport -> {
                // Handshake is in progress. The HandshakeComplete observer will call
                // startReading() when it finishes.
            }
            is CommManager.ConnectionState.HandshakeComplete -> {
                // Handshake already done before surface was ready — start reading now.
                lifecycleScope.launch { commManager.startReading() }
            }
            is CommManager.ConnectionState.TransportStarted -> {
                // Surface recreated while transport was already running; request a keyframe.
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            else -> {
                commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
            }
        }

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                ProjectionViewScaler.updateScale(projectionView as View, currentVideoWidth, currentVideoHeight)
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        // A relaunched instance may already own the decoder: on a singleTask relaunch the old
        // instance's surface teardown is framework-ordered after its onDestroy, and for the GLES
        // backend one main-looper post later still, so it lands after the new instance's
        // setSurface. Acting on it then would release video focus for a stream the new instance
        // is rendering and stop a decoder that was just rebuilt. All surface ownership changes
        // happen on the main thread, so this read cannot race the send below.
        if (!videoDecoder.isCurrentSurface(surface)) {
            AppLog.i("SurfaceCallback: onSurfaceDestroyed for a stale surface - ignoring. Surface: $surface")
            return
        }
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        isSurfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stopIfCurrentSurface(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
    }


    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView as View, width, height)
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val videoW = HeadUnitScreenConfig.getNegotiatedWidth()
        val videoH = HeadUnitScreenConfig.getNegotiatedHeight()

        if (videoW <= 0 || videoH <= 0 || projectionView !is View) {
            AppLog.w("sendTouchEvent: Ignoring touch, screen config or view not ready.")
            return
        }

        val view = projectionView as View
        val effectiveFullscreenMode = activityFullscreenOverride ?: settings.fullscreenMode
        val measuredTouchSurfaceEnabled = settings.useMeasuredTouchSurface &&
            effectiveFullscreenMode == Settings.FullscreenMode.IMMERSIVE
        val overlay = touchOverlayView
        val viewW = if (measuredTouchSurfaceEnabled) {
            (overlay?.width ?: 0).takeIf { it > 0 }?.toFloat()
                ?: view.width.takeIf { it > 0 }?.toFloat()
                ?: HeadUnitScreenConfig.getUsableWidth().toFloat()
        } else {
            HeadUnitScreenConfig.getUsableWidth().toFloat()
        }
        val viewH = if (measuredTouchSurfaceEnabled) {
            (overlay?.height ?: 0).takeIf { it > 0 }?.toFloat()
                ?: view.height.takeIf { it > 0 }?.toFloat()
                ?: HeadUnitScreenConfig.getUsableHeight().toFloat()
        } else {
            HeadUnitScreenConfig.getUsableHeight().toFloat()
        }

        if (viewW <= 0 || viewH <= 0) return

        val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
        val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()

        // Logic check: When forcedScale is active, the visual behavior of 'stretchToFill'
        // is inverted (True = Aspect Ratio Centered, False = Stretched to Screen).
        // We adjust the touch mapping to match this visual reality.
        val isStretch = if (HeadUnitScreenConfig.forcedScale) {
            !settings.stretchToFill
        } else {
            settings.stretchToFill
        }

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            if (measuredTouchSurfaceEnabled) {
                val corrected = TouchCoordinateMapper.map(
                    rawX = event.getX(pointerIndex),
                    rawY = event.getY(pointerIndex),
                    inputSurfaceWidth = viewW,
                    inputSurfaceHeight = viewH,
                    negotiatedWidth = videoW,
                    negotiatedHeight = videoH,
                    marginWidth = marginW,
                    marginHeight = marginH,
                    stretchToFill = isStretch,
                    hudMirroring = settings.hudMirroring
                )

                pointerData.add(Triple(pointerId, corrected.x, corrected.y))
            } else {
                val rawPx = event.getX(pointerIndex)
                val px = if (settings.hudMirroring) (viewW - rawPx) else rawPx
                val py = event.getY(pointerIndex)

                val videoX: Float
                val videoY: Float

                if (isStretch) {
                    videoX = (px / viewW) * (videoW - marginW)
                    videoY = (py / viewH) * (videoH - marginH)
                } else {
                    val uiW = videoW - marginW
                    val uiH = videoH - marginH
                    val uiRatio = uiW / uiH
                    val viewRatio = viewW / viewH

                    var displayedUiW = viewW
                    var displayedUiH = viewH

                    if (viewRatio > uiRatio) {
                        displayedUiW = viewH * uiRatio
                    } else {
                        displayedUiH = viewW / uiRatio
                    }

                    val uiLeft = (viewW - displayedUiW) / 2f
                    val uiTop = (viewH - displayedUiH) / 2f

                    val localX = px - uiLeft
                    val localY = py - uiTop

                    videoX = (localX / displayedUiW) * uiW
                    videoY = (localY / displayedUiH) * uiH
                }

                val correctedX = videoX.toInt().coerceIn(0, videoW)
                val correctedY = videoY.toInt().coerceIn(0, videoH)
                pointerData.add(Triple(pointerId, correctedX, correctedY))
            }
        }

        commManager.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }

        // 1. Let the system handle volume keys and unmapped back keys.
        // If Back was explicitly learned in Keymap and transport is running,
        // route it through CommManager so it can be remapped and sent to Android Auto.
        if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted &&
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(cachedKeyCodes, event.keyCode)) {
            commManager.sendKey(event.keyCode, action == KeyEvent.ACTION_DOWN, event.downTime, "projection")
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        // 2. Funnel all other keys to CommManager
        commManager.sendKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.downTime, "projection")
        return true
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        // Broadcasts (e.g. from CarKeyReceiver) still use this path.
        commManager.sendKey(keyCode, isPress, null, "key-broadcast")
    }

    private fun applyStickyOrientation() {
        if (settings.screenOrientation == Settings.ScreenOrientation.AUTO && HeadUnitScreenConfig.isResolutionLocked) {
            val target = if (HeadUnitScreenConfig.getNegotiatedWidth() > HeadUnitScreenConfig.getNegotiatedHeight()) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (requestedOrientation != target) {
                AppLog.i("[UI_DEBUG] Sticky Orientation: Session active, forcing orientation to $target")
                requestedOrientation = target
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
        // Defensive cleanup: if the activity is destroyed while the loading
        // overlay is still up (early connection failure, system kill,
        // configuration change before first frame), the VideoView's surface
        // and the Ken Burns animator outlive the view hierarchy briefly.
        // stopCustomLoadingMedia releases both.
        stopCustomLoadingMedia()
        stopPerformanceOverlayUpdates()
        performanceExecutor.shutdownNow()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        App.isPiPActive = false
        // On a singleTask relaunch the new instance's onCreate runs before this old instance's
        // onDestroy, so it has already registered its own view callback and decoder listeners.
        // Deregister only what still belongs to this instance, or the teardown of the old
        // instance strips the live one: its view callback stays armed to fire a stale
        // surface-destroy, and its listeners get nulled out from under it.
        if (::projectionView.isInitialized) projectionView.removeCallback(this)
        if (videoDecoder.onFpsChanged === fpsListener) videoDecoder.onFpsChanged = null
        (if (::projectionView.isInitialized) projectionView as? SoftwareYuvFrameSink else null)?.let {
            if (videoDecoder.softwareYuvFrameSink === it) videoDecoder.softwareYuvFrameSink = null
        }
        if (videoDecoder.dimensionsListener === this) videoDecoder.dimensionsListener = null
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
        @Volatile var isForeground = false

        /**
         * Optional one-shot override for the loading-screen status text. Set by
         * MainActivity when it begins an auto-connect with a context-specific
         * label (e.g. "Connecting to Pixel 8…" from the Nearby selector). Read
         * and cleared by [setupCustomLoadingScreen] on the next launch so the
         * value can't leak into a subsequent connection attempt.
         */
        @Volatile var pendingStatusText: String? = null

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
    private fun applyOrientationSettings() {
        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            applyStickyOrientation()
            if (!HeadUnitScreenConfig.isResolutionLocked) {
                // Initial start: lock to current orientation at launch
                if (Build.VERSION.SDK_INT >= 18) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                }
            }
        } else {
            requestedOrientation = screenOrientation.androidOrientation
        }
    }

    private fun setupProjectionView() {
        val container = findViewById<FrameLayout>(R.id.container)
        val displayMetrics = resources.displayMetrics

        // forcedViewModeOverride is set by the display-stall recovery to pin SurfaceView for the
        // rest of the session (issue #650); otherwise honor the user's chosen viewMode.
        val mode = forcedViewModeOverride ?: settings.viewMode
        AppLog.i(
            "Projection backend: viewMode=$mode override=${forcedViewModeOverride != null} " +
                "SoC=${Build.HARDWARE} board=${Build.BOARD} mfr=${Build.MANUFACTURER} " +
                "model=${Build.MODEL} API=${Build.VERSION.SDK_INT}"
        )

        if (mode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(Color.BLACK)
        } else if (mode == Settings.ViewMode.GLES) {
            AppLog.i("Using GlProjectionView")
            val glView = GlProjectionView(this)
            glView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = glView
            container.setBackgroundColor(Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as View
        container.addView(view)
        videoDecoder.softwareYuvFrameSink = projectionView as? SoftwareYuvFrameSink
        if (videoDecoder.softwareYuvFrameSink != null) {
            AppLog.i("Using GLES YUV sink for bundled software HEVC")
        }

        projectionView.addCallback(this)
        // Baseline for the "no frame drawn while streaming" renderer check (issue #767).
        projectionStartMs = SystemClock.elapsedRealtime()
    }

    private fun setupFpsCounter() {
        val container = findViewById<FrameLayout>(R.id.container)
        fpsTextView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(10, 5, 10, 5)
            text = "FPS: --\nCPU: -- / --\nTemp: --\nFrame: --"
            // Lift it above everything
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 100f
                translationZ = 100f
            }
            if (settings.hudMirroring) {
                scaleX = -1.0f
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(20, 20, 0, 0)
        }
        container.addView(fpsTextView, params)

        videoDecoder.onFpsChanged = fpsListener
        startPerformanceOverlayUpdates()
    }

    private fun startPerformanceOverlayUpdates() {
        performanceHandler.removeCallbacks(performanceOverlayRunnable)
        performanceOverlayRunnable.run()
    }

    private fun stopPerformanceOverlayUpdates() {
        performanceHandler.removeCallbacks(performanceOverlayRunnable)
    }

    private fun requestPerformanceOverlayUpdate() {
        if (!performanceSampleInFlight.compareAndSet(false, true)) return

        val fpsSnapshot = currentFps
        val lastFrameSnapshot = videoDecoder.lastFrameRenderedMs
        try {
            performanceExecutor.execute {
                try {
                    val text = buildPerformanceOverlayText(fpsSnapshot, lastFrameSnapshot)
                    runOnUiThread {
                        if (!isFinishing && fpsTextView?.visibility == View.VISIBLE) {
                            fpsTextView?.text = text
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w("Performance overlay update failed: ${e.message}")
                } finally {
                    performanceSampleInFlight.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            performanceSampleInFlight.set(false)
        }
    }

    private fun buildPerformanceOverlayText(fpsSnapshot: Int?, lastFrameSnapshot: Long): String {
        val metrics = performanceSampler.sample()
        val frameAgeText = if (lastFrameSnapshot > 0L) {
            "${SystemClock.elapsedRealtime() - lastFrameSnapshot}ms"
        } else {
            "--"
        }
        val fpsText = fpsSnapshot?.toString() ?: "--"
        val appCpuText = metrics.appCpuPercent?.let { "${it}%" } ?: "--"
        val totalCpuText = metrics.totalCpuPercent?.let { "${it}%" }
            ?: metrics.loadAverage?.let { String.format(java.util.Locale.US, "%.2f load", it) }
            ?: "--"
        val tempText = metrics.temperatureC?.let { "${it}C" } ?: "--"
        return "FPS: $fpsText\nCPU: app $appCpuText / sys $totalCpuText\nTemp: $tempText\nFrame: $frameAgeText"
    }

    private class PerformanceSampler {
        private data class TotalCpuSnapshot(
            val totalJiffies: Long,
            val idleJiffies: Long
        )

        data class Metrics(
            val appCpuPercent: Int?,
            val totalCpuPercent: Int?,
            val loadAverage: Double?,
            val temperatureC: Int?
        )

        private var previousTotalCpu: TotalCpuSnapshot? = null
        private var previousProcessCpuMs: Long? = null
        private var previousElapsedMs: Long? = null

        fun sample(): Metrics {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val nowProcessCpuMs = android.os.Process.getElapsedCpuTime()
            val previousProcess = previousProcessCpuMs
            val previousElapsed = previousElapsedMs
            previousProcessCpuMs = nowProcessCpuMs
            previousElapsedMs = nowElapsedMs

            val appCpu = if (previousProcess != null && previousElapsed != null) {
                val cpuDelta = (nowProcessCpuMs - previousProcess).coerceAtLeast(0L)
                val elapsedDelta = (nowElapsedMs - previousElapsed).coerceAtLeast(1L)
                ((cpuDelta.toDouble() / elapsedDelta) * 100.0).toInt().coerceAtLeast(0)
            } else {
                null
            }

            val currentTotalCpu = readTotalCpuSnapshot()
            val previousTotal = previousTotalCpu
            previousTotalCpu = currentTotalCpu
            val totalCpu = if (currentTotalCpu != null && previousTotal != null) {
                val totalDelta = (currentTotalCpu.totalJiffies - previousTotal.totalJiffies).coerceAtLeast(1L)
                val idleDelta = (currentTotalCpu.idleJiffies - previousTotal.idleJiffies).coerceAtLeast(0L)
                (((totalDelta - idleDelta).toDouble() / totalDelta) * 100.0).toInt().coerceIn(0, 100)
            } else {
                null
            }

            return Metrics(appCpu, totalCpu, readLoadAverage(), readTemperatureC())
        }

        private fun readTotalCpuSnapshot(): TotalCpuSnapshot? {
            return try {
                val cpuLine = File("/proc/stat").useLines { lines ->
                    lines.firstOrNull { it.startsWith("cpu ") }
                } ?: return null
                val cpuValues = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (cpuValues.size < 5) return null
                val idle = cpuValues.getOrElse(3) { 0L } + cpuValues.getOrElse(4) { 0L }
                val total = cpuValues.take(8).sum()
                TotalCpuSnapshot(total, idle)
            } catch (e: Exception) {
                null
            }
        }

        private fun readLoadAverage(): Double? {
            return try {
                File("/proc/loadavg")
                    .readText()
                    .trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.toDoubleOrNull()
            } catch (e: Exception) {
                null
            }
        }

        private fun readTemperatureC(): Int? {
            return try {
                val thermalRoot = File("/sys/class/thermal")
                val values = thermalRoot.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.mapNotNull { zone ->
                        val raw = zone.resolve("temp").readText().trim().toIntOrNull() ?: return@mapNotNull null
                        when {
                            raw in 10000..125000 -> raw / 1000
                            raw in 10..125 -> raw
                            else -> null
                        }
                    }
                    .orEmpty()
                values.maxOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }
}
