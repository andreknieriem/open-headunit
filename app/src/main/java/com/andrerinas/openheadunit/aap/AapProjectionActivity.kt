package com.andrerinas.openheadunit.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStagePill
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.contract.KeyIntent
import com.andrerinas.openheadunit.decoder.video.WarmRelaunchKeyframePolicy
import com.andrerinas.openheadunit.input.ProjectionKeyPolicy
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.SoftwareYuvFrameSink
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoDimensionsListener
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy
import com.andrerinas.openheadunit.connection.usb.UsbSwitchClaim
import com.andrerinas.openheadunit.decoder.audio.CallState
import com.andrerinas.openheadunit.utils.IntentFilters
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.GlProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.TextureProjectionView
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.view.OverlayTouchView
import com.andrerinas.openheadunit.view.PerformanceOverlay
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.SystemUI
import com.andrerinas.openheadunit.aap.AapService
import android.content.IntentFilter
import com.andrerinas.openheadunit.view.ProjectionViewScaler
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.andrerinas.openheadunit.main.QuickSettingsFragment
import com.andrerinas.openheadunit.main.RenameNotice
import com.andrerinas.openheadunit.main.Aa174Notice
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.andrerinas.openheadunit.main.AutoStartLoadingScreenPolicy
import com.andrerinas.openheadunit.main.AutoStartOfferPolicy
import com.andrerinas.openheadunit.main.MainActivity
import java.io.File
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

    /** When the session dropped, and when the reconnect last showed a sign of life. Realtime, so a
     * unit that sleeps mid-reconnect does not come back with the whole wait still ahead of it. */
    private var disconnectedAtMs = 0L
    private var lastReconnectProgressAtMs = 0L
    private var reconnectIsUsb = false

    /**
     * True when the user themselves left the projection (Home, Recents), which fires
     * onUserLeaveHint just before onPause. An activity launching over us does not, and that is
     * what separates a call screen covering the projection from the user walking away from it.
     */
    private var userLeftDeliberately = false

    /** The open call-raise episode, or null when the projection is not covered by a call. */
    private var callRaiseEpisode: SelfModeCallRaisePolicy.Episode? = null

    /** What the last episode spent, so a call screen that relaunches cannot keep buying more. */
    private var lastCallRaiseAttempts = 0
    private var lastCallRaiseAtMs = 0L

    private val callRaiseTick = Runnable { tickCallRaise() }

    /**
     * Ken Burns scale animation applied to a static image loading screen.
     * Stored in a field so it can be cancelled when the loading overlay is
     * torn down or the activity is destroyed — otherwise the infinite-repeat
     * animator keeps consuming frame callbacks even when the view is gone.
     */
    private var kenBurnsAnimator: ObjectAnimator? = null
    private var stagePill: ConnectionStagePill? = null
    private var loggedPillStage: ConnectionStage? = null
    private var renderedPillShown: Boolean? = null

    private var initialX = 0f
    private var initialY = 0f
    private var isPotentialGesture = false

    // Activity-local override for fullscreen mode. If non-null, setFullscreen() will use this
    // instead of persisting to Settings. This keeps toggles local to the Activity lifecycle.
    private var activityFullscreenOverride: Settings.FullscreenMode? = null
    private var touchOverlayView: OverlayTouchView? = null
    private val performanceOverlay by lazy {
        PerformanceOverlay(settings) { videoDecoder.lastFrameRenderedMs }
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
                    crediblePictureOnSurface = videoDecoder.hasCrediblePicture,
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
            // Every tick, not only before the first frame: a codec rebuilt with cached parameter
            // sets renders gray P-frame output, so lastFrameRenderedMs is set while there is still
            // no picture, and gating this on it left the 850 ms check as the only attempt.
            maybeRecoverWarmRelaunch()
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame == 0L) {
                // First frame hasn't arrived yet — handled by the starting overlay. If the phone is
                // streaming video but nothing draws, offer to switch renderer (issue #767).
                maybeOfferRendererConfirm()
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
                hideReconnectingOverlay("frames resumed")
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
    /** When a touch of ours last completed, for [VideoFocusReleasePolicy.coverFollowsTouch]. */
    private var lastProjectionTouchMs = 0L

    private var lastSurfaceSetMs = 0L
    private var warmRelaunchCycleSpent = false

    /** One line per surface, not per tick: the gray-P-frame case is worth naming exactly once. */
    private var loggedKeyframelessPicture = false

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
        if (!loggedKeyframelessPicture &&
            videoDecoder.lastFrameRenderedMs != 0L && !videoDecoder.hasCrediblePicture
        ) {
            loggedKeyframelessPicture = true
            AppLog.w(
                "AapProjectionActivity: frames are rendering but no keyframe has decoded since the " +
                    "codec started - counting this surface as having no picture"
            )
        }
        val action = WarmRelaunchKeyframePolicy.decide(
            sessionHasRendered = videoDecoder.hasRenderedThisSession,
            crediblePictureOnSurface = videoDecoder.hasCrediblePicture,
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
    /**
     * Re-asks [ReconnectGracePolicy] rather than firing at a fixed delay, because a reconnect that
     * is visibly in flight has to outlive the base grace: the AOA switch alone is sized at 10s.
     */
    private val exitRunnable = object : Runnable {
        override fun run() {
            if (commManager.connectionState.value !is CommManager.ConnectionState.Disconnected) return
            noteReconnectProgressIfSwitching()
            val now = SystemClock.elapsedRealtime()
            val sinceProgress =
                if (lastReconnectProgressAtMs == 0L) null else now - lastReconnectProgressAtMs
            val ended = ReconnectGracePolicy.endedBecause(
                reconnectIsUsb, now - disconnectedAtMs, sinceProgress
            )
            if (ended == null) {
                watchdogHandler.postDelayed(this, ProjectionWatchdogPolicy.WATCHDOG_TICK_MS)
                return
            }
            AppLog.i(
                "AapProjectionActivity: Reconnect gave up after ${(now - disconnectedAtMs) / 1000}s " +
                    "($ended). Finishing activity."
            )
            hideReconnectingOverlay("reconnect timed out")
            finish()
        }
    }

    /** The AOA switch runs with the session still Disconnected, so the claim is the only sign of it. */
    private fun noteReconnectProgressIfSwitching() {
        if (reconnectIsUsb && UsbSwitchClaim.isLive()) noteReconnectProgress("a USB switch is in flight")
    }

    private fun noteReconnectProgress(what: String) {
        if (disconnectedAtMs == 0L) return
        if (lastReconnectProgressAtMs == 0L) {
            AppLog.i("AapProjectionActivity: reconnect is under way ($what), holding the overlay")
        }
        lastReconnectProgressAtMs = SystemClock.elapsedRealtime()
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

            if (settings.showPerformanceOverlay && !performanceOverlay.isAttached) {
                attachPerformanceOverlay()
            } else if (!settings.showPerformanceOverlay && performanceOverlay.isAttached) {
                performanceOverlay.setVisible(false)
                performanceOverlay.stop()
            } else if (settings.showPerformanceOverlay && performanceOverlay.isAttached) {
                performanceOverlay.setVisible(true)
                performanceOverlay.start()
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
            performanceOverlay.setMirror(mirror)
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
    private var autoStartOfferBanner: View? = null
    private var autoStartOfferResolved = false
    private var autoStartOfferTimer: CountDownTimer? = null
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

    /**
     * Offers Bluetooth auto-start for the one phone that just connected, once, over the picture.
     *
     * Asked here rather than on the home screen: a phone can wake this unit by itself, so nobody is
     * on that screen for the sessions the question is about. [AutoStartOfferPolicy] holds the rules.
     */
    private fun maybeOfferAutoStart() {
        if (autoStartOfferResolved || autoStartOfferBanner != null) return
        // One bottom bar at a time - both sit at Gravity.BOTTOM and would stack on each other.
        if (rendererBanner != null) return
        if (settings.wifiConnectionMode !=
            com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode.NATIVE
        ) return
        val adapter = BluetoothHelper.getBluetoothAdapter(this)
        if (adapter == null || !adapter.isEnabled) return

        val connectedMac = settings.lastConnectedNativeMac
        val cands = BluetoothHelper.driverCandidates(
            this, settings.nativePreferredDeviceMac, connectedMac
        )
        // The connected phone is pinned PROVEN here, so the offered tier really is the phones.
        val offers = AutoStartOfferPolicy.offers(
            phonesPaired = cands.offered.size,
            connectedMac = connectedMac,
            answeredMacs = settings.autoStartOfferAnsweredMacs,
            autoStartConfigured = settings.autoStartBluetoothDeviceMacs.isNotEmpty(),
        )
        if (!offers) return
        showAutoStartOfferBanner(connectedMac, cands.deviceFor(connectedMac)?.name ?: connectedMac)
    }

    /** A dismissible bottom bar: "Start automatically?" with Yes / No and a countdown to No. */
    private fun showAutoStartOfferBanner(mac: String, name: String) {
        runOnUiThread {
            if (autoStartOfferResolved || autoStartOfferBanner != null) return@runOnUiThread
            val container = findViewById<FrameLayout>(R.id.container) ?: return@runOnUiThread
            // Said out loud, both halves: a prompt that leaves no trace in a capture has cost this
            // thread three rounds, and the answer decides whether the app starts itself from now on.
            AppLog.i("AapProjectionActivity: the Bluetooth auto-start offer is up for %s (%s).", name, mac)

            fun resolve(outcome: String, turnOn: Boolean) {
                if (autoStartOfferResolved) return
                autoStartOfferResolved = true
                autoStartOfferTimer?.cancel()
                autoStartOfferTimer = null
                // Marked on every outcome, the timeout included, so the question is put once per
                // phone. An expiry is the No it counts as.
                settings.autoStartOfferAnsweredMacs = settings.autoStartOfferAnsweredMacs + mac
                if (turnOn) {
                    settings.autoStartBluetoothDeviceMacs = setOf(mac)
                    settings.autoStartBluetoothDeviceName = name
                    Settings.syncAutoStartBtMacsToDeviceStorage(this, setOf(mac))
                }
                AppLog.i("AapProjectionActivity: the Bluetooth auto-start offer was answered %s.", outcome)
                dismissAutoStartOfferBanner()
            }

            fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xE6000000.toInt())
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val label = TextView(this).apply {
                text = getString(R.string.auto_start_offer_message, name)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val countdown = TextView(this).apply {
                setTextColor(Color.LTGRAY)
                setPadding(dp(8), 0, dp(8), 0)
                text = getString(
                    R.string.auto_start_offer_countdown,
                    (AutoStartOfferPolicy.OFFER_TIMEOUT_MS / 1000L).toInt()
                )
            }
            val yes = Button(this).apply {
                text = getString(R.string.auto_start_offer_yes)
                setOnClickListener { resolve("yes", turnOn = true) }
            }
            val no = Button(this).apply {
                text = getString(R.string.auto_start_offer_no)
                setOnClickListener { resolve("no", turnOn = false) }
            }
            bar.addView(label)
            bar.addView(countdown)
            bar.addView(yes)
            bar.addView(no)
            bar.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            container.addView(bar)
            autoStartOfferBanner = bar
            bar.bringToFront()

            autoStartOfferTimer?.cancel()
            autoStartOfferTimer = object : CountDownTimer(AutoStartOfferPolicy.OFFER_TIMEOUT_MS, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    countdown.text = getString(
                        R.string.auto_start_offer_countdown,
                        ((millisUntilFinished + 999) / 1000).toInt()
                    )
                }

                override fun onFinish() {
                    resolve("by running out", turnOn = false)
                }
            }
            autoStartOfferTimer?.start()
        }
    }

    private fun dismissAutoStartOfferBanner() {
        runOnUiThread {
            autoStartOfferBanner?.let { (it.parent as? FrameLayout)?.removeView(it) }
            autoStartOfferBanner = null
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

        // In onCreate and not onStart: the whole point is to hear a call while the activity is
        // stopped behind the phone's call screen.
        registerAudioModeListener()

        setContentView(R.layout.activity_headunit)

        if (settings.showPerformanceOverlay) {
            attachPerformanceOverlay()
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
                                hideReconnectingOverlay("the session ended")
                                finish()
                            } else {
                                // Show the reconnecting overlay immediately. How long it stays is
                                // ReconnectGracePolicy's: the base below, extended while a reconnect
                                // is visibly in flight, because an AOA switch outlasts the USB base.
                                reconnectIsUsb = settings.lastConnectionType == Settings.CONNECTION_TYPE_USB
                                disconnectedAtMs = SystemClock.elapsedRealtime()
                                lastReconnectProgressAtMs = 0L
                                val baseMs = ReconnectGracePolicy.baseFor(reconnectIsUsb)
                                AppLog.i("AapProjectionActivity: Unexpected disconnect. Showing reconnecting overlay and waiting at least ${baseMs / 1000}s for recovery.")
                                showReconnectingOverlay()

                                // Re-initialize the first frame listener to hide the reconnecting overlay when video starts flowing
                                videoDecoder.onFirstFrameListener = {
                                    runOnUiThread {
                                        hideReconnectingOverlay("frames resumed")
                                    }
                                }

                                watchdogHandler.removeCallbacks(exitRunnable)
                                watchdogHandler.postDelayed(exitRunnable, ProjectionWatchdogPolicy.WATCHDOG_TICK_MS)
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete -> {
                            watchdogHandler.removeCallbacks(exitRunnable)

                            // Restart the video watchdog so it can request keyframes for the new session
                            watchdogHandler.removeCallbacks(videoWatchdogRunnable)
                            watchdogHandler.postDelayed(videoWatchdogRunnable, 1000)

                            // Lock the resolution so that orientation changes don't cause re-negotiation
                            HeadUnitScreenConfig.lockResolution()
                            HeadUnitScreenConfig.onMarginsDiverged = ::onMarginsDiverged
                            applyOrientationSettings()

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
                        // The reconnect is answering. Each of these refreshes the grace.
                        is CommManager.ConnectionState.Connecting -> noteReconnectProgress("connecting")
                        is CommManager.ConnectionState.Connected -> noteReconnectProgress("connected")
                        is CommManager.ConnectionState.StartingTransport ->
                            noteReconnectProgress("starting the transport")
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
        performanceOverlay.bringToFront()

        // Set up custom loading screen if configured
        setupCustomLoadingScreen()

        // The pill the home screen raised carries on here, so the step the user was reading
        // survives the handoff. No X: the session is up, and disconnect_button is this screen's.
        stagePill = findViewById<View>(R.id.auto_connect_pill)?.let { ConnectionStagePill(it) }
        stagePill?.setCancelAction(null)
        // Whatever this screen is already saying, so the pill does not contradict the handover
        // text setupCustomLoadingScreen() just applied.
        stagePill?.setHeadline(
            findViewById<TextView>(R.id.overlay_text)?.text
                ?: getString(R.string.android_auto_starting)
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStageTracker.stage.collect { renderProjectionPill() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStageTracker.network.collect {
                    stagePill?.applyNetwork(it, animate = true)
                    renderProjectionPill()
                }
            }
        }

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
                    ToastUtils.showToast(this@AapProjectionActivity, R.string.gesture_hint, Toast.LENGTH_LONG, force = true)
                    settings.gestureHintShown = true
                }

                maybeOfferAutoStart()
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
        Aa174Notice.dismiss()
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
        // After the removeCallbacks above, never before: the tick posts on the same handler.
        maybeOpenCallRaiseEpisode()
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        userLeftDeliberately = false
        closeCallRaiseEpisode("the projection is back in front")
        AppLog.i("AapProjectionActivity: onResume")
        // Show the one-time rename notice even here, on top of an active projection.
        RenameNotice.maybeShow(this, App.provide(this).settings)
        Aa174Notice.maybeShow(this, App.provide(this).settings)
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
        renderProjectionPill()
    }

    /**
     * [reason] is the caller's, not this method's. Two of the four callers are teardown paths, and
     * with the reason hard-coded both of them logged that frames had resumed on a session that was
     * ending - which is exactly the kind of line these logs get read literally for.
     */
    private fun hideReconnectingOverlay(reason: String) {
        AppLog.i("Hiding reconnecting overlay - $reason")
        overlayState = OverlayState.HIDDEN
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.GONE
        detail?.visibility = View.GONE
        button?.visibility = View.GONE
        stagePill?.hide()
        renderedPillShown = false
        stopCustomLoadingMedia()
        touchOverlayView?.requestFocus()
    }

    /**
     * The pill is a child of the loading overlay, so the overlay's own visibility gates it; this
     * only adds the setting and "is there a step to name at all".
     */
    private fun renderProjectionPill() {
        val pill = stagePill ?: return
        val stage = ConnectionStageTracker.stage.value
        val overlayUp = findViewById<View>(R.id.loading_overlay)?.visibility == View.VISIBLE
        val shown = AutoStartLoadingScreenPolicy.showsProjectionPill(
            loadingOverlayVisible = overlayUp,
            stage = stage,
            pillSettingOn = settings.loadingScreenShowPill
        )
        // The same line MainActivity writes, prefixed with this activity instead: frame sampling
        // could not separate this pill from the loading screen behind it, so the log grades it.
        // Gated on a change: the stage flow re-emits far more often than the pill changes.
        if (stage != loggedPillStage || shown != renderedPillShown) {
            loggedPillStage = stage
            renderedPillShown = shown
            val step = when {
                shown -> stage?.name ?: "hidden"
                stage == null -> "hidden"
                !overlayUp -> "${stage.name} (not shown, the loading screen is down)"
                else -> "${stage.name} (not shown, the pill is switched off)"
            }
            AppLog.i("AapProjectionActivity: status pill step: $step")
        }
        if (!shown) {
            pill.hide()
            return
        }
        pill.show()
        pill.applyStage(stage, animate = true)
    }

    private fun setupCustomLoadingScreen() {
        // Apply any context-specific status text handed over by MainActivity
        // (e.g. "Connecting to Pixel 8…") to BOTH the default-content text and
        // the custom-media text overlay. Done before the early-return paths so
        // the override applies whether or not custom media is configured. Read
        // once and cleared so the value can't leak into a later connection.
        val handover = pendingStatusText
        pendingStatusText = null
        val mediaResumeMs = pendingLoadingMediaPositionMs
        pendingLoadingMediaPositionMs = 0
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
                        // Where the home screen's copy had got to, so the cut is not a restart.
                        if (mediaResumeMs > 0) customVideo.seekTo(mediaResumeMs)

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
            val isDestroyedCompat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed
            if (!isFinishing && !isDestroyedCompat) {
                recreate()
            }
        }
    }

    private fun hideLoadingOverlay(loadingOverlay: View?) {
        overlayState = OverlayState.HIDDEN
        AppLog.i("Hiding loading overlay after first video frame")
        stagePill?.hide()
        renderedPillShown = false

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

        if (settings.wifiConnectionMode == com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode.NATIVE) {
            options.add(ExitOption(R.string.switch_driver, R.drawable.ic_phone, Color.LTGRAY))
        }

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
                    R.string.switch_driver -> {
                        switchDriver()
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

    private fun switchDriver() {
        AppLog.i("AapProjectionActivity: User requested switch driver")
        val intent = Intent(this, AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_SWITCH_DEVICE
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
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
                ToastUtils.showToast(this, "PiP failed: ${e.localizedMessage}", Toast.LENGTH_SHORT, force = true)
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
            performanceOverlay.setVisible(false)
        } else {
            // Restore UI if needed
            performanceOverlay.setVisible(settings.showPerformanceOverlay)
            setFullscreen()
        }
    }

    /**
     * Opens a call-raise episode when something covered the projection, in Self Mode.
     *
     * Self Mode is the only place this can happen: everywhere else the call screen lands on the
     * phone and the projection is on the head unit. The audio mode is the episode's first
     * observation rather than a condition for opening one, because the call screen covers us before
     * the call registers. An episode that never sees a call closes itself.
     */
    private fun maybeOpenCallRaiseEpisode(fromModeChange: Boolean = false) {
        if (!fromModeChange) closeCallRaiseEpisode("covered again")

        val verdict = SelfModeCallRaisePolicy.coverVerdict(
            userLeft = userLeftDeliberately,
            selfMode = AapService.instance?.isSelfModeActive() == true,
            pipActive = App.isPiPActive,
            enabled = settings.raiseProjectionDuringCall,
            episodeOpen = callRaiseEpisode != null,
        )
        if (verdict != SelfModeCallRaisePolicy.CoverVerdict.OPEN) {
            // Every cover accounts for itself, or a raise that never happens leaves nothing to read
            // in the log. A later look at the same cover has nothing new to say.
            if (!fromModeChange) {
                AppLog.i("AapProjectionActivity: leaving this cover alone - ${verdict.reason}")
            }
            return
        }

        val audioMode = audioModeOrNormal()
        val callActive = CallState.isCallActive(audioMode, MicRecorder.holdsCommunicationMode)
        val nowMs = SystemClock.elapsedRealtime()
        val carried = SelfModeCallRaisePolicy.carriedAttempts(lastCallRaiseAttempts, lastCallRaiseAtMs, nowMs)
        val what = if (callActive) "covered during a call" else "covered, watching for a call"
        AppLog.i("AapProjectionActivity: $what (audioMode=$audioMode, $carried attempts already spent)")
        callRaiseEpisode = SelfModeCallRaisePolicy.Episode(
            startedAtMs = nowMs,
            sawCallActive = callActive,
            attempts = carried,
            lastAttemptAtMs = if (carried > 0) lastCallRaiseAtMs else 0L,
        )
        watchdogHandler.postDelayed(callRaiseTick, SelfModeCallRaisePolicy.TICK_MS)
    }

    /**
     * Ends an open episode. The reason is logged only when there was one, because a successful raise
     * ends the episode from onResume before the tick that would otherwise have reported it can run.
     */
    private fun closeCallRaiseEpisode(reason: String) {
        if (callRaiseEpisode != null) {
            AppLog.i("AapProjectionActivity: call raise finished - $reason")
        }
        callRaiseEpisode = null
        watchdogHandler.removeCallbacks(callRaiseTick)
    }

    private fun tickCallRaise() {
        val episode = callRaiseEpisode ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val callActive = CallState.isCallActive(audioModeOrNormal(), MicRecorder.holdsCommunicationMode)
        val observed = SelfModeCallRaisePolicy.observe(episode, nowMs, callActive)
        val action = SelfModeCallRaisePolicy.decide(
            nowMs = nowMs,
            episode = observed,
            callActive = callActive,
            isForeground = isForeground,
            pipActive = App.isPiPActive,
        )
        val reason = SelfModeCallRaisePolicy.describe(action, observed, callActive, isForeground)

        val next = when (action) {
            SelfModeCallRaisePolicy.Action.DONE -> {
                AppLog.i("AapProjectionActivity: call raise finished - $reason")
                callRaiseEpisode = null
                return
            }
            SelfModeCallRaisePolicy.Action.RAISE -> {
                AppLog.i("AapProjectionActivity: raising the projection - $reason")
                requestProjectionRaise()
                SelfModeCallRaisePolicy.onRaised(observed, nowMs, callActive).also {
                    lastCallRaiseAttempts = it.attempts
                    lastCallRaiseAtMs = nowMs
                }
            }
            SelfModeCallRaisePolicy.Action.WAIT -> observed
        }
        callRaiseEpisode = next
        watchdogHandler.postDelayed(callRaiseTick, SelfModeCallRaisePolicy.nextTickDelayMs(next))
    }

    /** The service owns the overlay trampoline, and a paused activity cannot start itself. */
    private fun requestProjectionRaise() {
        sendBroadcast(Intent(AapService.ACTION_RAISE_PROJECTION).apply { setPackage(packageName) })
    }

    private fun audioModeOrNormal(): Int = try {
        (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).mode
    } catch (e: Exception) {
        AppLog.w("AapProjectionActivity: Could not read the audio mode: ${e.message}")
        android.media.AudioManager.MODE_NORMAL
    }

    /**
     * The second way into an episode: a call that only registers after the cover.
     *
     * Exact where the confirm window is a guess, and it is what catches an outgoing call whose
     * dialling outlasts the window. API 31+; below it the window is the whole story.
     */
    private var audioModeListener: android.media.AudioManager.OnModeChangedListener? = null

    private fun registerAudioModeListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val listener = android.media.AudioManager.OnModeChangedListener { mode ->
                val callish = CallState.isCallActive(mode, MicRecorder.holdsCommunicationMode) ||
                    CallState.isCallStarting(mode)
                if (!isForeground && callish) maybeOpenCallRaiseEpisode(fromModeChange = true)
            }
            (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .addOnModeChangedListener(ContextCompat.getMainExecutor(this), listener)
            audioModeListener = listener
        } catch (e: Exception) {
            AppLog.w("AapProjectionActivity: Could not watch the audio mode: ${e.message}")
        }
    }

    private fun unregisterAudioModeListener() {
        val listener = audioModeListener ?: return
        audioModeListener = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .removeOnModeChangedListener(listener)
        } catch (_: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        // Optional: Auto-enter PiP if user presses home

        // For now, we only enter via dialog as requested.
        // Also the one signal that the next onPause is the user's own doing, so the call raise
        // below never argues with someone who chose to leave.
        userLeftDeliberately = true
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
        loggedKeyframelessPicture = false
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

        val anchorMoved = HeadUnitScreenConfig.updateSurfaceDimensions(width, height)

        // What is cached is the usable area, which the screen config has already turned the right
        // way up, so the orientation guard this used to carry could only reject a correct reading.
        val shouldCache = !App.isPiPActive

        // Cached whether or not the anchor moved: service discovery answers before any surface
        // exists, so next session's announcement has only this to go on.
        val canvasW = HeadUnitScreenConfig.getUsableWidth()
        val canvasH = HeadUnitScreenConfig.getUsableHeight()
        val canvasHash = HeadUnitScreenConfig.computeSettingsHash(settings)
        if (shouldCache && (settings.cachedSurfaceWidth != canvasW ||
                settings.cachedSurfaceHeight != canvasH ||
                settings.cachedSurfaceSettingsHash != canvasHash)
        ) {
            settings.cachedSurfaceWidth = canvasW
            settings.cachedSurfaceHeight = canvasH
            settings.cachedSurfaceSettingsHash = canvasHash
        } else if (!shouldCache) {
            AppLog.i("[UI_DEBUG_FIX] Skipping surface dimension cache update due to transient orientation mismatch: ${width}x${height}")
        }

        if (anchorMoved) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch! Expected: ${prevUsableW}x${prevUsableH}, Actual: ${width}x${height}")
            reannounceMargins()
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
        val nowMs = SystemClock.elapsedRealtime()
        if (VideoFocusReleasePolicy.shouldReleaseOnSurfaceLost(
                coverFollowsTouch = VideoFocusReleasePolicy.coverFollowsTouch(lastProjectionTouchMs, nowMs),
                sessionConnected = commManager.isConnected,
                activityEnding = isFinishing || isChangingConfigurations,
                pipActive = App.isPiPActive,
                focusCycleInFlight = focusCycleGainPending,
            )
        ) {
            commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        } else {
            AppLog.i(
                "AapProjectionActivity: the surface went away ${nowMs - lastProjectionTouchMs}ms after " +
                    "a touch - holding video focus so Android Auto keeps its keyboard up"
            )
        }
        videoDecoder.stopIfCurrentSurface(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
    }


    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView as View, width, height)
        }
    }

    /**
     * Send the panel's per-side margins to a running session. False when there is no session to
     * tell, which leaves the announced margins standing so the next recalculate tries again.
     * A surface change reaches here twice, through recalculate and then directly; one send.
     */
    private fun reannounceMargins(): Boolean {
        if (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
            return false
        }
        if (HeadUnitScreenConfig.marginsMatchAnnounced()) return true
        commManager.sendUpdateUiConfigRequest(
            HeadUnitScreenConfig.getLeftMargin(),
            HeadUnitScreenConfig.getTopMargin(),
            HeadUnitScreenConfig.getRightMargin(),
            HeadUnitScreenConfig.getBottomMargin()
        )
        HeadUnitScreenConfig.recordAnnouncedMargins(
            HeadUnitScreenConfig.getWidthMargin(), HeadUnitScreenConfig.getHeightMargin()
        )
        AppLog.i("[UI_DEBUG_FIX] AA is already running, send corrected via sendUpdateUiConfigRequest")
        return true
    }

    /**
     * The margins moved after they were announced, which on a device whose insets are still
     * settling also means the scale was computed against the old reading. Re-announce and redraw.
     */
    private fun onMarginsDiverged(): Boolean {
        val reannounced = reannounceMargins()
        runOnUiThread {
            val view = projectionView as? View ?: return@runOnUiThread
            ProjectionViewScaler.updateScale(view, videoDecoder.videoWidth, videoDecoder.videoHeight)
        }
        return reannounced
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
        // The coordinates come from the overlay, so only the overlay can be the denominator. The
        // screen config is the fallback for a touch that beats the first layout, and outside
        // immersive it describes the display rather than the window this event was measured in.
        val overlay = touchOverlayView
        val viewW = (overlay?.width ?: 0).takeIf { it > 0 }?.toFloat()
            ?: view.width.takeIf { it > 0 }?.toFloat()
            ?: HeadUnitScreenConfig.getUsableWidth().toFloat()
        val viewH = (overlay?.height ?: 0).takeIf { it > 0 }?.toFloat()
            ?: view.height.takeIf { it > 0 }?.toFloat()
            ?: HeadUnitScreenConfig.getUsableHeight().toFloat()

        if (viewW <= 0 || viewH <= 0) return

        val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
        val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val corrected = TouchCoordinateMapper.map(
                rawX = event.getX(pointerIndex),
                rawY = event.getY(pointerIndex),
                inputSurfaceWidth = viewW,
                inputSurfaceHeight = viewH,
                negotiatedWidth = videoW,
                negotiatedHeight = videoH,
                marginWidth = marginW,
                marginHeight = marginH,
                fitMode = settings.videoFitMode,
                hudMirroring = settings.hudMirroring
            )
            pointerData.add(Triple(pointerId, corrected.x, corrected.y))
        }

        // The only instrument that says whether a tap reached the pixel it looked like it hit.
        // Verbose because it is one line per pointer per motion event.
        if (AppLog.LOG_VERBOSE) {
            val first = pointerData.firstOrNull()
            AppLog.v(
                "[UI_DEBUG] Touch map: raw=${event.getX(0).toInt()},${event.getY(0).toInt()} " +
                    "-> video=${first?.second},${first?.third} view=${viewW.toInt()}x${viewH.toInt()} " +
                    "video=${videoW}x${videoH} margin=${marginW.toInt()}x${marginH.toInt()} fit=${settings.videoFitMode}"
            )
        }

        commManager.send(TouchEvent(ts, action, event.actionIndex, pointerData))
        // ACTION_UP only: a swipe-up-home or edge-back gesture ends in ACTION_CANCEL, and neither
        // must look like the text-field tap that opens Android Auto's phone keyboard.
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            lastProjectionTouchMs = ts
        }
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
        autoStartOfferTimer?.cancel()
        autoStartOfferTimer = null
        HeadUnitScreenConfig.onMarginsDiverged = null
        HeadUnitScreenConfig.clearAnnouncedMargins()
        closeCallRaiseEpisode("the projection is going away")
        unregisterAudioModeListener()
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
        performanceOverlay.release()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        App.isPiPActive = false
        // On a singleTask relaunch the new instance's onCreate runs before this old instance's
        // onDestroy, so it has already registered its own view callback and decoder listeners.
        // Deregister only what still belongs to this instance, or the teardown of the old
        // instance strips the live one: its view callback stays armed to fire a stale
        // surface-destroy, and its listeners get nulled out from under it.
        if (::projectionView.isInitialized) projectionView.removeCallback(this)
        if (videoDecoder.onFpsChanged === performanceOverlay.fpsListener) videoDecoder.onFpsChanged = null
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

        /**
         * Where MainActivity's loading video had got to when it handed over, so this screen
         * resumes it instead of cutting back to the first frame. Read and cleared once.
         */
        @Volatile var pendingLoadingMediaPositionMs: Int = 0

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }

        fun minimizeToHome(context: Context) {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(homeIntent)
            } catch (e: Exception) {
                AppLog.w("[AapProjectionActivity] minimizeToHome failed: ${e.message}")
            }
        }

        fun returnToAppHome(context: Context) {
            try {
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(mainIntent)
            } catch (e: Exception) {
                AppLog.w("[AapProjectionActivity] returnToAppHome failed: ${e.message}")
            }
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.i("[AapProjectionActivity] onConfigurationChanged: orientation=${newConfig.orientation}")
        if (!HeadUnitScreenConfig.isResolutionLocked) {
            HeadUnitScreenConfig.init(this, resources.displayMetrics, settings)
        }
    }

    private fun applyOrientationSettings() {
        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            applyStickyOrientation()
            if (!HeadUnitScreenConfig.isResolutionLocked) {
                // Before resolution is locked, allow sensor to orient the activity
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
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
        performanceOverlay.bringToFront()
    }

    private fun attachPerformanceOverlay() {
        performanceOverlay.attachTo(findViewById(R.id.container))
        videoDecoder.onFpsChanged = performanceOverlay.fpsListener
        performanceOverlay.start()
    }
}
