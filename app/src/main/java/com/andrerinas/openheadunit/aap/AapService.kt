package com.andrerinas.openheadunit.aap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.app.ActivityLaunchPolicy
import com.andrerinas.openheadunit.app.BootCompleteReceiver
import com.andrerinas.openheadunit.app.BootLoopPolicy
import com.andrerinas.openheadunit.app.BtAutoDisconnectArm
import com.andrerinas.openheadunit.app.BtAutoDisconnectPolicy
import com.andrerinas.openheadunit.app.BtAutoStartRearmPolicy
import com.andrerinas.openheadunit.app.ForegroundServiceTypePolicy
import com.andrerinas.openheadunit.app.WifiAutoStartReceiver
import com.andrerinas.openheadunit.connection.wifi.HotspotExitAction
import com.andrerinas.openheadunit.connection.wifi.UsbSessionQuiescePolicy
import com.andrerinas.openheadunit.connection.wifi.UserExitHotspotPolicy
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.BluetoothAddressSeedPolicy
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.DummyVpnPolicy
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.aap.protocol.messages.NightModeEvent
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.NetworkDiscovery
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.location.GpsLocationService
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.LogExporter
import com.andrerinas.openheadunit.utils.NightModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.app.NotificationManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.view.View
import android.view.WindowManager
import android.media.AudioManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfModeDisconnectPolicy
import com.andrerinas.openheadunit.connection.usb.UsbLauncherManager
import com.andrerinas.openheadunit.connection.wifi.LinkLossTeardownPolicy
import com.andrerinas.openheadunit.connection.wifi.LinkLossTrigger
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ProjectionQrSnapshot
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SessionEndGroupPolicy
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherStopSequence
import com.andrerinas.openheadunit.connection.wifi.direct.StationScanMonitor
import com.andrerinas.openheadunit.connection.wifi.direct.StationStandDown
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherHelper
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherManual
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServer
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.VpnControl
import com.andrerinas.openheadunit.utils.protoUint32ToLong

/**
 * Top-level foreground service that manages the Android Auto connection lifecycle.
 *
 * Responsibilities:
 * - Manages the [CommManager] connection state machine (USB and WiFi)
 * - Drives [AapProjectionActivity] via intents and connection state flow
 * - Runs a [WirelessServer] for the "server" WiFi mode and coordinates [NetworkDiscovery] scans
 * - Keeps a foreground notification updated to reflect the current connection state
 * - Manages car mode, night mode, media session, and GPS location service
 *
 * Connection types:
 * - **USB**: [UsbReceiver] detects attach → [checkAlreadyConnectedUsb] → [connectUsbWithRetry]
 * - **WiFi (client)**: [NetworkDiscovery] finds a Headunit Server → [CommManager.connect]
 * - **WiFi (server)**: [WirelessServer] accepts incoming sockets from AA Wireless / Self Mode
 * - **Self Mode**: starts [WirelessServer] and launches the AA Wireless Setup Activity on-device
 */
class AapService : Service() {

    // SupervisorJob prevents a child coroutine failure from cancelling the whole scope
    val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var uiModeManager: UiModeManager
    private var nightModeManager: NightModeManager? = null
    private var wifiAutoStartReceiver: WifiAutoStartReceiver? = null
    private var mediaSession: MediaSessionCompat? = null
    private val wifiLauncherManager = WifiLauncherManager(this)

    /**
     * Counts this unit's own WiFi scans into the log. Nothing acts on it; it exists so a reporter's
     * capture can carry the one piece of evidence the periodic-outage theory has always lacked.
     */
    private val stationScanMonitor = StationScanMonitor()
    private val usbLauncherManager = UsbLauncherManager(this)
    private val selfLauncherManager = SelfLauncherManager(this, wifiLauncherManager)

    /**
     * Set when a link-loss teardown closed the session because station WiFi was going away.
     *
     * The ordinary answer to a disconnect is to restart the discovery loop two seconds later, and
     * that is wrong here: the network it would scan is the one on its way down. What it finds is
     * whatever interface enumerates first — a modem bridge, typically — and it sweeps that subnet
     * every ten seconds until WiFi returns, which costs nothing but reads in a captured log
     * exactly like discovery probing the wrong network for real.
     *
     * Cleared when a network comes back, which is also what revives the loop: `onAvailable` calls
     * `startScan()` on the instance that is still there.
     */
    @Volatile
    var discoveryDormantAfterWifiLoss: Boolean = false

    private inline fun <T> safeMediaSessionCall(crossinline block: (MediaSessionCompat) -> T): T? {
        if (isDestroying) return null
        val session = mediaSession ?: return null
        return try {
            block(session)
        } catch (e: Exception) {
            // Catching binder death: DeadObjectException or DeadSystemException
            AppLog.e("MediaSession call failed (Binder dead?): ${e.message}")
            null
        }
    }
    private var permanentFocusRequest: AudioFocusRequest? = null

    private var lastAaMediaMetadata: MediaPlayback.MediaMetaData? = null
    private var lastAaPlaybackPositionMs: Long = 0L
    private var lastAaPlaybackIsPlaying: Boolean? = null
    private var wasPlayingBeforeDisconnect = false
    private var lastDisconnectTimestampMs = 0L
    private var mediaSessionIsPlaying = false
    private var mediaMetadataDecodeJob: Job? = null
    private var autoResumePlaybackJob: Job? = null
    /** Decoded on a background thread in [scheduleApplyAaMediaMetadata]; reused for notification updates on position ticks. */
    private var cachedAaAlbumArtBitmap: Bitmap? = null
    private var settingsPrefs: SharedPreferences? = null
    private val settings: Settings by lazy { App.provide(this).settings }
    private val mediaNotification by lazy { BackgroundNotification(this) }

    /** Last `NetworkCallback.onAvailable` we acted on, for the debounce in [registerNetworkMonitor]. */
    private var lastNetworkAvailableKickMs: Long = 0L

    /**
     * Set when a network-available kick found a sweep already in flight and was folded into it.
     * The sweep it joined was started for the *previous* network, so the one after it should not
     * wait out the ordinary re-arm. Consumed once, in the discovery listener's `onScanFinished`.
     */
    @Volatile
    var rescanWithoutWaiting: Boolean = false

    private val settingsPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.KEY_SYNC_MEDIA_SESSION_AA_METADATA) {
                serviceScope.launch(Dispatchers.Main) {
                    refreshMediaSessionMetadataForPrefsChange()
                }
            }

            if (key == Settings.KEY_LOG_SOURCE || key == Settings.KEY_LOG_LEVEL || key == Settings.KEY_LOG_CAPTURE_ENABLED) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        syncLogBackendState()
                    } catch (e: Exception) {
                        AppLog.e("LogExporter: failed to sync state", e)
                    }
                }
            }

            if (key == Settings.KEY_MEDIA_VOLUME_OFFSET || key == Settings.KEY_ASSISTANT_VOLUME_OFFSET || key == Settings.KEY_NAVIGATION_VOLUME_OFFSET) {
                serviceScope.launch(Dispatchers.Main) {
                    commManager.updateAudioGains()
                }
            }
        }

    private fun syncLogBackendState() {
        AppLog.init(settings, this@AapService)

        if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
            if (LogExporter.isCapturing) {
                LogExporter.stopCapture()
                AppLog.d("LogExporter: stopped because logSource=APPLOG_FILE")
            }
            return
        }

        val newLogLevel = settings.exporterLogLevel
        val exporterCaptureEnabled = settings.exporterCaptureEnabled
        val isCapturing = LogExporter.isCapturing
        val currentLogLevel = LogExporter.currentLevel

        if (!exporterCaptureEnabled || newLogLevel == LogExporter.LogLevel.SILENT) {
            if (isCapturing) {
                LogExporter.stopCapture()
                AppLog.d("LogExporter: stopped (enabled=$exporterCaptureEnabled, level=${newLogLevel.name})")
            }
        } else if (!isCapturing || currentLogLevel != newLogLevel) {
            LogExporter.startCapture(this@AapService, newLogLevel)
            AppLog.d("LogExporter: started with level ${newLogLevel.name}")
        }
    }

    /**
     * Set to `true` before calling [stopSelf] or entering [onDestroy] to suppress any
     * flow observers that would otherwise update the already-dismissed notification.
     */
    private var isDestroying = false
    private var hasEverConnected = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var wifiReadyCallback: ConnectivityManager.NetworkCallback? = null

    private var wifiReadyTimeoutJob: Job? = null
    private var wifiModeInitialized = false

    /**
     * Which feature the dummy VPN is up for, or `null` when we did not start it.
     *
     * See [DummyVpnPolicy]: the VPN used to be stopped from [stopWirelessServer], which every
     * mode change runs, so a user's VPN went down moments after it came up. Ownership is what
     * decides now, and a VPN with no owner is never touched.
     */
    var dummyVpnOwner: DummyVpnPolicy.Owner? = null

    /**
     * Partial wake lock acquired when the service starts from boot/screen-on.
     * Keeps the CPU active while the head unit runs without ACC, making the
     * service harder for MediaTek's background power saving to kill.
     */
    private var bootWakeLock: PowerManager.WakeLock? = null

    /**
     * Runtime-registered receiver for MEDIA_BUTTON intents.
     * Unlike manifest-registered receivers, runtime receivers are NOT affected by
     * Android 8+ implicit broadcast restrictions — this is a critical difference
     * that makes steering wheel controls work on China headunits.
     */
    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
                AppLog.i("Runtime MEDIA_BUTTON receiver fired")
                safeMediaSessionCall {
                    MediaButtonReceiver.handleIntent(it, intent)
                }
            }
        }
    }

    /**
     * Set when the phone sends VIDEO_FOCUS_NATIVE (user tapped "Exit" in AA).
     * Suppresses [scheduleReconnectIfNeeded] so we don't try to reconnect to a
     * stale dongle that hasn't re-enumerated yet.
     * Cleared on USB detach (dongle reset complete) or on fresh USB attach.
     */
    @Volatile
    var userExitedAA = false
    @Volatile
    var userExitCooldownUntil = 0L

    /** elapsedRealtime when the current session reached Connected, or 0 when nothing is up. */
    @Volatile
    private var sessionConnectedAt = 0L

    /**
     * Pending "a watched Bluetooth device went away" timers, one per address. Touched only from
     * the main thread, where the receiver and the timer bodies both run, so it needs no locking.
     */
    private val btAutoDisconnectJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Set just before a Bluetooth auto-disconnect ends the session: the stack must stay down
     * afterwards, which for Helper and Auto means undoing their user-exit discovery restart.
     */
    @Volatile
    private var btAutoDisconnectStandDown = false

    private val commManager get() = App.provide(this).commManager

    fun isSelfModeActive() = selfLauncherManager.isActive

    /**
     * Whether a Native AA poke or handshake is in flight, for callers outside the service that
     * cannot reach the launcher. Null when Native is not the armed mode.
     */
    fun nativeAttemptInFlight(): Boolean? =
        (wifiLauncherManager.active as? WifiLauncherNative)?.handshakeManager?.isAttemptInFlight()

    fun updateMediaSessionState(isPlaying: Boolean) {
        mediaSessionIsPlaying = isPlaying
        var actions = PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        var state: Int

        if (isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING
            actions = actions or PlaybackStateCompat.ACTION_PAUSE
        } else {
            state = PlaybackStateCompat.STATE_STOPPED
            actions = actions or PlaybackStateCompat.ACTION_PLAY
        }

        safeMediaSessionCall {
            it.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(state, lastAaPlaybackPositionMs, if (isPlaying) 1.0f else 0.0f)
                    .setActions(actions)
                    .build()
            )
        }
        AppLog.d(
            "MediaSession: State updated to ${if (isPlaying) "PLAYING" else "STOPPED"}, positionMs=$lastAaPlaybackPositionMs"
        )
    }

    private fun applyPlaceholderMediaMetadata() {
        safeMediaSessionCall {
            it.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.video))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.media_session_aa_status_placeholder))
                    .build()
            )
        }
    }

    private fun refreshMediaSessionMetadataForPrefsChange() {
        if (isDestroying) return
        val sync = App.provide(this).settings.syncMediaSessionWithAaMetadata
        if (!sync) {
            applyPlaceholderMediaMetadata()
            cachedAaAlbumArtBitmap = null
            mediaNotification.cancel()
        } else {
            val last = lastAaMediaMetadata
            if (last != null) {
                scheduleApplyAaMediaMetadata(last)
            } else {
                applyPlaceholderMediaMetadata()
                cachedAaAlbumArtBitmap = null
                mediaNotification.cancel()
            }
        }
    }

    private fun onAaMediaMetadataFromPhone(meta: MediaPlayback.MediaMetaData) {
        if (isDestroying) return
        lastAaMediaMetadata = meta
        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        // Avoid showing a previous track's art with new title/artist until decode finishes.
        cachedAaAlbumArtBitmap = null
        scheduleApplyAaMediaMetadata(meta)
    }

    private fun onAaPlaybackStatusFromPhone(status: MediaPlayback.MediaPlaybackStatus) {
        if (isDestroying) return
        if (status.hasPlaybackSeconds()) {
            lastAaPlaybackPositionMs = status.playbackSeconds.protoUint32ToLong() * 1000L
        }
        val isPlayingFromStatus = resolveIsPlayingFromStatus(status)
        lastAaPlaybackIsPlaying = isPlayingFromStatus
        mediaSessionIsPlaying = isPlayingFromStatus

        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        updateMediaSessionState(isPlayingFromStatus)
        lastAaMediaMetadata?.let { updateMediaNotification(it) }
    }

    private fun resolveIsPlayingFromStatus(status: MediaPlayback.MediaPlaybackStatus): Boolean {
        if (!status.hasState()) return lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying
        return when (status.state) {
            MediaPlayback.MediaPlaybackStatus.State.PLAYING -> true
            MediaPlayback.MediaPlaybackStatus.State.STOPPED,
            MediaPlayback.MediaPlaybackStatus.State.PAUSED -> false
        }
    }

    private fun updateMediaNotification(meta: MediaPlayback.MediaMetaData) {
        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        mediaNotification.notify(
            metadata = meta,
            playbackSeconds = lastAaPlaybackPositionMs / 1000L,
            isPlaying = lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying,
            albumArtBitmap = cachedAaAlbumArtBitmap
        )
    }

    private fun scheduleApplyAaMediaMetadata(meta: MediaPlayback.MediaMetaData) {
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = serviceScope.launch(Dispatchers.Default) {
            val bytes = if (meta.hasAlbumArt() && !meta.albumArt.isEmpty) meta.albumArt.toByteArray() else null
            val bitmap = bytes?.let { decodeAlbumArt(it) }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (isDestroying) return@withContext
                if (!App.provide(this@AapService).settings.syncMediaSessionWithAaMetadata) return@withContext
                // Drop stale decode results if newer metadata arrived while we were decoding.
                if (lastAaMediaMetadata !== meta) return@withContext
                cachedAaAlbumArtBitmap = bitmap
                applyAaMediaMetadataToSession(meta, bitmap)
                updateMediaNotification(meta)
            }
        }
    }

    private fun decodeAlbumArt(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                opts.inJustDecodeBounds = false
                opts.inSampleSize = 1
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
            var sampleSize = 1
            val maxDim = 720
            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sampleSize
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun applyAaMediaMetadataToSession(meta: MediaPlayback.MediaMetaData, albumArt: Bitmap?) {
        val session = mediaSession ?: return
        val title = when {
            meta.hasSong() && meta.song.isNotBlank() -> meta.song
            else -> getString(R.string.video)
        }
        val artist = when {
            meta.hasArtist() && meta.artist.isNotBlank() -> meta.artist
            else -> getString(R.string.media_session_aa_status_placeholder)
        }
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
        if (meta.hasAlbum() && meta.album.isNotBlank()) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album)
        }
        if (meta.hasDurationSeconds()) {
            val durationSec = meta.durationSeconds.protoUint32ToLong()
            if (durationSec > 0L) {
                b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
            }
        }
        if (albumArt != null) {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        }
        safeMediaSessionCall { it.setMetadata(b.build()) }
    }

    // Receives ACTION_REQUEST_NIGHT_MODE_UPDATE broadcasts sent by the key-binding handler
    // when the user presses the night-mode toggle key.
    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    private val sensorRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REFRESH_SENSORS) {
                AppLog.i("AapService: Received request to refresh all sensors")
                // Re-send current states
                nightModeManager?.resendCurrentState()
            } else if (intent.action == ACTION_RESTART_AUDIO) {
                AppLog.i("AapService: Received request to restart audio")
                commManager.restartAudio()
            }
        }
    }

    // Receives ACTION_RAISE_PROJECTION, sent by the projection activity when a call screen has
    // covered it in Self Mode. The activity is paused at that point, so the launch has to come from
    // here, where the overlay trampoline lives.
    private val raiseProjectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RAISE_PROJECTION) return
            launchAapProjectionActivity(allowNotificationFallback = false)
        }
    }

    // -------------------------------------------------------------------------
    // Wake detection for hibernate/quick boot head units
    // -------------------------------------------------------------------------

    /**
     * Timestamp (elapsedRealtime) when the screen last turned off.
     * Used to measure how long the device was asleep and distinguish a normal
     * screen timeout from a hibernate wake (car ACC off → on).
     */
    private var screenOffTimestamp = 0L

    /**
     * Debounce: last time [onHibernateWake] actually ran.
     * Prevents double-triggering when both BootCompleteReceiver and this dynamic
     * receiver fire for the same wake event.
     */
    private var lastWakeHandledTimestamp = 0L

    /**
     * Runtime-registered receiver for system wake/boot/power/screen events.
     *
     * On Chinese head units with Quick Boot (hibernate/resume), standard broadcasts
     * like BOOT_COMPLETED and USB_DEVICE_ATTACHED often don't fire after waking.
     * This receiver serves two purposes:
     *
     * 1. **Diagnostic logging:** Logs every received system event with the
     *    "WakeDetect:" prefix so users can export logs and we can see which
     *    broadcasts their specific head unit sends (or doesn't send) on wake.
     *
     * 2. **Universal wake detection:** Uses ACTION_SCREEN_ON (which fires on ALL
     *    devices after hibernate) combined with screen-off duration tracking to
     *    detect hibernate wakes and trigger auto-start — regardless of which OEM
     *    boot/ACC intents the device sends.
     *
     * ACTION_SCREEN_ON can only be received by dynamically registered receivers,
     * not manifest-declared ones — that's why the manifest-based BootCompleteReceiver
     * can't catch it and we need this service-based approach.
     */
    private val wakeDetectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffTimestamp = SystemClock.elapsedRealtime()
                    AppLog.i("WakeDetect: SCREEN_OFF")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val now = SystemClock.elapsedRealtime()
                    val offDuration = if (screenOffTimestamp > 0) now - screenOffTimestamp else -1L
                    val offSec = if (offDuration >= 0) offDuration / 1000 else -1L
                    screenOffTimestamp = 0

                    AppLog.i("WakeDetect: SCREEN_ON (screen was off for ${offSec}s)")

                    val settings = App.provide(this@AapService).settings

                    // "Start on screen on" — triggers on every SCREEN_ON, designed for
                    // head units that never truly power off (quick boot / always-on).
                    if (settings.autoStartOnScreenOn) {
                        AppLog.i("WakeDetect: start-on-screen-on enabled, triggering auto-start")
                        onScreenOnAutoStart()
                    } else if (offDuration > HIBERNATE_WAKE_THRESHOLD_MS) {
                        // Hibernate wake detection — only for longer sleeps
                        AppLog.i("WakeDetect: hibernate wake detected (off for ${offSec}s > ${HIBERNATE_WAKE_THRESHOLD_MS / 1000}s threshold)")
                        onHibernateWake("SCREEN_ON after ${offSec}s sleep")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    AppLog.i("WakeDetect: USER_PRESENT")
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    AppLog.i("WakeDetect: POWER_CONNECTED")
                    // On some head units, power connected = ACC on = car started.
                    // Only check USB (don't launch UI) since this could also be a
                    // charger being plugged in on a phone/tablet.
                    onPossibleWake("POWER_CONNECTED")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    AppLog.i("WakeDetect: POWER_DISCONNECTED")
                }
                Intent.ACTION_SHUTDOWN -> {
                    AppLog.i("WakeDetect: SHUTDOWN (system shutting down, not hibernating)")
                    maybeTearDownBeforeLinkGoes(LinkLossTrigger.DEVICE_SHUTDOWN) { goAsync() }
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    if (state == WifiManager.WIFI_STATE_DISABLING) {
                        maybeTearDownBeforeLinkGoes(LinkLossTrigger.WIFI_STATION_DISABLING) { goAsync() }
                    }
                }
                else -> {
                    // OEM boot/ACC/wake intents — log with extras for diagnostics
                    AppLog.i("WakeDetect: $action")
                    val extras = intent.extras
                    if (extras != null && !extras.isEmpty) {
                        val extrasStr = extras.keySet().joinToString { "$it=${extras.get(it)}" }
                        AppLog.i("WakeDetect: extras: $extrasStr")
                    }
                    // Any OEM boot/ACC intent received dynamically = definite wake
                    onHibernateWake(action)
                }
            }
        }
    }

    /**
     * Called when we've confidently detected a hibernate wake (screen was off for
     * a long time, or an OEM boot/ACC intent was received by the dynamic receiver).
     */
    private fun onHibernateWake(trigger: String) {
        // Debounce: don't re-trigger within 10 seconds (covers BootCompleteReceiver + this)
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 10_000) {
            AppLog.i("WakeDetect: wake already handled ${(now - lastWakeHandledTimestamp) / 1000}s ago, skipping ($trigger)")
            return
        }
        lastWakeHandledTimestamp = now

        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            usbLauncherManager.isSwitchingToProjection()) {
            AppLog.i("WakeDetect: already connected/connecting, skipping ($trigger)")
            return
        }

        val settings = App.provide(this).settings

        if (settings.autoStartOnBoot) {
            AppLog.i("WakeDetect: launching UI (trigger=$trigger)")
            launchMainActivityOnBoot()
        }

        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices (trigger=$trigger)")
            usbLauncherManager.checkAlreadyConnected(force = true)
        }
    }

    /**
     * Called on events that MIGHT indicate a wake (e.g. POWER_CONNECTED) but aren't
     * conclusive alone. Only checks USB — does not launch the UI.
     */
    private fun onPossibleWake(trigger: String) {
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            usbLauncherManager.isSwitchingToProjection()) return

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: possible wake, checking USB (trigger=$trigger)")
            usbLauncherManager.checkAlreadyConnected(force = true)
        }
    }

    /**
     * Closes an active session while the link it rides still works.
     *
     * Android Auto's head unit server is wedged permanently by a peer that vanishes without
     * closing, and only a restart on the phone clears it. A session that closes properly does not
     * do that. The system warns us before some link losses and not others; this takes the ones it
     * does warn about.
     *
     * [pendingResult] keeps the broadcast alive while the teardown runs, because it does socket
     * work and the caller is a receiver on the main thread. It is always finished.
     */
    private fun maybeTearDownBeforeLinkGoes(
        trigger: LinkLossTrigger,
        pendingResult: () -> BroadcastReceiver.PendingResult
    ) {
        if (!commManager.isConnected) return
        // Not `?: return`. A wired session quiesces the wireless stack and leaves no active
        // launcher, and a shutdown arriving in that window still has a session to close in an
        // orderly way - which is the whole reason DEVICE_SHUTDOWN applies to USB as well. The
        // policy takes the null and answers for it.
        val launcher = wifiLauncherManager.active

        if (!LinkLossTeardownPolicy.shouldTearDown(
                trigger,
                launcher,
                // [BUG_FIX] Ask the session, not the settings. wifiConnectionMode is stored and
                // says nothing about what is running: a USB drive with a WiFi mode selected was
                // being disconnected by the user switching WiFi off, which the session never
                // rode in the first place.
                sessionIsWireless = commManager.isWirelessSession
            )
        ) {
            AppLog.i("AapService: $trigger, but this session does not ride that link; leaving it alone")
            return
        }

        // Only the WiFi trigger: a shutdown takes the whole device with it, so what the discovery
        // loop does in the two seconds it has left does not matter.
        if (trigger == LinkLossTrigger.WIFI_STATION_DISABLING) discoveryDormantAfterWifiLoss = true

        val pending = pendingResult()
        val startedAt = SystemClock.elapsedRealtime()
        AppLog.i(
            "AapService: $trigger with a live session — closing it now, while the link still " +
                "works. A session that just vanishes leaves the phone's head unit server holding a " +
                "peer that never came back, and only restarting it by hand clears that."
        )
        Thread {
            try {
                commManager.disconnectForLinkLoss(LINK_LOSS_TEARDOWN_BUDGET_MS)
                AppLog.i(
                    "AapService: link-loss teardown finished in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            } catch (e: Exception) {
                AppLog.e("AapService: link-loss teardown failed", e)
            } finally {
                try { pending.finish() } catch (e: Exception) {}
            }
        }.apply { name = "AapService-LinkLossTeardown"; start() }
    }

    /**
     * Called on every SCREEN_ON when "Start on screen on" is enabled.
     * Designed for head units that never truly power off — screen on = car turned on.
     *
     * If the connection is still active (e.g. brief screen toggle), returns to the
     * projection activity. Otherwise launches the main UI and checks USB.
     */
    private fun onScreenOnAutoStart() {
        // Debounce: don't re-trigger within 5 seconds
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 5_000) {
            AppLog.i("WakeDetect: screen-on auto-start already handled recently, skipping")
            return
        }
        lastWakeHandledTimestamp = now

        // Acquire wake lock to resist power saving cleanup on Quick Boot devices
        acquireBootWakeLock()

        if (commManager.isConnected) {
            // Connection still alive — return to projection screen
            if (App.isPiPActive) {
                AppLog.i("WakeDetect: connection active, but PiP is active. Skipping return to full screen.")
                return
            }
            AppLog.i("WakeDetect: connection active, returning to projection")
            try {
                val projectionIntent = AapProjectionActivity.intent(this).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(projectionIntent)
            } catch (e: Exception) {
                AppLog.e("WakeDetect: failed to launch projection: ${e.message}")
            }
            return
        }

        if (commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            usbLauncherManager.isSwitchingToProjection()) {
            AppLog.i("WakeDetect: already connecting, skipping screen-on auto-start")
            return
        }

        // Not connected — launch UI (which triggers auto-connect via HomeFragment)
        AppLog.i("WakeDetect: launching UI on screen on")
        launchMainActivityOnBoot()

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices on screen on")
            usbLauncherManager.checkAlreadyConnected(force = true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Put the head unit's own Bluetooth address in Settings the first time, if it can be read.
     *
     * The service discovery response omits the Bluetooth service entirely when this is blank, so
     * the phone is never told where to connect hands-free - and Android Auto keeps phone calls on
     * the phone until that link exists. The field was typed by hand, so most installs announce
     * nothing, while BluetoothHelper has been able to resolve the real address all along and used
     * it only for description strings.
     *
     * Never overwrites what the user typed: a hand-entered address is usually there because the
     * detected one was wrong.
     */
    private fun fillBluetoothAddressIfUnset() {
        val detected = try {
            BluetoothHelper.getBluetoothMacAddress(this)
        } catch (e: Exception) {
            AppLog.w("AapService: could not read this device's Bluetooth address: ${e.message}")
            null
        }
        val seeded = BluetoothAddressSeedPolicy.seed(settings.bluetoothAddress, detected)
        if (seeded.isNotEmpty() && seeded != settings.bluetoothAddress) {
            settings.bluetoothAddress = seeded
            AppLog.i("AapService: filled in this device's Bluetooth address ($seeded) so the " +
                "Bluetooth service can be announced; phone calls need it")
        }
    }

    /**
     * Re-claims the foreground types with the microphone added, for as long as capture is open.
     *
     * The microphone type is while-in-use, so it cannot be claimed at service start: a background
     * start is refused even with RECORD_AUDIO granted. Here the projection is on screen and the app
     * is eligible. Returns whether the claim succeeded, so a refusal declines the phone's request
     * rather than losing the service.
     */
    private fun promoteForMicrophone(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        val mask = ForegroundServiceTypePolicy.withMicrophone(
            sdkInt = Build.VERSION.SDK_INT,
            recordAudioGranted = PermissionChecker.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED,
            headUnitMicEnabled = settings.useHeadUnitMicrophone)

        // The mask can come back without the microphone type when the permission or the setting
        // says no. Capture has already checked both by this point, so say which happened rather
        // than claiming something that did not.
        val claimed = mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0

        return try {
            startForeground(1, createNotification(), mask)
            if (claimed) {
                AppLog.i("AapService: claimed the microphone foreground-service type for this capture")
            } else {
                AppLog.i("AapService: the microphone foreground-service type was not asked for; " +
                    "the permission or the setting says no")
            }
            true
        } catch (e: Exception) {
            AppLog.e("AapService: could not claim the microphone foreground-service type " +
                "(${e.message}); declining the phone's request rather than capturing without it", e)
            false
        }
    }

    /** Drops the microphone type again once capture is closed, so it is held only while it is true. */
    private fun demoteAfterMicrophone() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            startForeground(1, createNotification(),
                ForegroundServiceTypePolicy.baseTypeMask(Build.VERSION.SDK_INT))
        } catch (e: Exception) {
            // Nothing to do about it and nothing depends on it: the service stays foreground with
            // the wider mask, which is the state it was already in a moment ago.
            AppLog.w("AapService: could not drop the microphone foreground-service type: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")
        instance = this

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ForegroundServiceTypePolicy.baseTypeMask(Build.VERSION.SDK_INT))
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onCreate: ${e.message}", e)
            stopSelf()
            return
        }
        fillBluetoothAddressIfUnset()
        // A force-stop or a crash runs no teardown, so a station stand-down can outlive the process
        // that made it. Putting the network back here is what keeps that from stranding the unit off
        // its own WiFi. No-op unless one is standing.
        StationStandDown.restore(this)
        setupCarMode()
        setupNightMode()
        observeConnectionState()
        registerReceivers()

        MicRecorder.foregroundClaim = object : MicRecorder.ForegroundMicrophoneClaim {
            override fun claim() = promoteForMicrophone()
            override fun release() = demoteAfterMicrophone()
        }

        // Handle immediate WiFi auto-start check (e.g. if already connected on boot/wake)
        WifiAutoStartReceiver.checkAndStart(this)

        // Initialize MediaSession early and set it active immediately.
        // This ensures media button routing works even BEFORE an AA connection,
        // which is critical for keymap configuration and early button presses.
        if (mediaSession == null) {
            setupMediaSession()
        }
        safeMediaSessionCall { it.isActive = true }
        updateMediaSessionState(false) // Set initial PlaybackState so system knows our actions

        commManager.onAaMediaMetadata = { meta -> onAaMediaMetadataFromPhone(meta) }
        commManager.onAaPlaybackStatus = { status -> onAaPlaybackStatusFromPhone(status) }
        settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE).also { prefs ->
            prefs.registerOnSharedPreferenceChangeListener(settingsPreferenceListener)
        }

        AppLog.init(settings, this)
        syncLogBackendState()


        // Decided here as well as inside initWifiMode() so a paused start skips the wait-for-WiFi
        // machinery entirely rather than setting it up and being turned away at the end of it.
        if (applyBootLoopGuard()) {
            AppLog.w("AapService: Wireless bring-up paused by the boot-loop guard. USB and the rest of the app are unaffected.")
        } else {
            initWifiModeWithOptionalWait()
        }
        scheduleBootLoopStrikeClear()
        usbLauncherManager.checkAlreadyConnected()
        registerNetworkMonitor()
    }

    /** Enables Android Automotive UI mode so the system uses car-optimised layouts. */
    private fun setupCarMode() {
        try {
            val mgr = getSystemService(UI_MODE_SERVICE) as? UiModeManager
            if (mgr != null) {
                uiModeManager = mgr
                mgr.enableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Failed to enable car mode: ${e.message}")
        }
    }

    /** Initialises [NightModeManager] and forwards night-mode changes to Android Auto via AAP. */
    private fun setupNightMode() {
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            commManager.send(NightModeEvent(isNight))
            // Also notify local components (for AA monochrome filter)
            val intent = Intent(ACTION_NIGHT_MODE_CHANGED).apply {
                setPackage(packageName)
                putExtra("isNight", isNight)
            }
            sendBroadcast(intent)
        }
    }

    /**
     * Single observer for all [CommManager.ConnectionState] transitions.
     *
     * Uses [hasEverConnected] to skip the initial [ConnectionState.Disconnected] emission
     * from StateFlow replay, avoiding a spurious disconnect on startup.
     */
    private fun observeConnectionState() {
        serviceScope.launch {
            commManager.connectionState.collect { state ->
                when (state) {
                    is CommManager.ConnectionState.Connected -> {
                        sessionConnectedAt = SystemClock.elapsedRealtime()
                        onConnected()
                    }
                    is CommManager.ConnectionState.HandshakeComplete -> {
                        launchAapProjectionActivity()
                    }
                    is CommManager.ConnectionState.TransportStarted -> {
                        hasEverConnected = true
                        usbLauncherManager.projectionHandshakeFailures = 0
                        sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
                            setPackage(packageName)
                        })
                        maybeAutoResumePlaybackOnReconnect()
                    }
                    is CommManager.ConnectionState.Error -> {
                        // Nothing may be counted here, and nothing new may be hung off this branch.
                        // connectionState is a MutableStateFlow, so collection is conflated, and
                        // startHandshake() calls disconnect() with no suspension point after
                        // emitting Error — the value is already Disconnected by the time any
                        // collector resumes, so this branch does not run while the Disconnected one
                        // below runs normally. Anything that has to count failures counts them
                        // where they happen; the silent-peer streak lives in CommManager for that
                        // reason.
                        if (state.message.contains("Handshake failed")) {
                            usbLauncherManager.onHandshakeFailed()
                        }
                    }
                    is CommManager.ConnectionState.Disconnected -> {
                        if (hasEverConnected) onDisconnected(state)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun maybeAutoResumePlaybackOnReconnect() {
        val settings = App.provide(this).settings
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceDisconnect = now - lastDisconnectTimestampMs
        val wasPlaying = wasPlayingBeforeDisconnect
        wasPlayingBeforeDisconnect = false // Consume once so it doesn't fire again on subsequent events

        val shouldResume = AutoResumePlaybackPolicy.shouldResume(
            enabled = settings.autoResumePlaybackOnReconnect,
            wasPlayingBeforeDisconnect = wasPlaying,
            elapsedSinceDisconnectMs = elapsedSinceDisconnect
        )

        if (shouldResume) {
            AppLog.i("AapService: Auto-resuming playback on reconnect (${elapsedSinceDisconnect}ms since disconnect)")
            autoResumePlaybackJob?.cancel()
            autoResumePlaybackJob = serviceScope.launch {
                try {
                    // Allow Android Auto on the phone time to settle and attach its media player session
                    delay(1500L)
                    if (!isActive || isDestroying) return@launch

                    // Confirm that the connection is still alive and in TransportStarted before sending keys
                    if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                        AppLog.i("AapService: Dispatching auto-resume play key")
                        commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, true, null, "auto-resume")
                        commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, false, null, "auto-resume")
                    } else {
                        AppLog.w("AapService: Connection state changed before auto-resume could dispatch")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e("AapService: Error while auto-resuming playback: ${e.message}", e)
                }
            }
        } else {
            AppLog.d("AapService: Skipping auto-resume playback (wasPlaying=$wasPlaying, elapsed=${elapsedSinceDisconnect}ms)")
        }
    }

    /**
     * Performs the permanent audio focus request used for AA audio sink.
     *
     * This logic was previously executed in onCreate(); it has been moved here so
     * the caller can decide when to acquire focus (for example, immediately before
     * starting the AA handshake) to avoid stealing audio during autostart.
     *
     * The permanent AUDIOFOCUS_GAIN is only appropriate for Static Audio Focus mode,
     * where the phone must believe focus is always held. In the default (dynamic) mode
     * focus is instead acquired on demand via the AA protocol
     * (AapControl.audioFocusRequest -> AapAudio.requestFocusChange), so grabbing a
     * permanent gain here would needlessly evict other media (e.g. the car radio) the
     * moment the phone connects, before AA plays anything.
     *
     * Whether to take it at all is PlaybackFocusPolicy's call, the same as for the dynamic path:
     * on a head unit that is also the phone's Bluetooth A2DP sink, evicting the sink makes it
     * AVRCP-pause that same phone, so the session starts with the projected audio stopped.
     */
    private fun requestPermanentAudioFocus() {
        if (!settings.enableAudioSink) {
            AppLog.d("Audio Sink disabled - skipping permanent audio focus request.")
            return
        }
        if (!settings.staticAudioFocus) {
            AppLog.d("Static Audio Focus disabled - skipping permanent audio focus request; focus will be acquired on demand.")
            return
        }

        // One probe at connect is enough: the sink only pauses on a focus-loss *event*, so a
        // Bluetooth link that comes up later in the session never sees one.
        val mode = settings.playbackFocusMode
        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(this)
        if (!PlaybackFocusPolicy.shouldAcquirePermanent(
                mode = mode,
                staticAudioFocus = true,
                audioSinkEnabled = true,
                btMediaLinkActive = btMediaLinkActive)) {
            AppLog.i("AapService: Static Audio Focus - leaving system audio focus alone " +
                "(mode=$mode, bluetoothMedia=$btMediaLinkActive)")
            return
        }
        AppLog.i("AapService: Static Audio Focus - acquiring permanent system audio focus " +
            "(mode=$mode, bluetoothMedia=$btMediaLinkActive)")

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (permanentFocusRequest == null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    permanentFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener { focusChange ->
                            AppLog.d("AapService: Permanent audio focus changed: $focusChange")
                        }
                        .build()
                }
                val res = audioManager.requestAudioFocus(permanentFocusRequest!!)
                AppLog.d("AapService: requestPermanentAudioFocus: result=$res")
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    { focusChange -> AppLog.d("AapService: Permanent audio focus changed: $focusChange") },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                AppLog.d("AapService: requestPermanentAudioFocus (legacy): result=$res")
            }
        } catch (e: Exception) {
            AppLog.e("AapService: requestPermanentAudioFocus failed", e)
        }
    }

    /**
     * Releases any permanent audio focus previously requested by [requestPermanentAudioFocus].
     *
     * This is invoked on disconnect to return audio focus to the phone or other media
     * apps so that playback can resume normally. Supports both the modern
     * AudioFocusRequest API (API >= O) and the legacy abandonAudioFocus path.
     */
    private fun releasePermanentAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permanentFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    AppLog.d("AapService: abandoned permanent audio focus request")
                    permanentFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    audioManager.abandonAudioFocus(null)
                    AppLog.d("AapService: abandoned legacy audio focus (null listener)")
                } catch (e: Exception) {
                    // Some devices may not accept a null listener; ignore failures
                    AppLog.e("AapService: releasePermanentAudioFocus failed", e)
                }
            }
        } catch (e: Exception) {
            AppLog.e("AapService: Failed to abandon audio focus", e)
        }
    }

    /**
     * Called by [CommManager.ConnectionState.Connected] observer:
     * 1. Refreshes the foreground notification.
     * 2. Activates a [MediaSessionCompat] so media keys are routed to Android Auto.
     * 3. Starts the SSL handshake ([CommManager.startHandshake]) **in parallel** with
     *    launching [AapProjectionActivity], hiding multi-second handshake latency behind
     *    activity-inflation time.
     *
     * The inbound message loop ([CommManager.startReading]) is intentionally NOT started
     * here. It is deferred until [AapProjectionActivity] confirms its render surface is
     * ready (via [CommManager.ConnectionState.HandshakeComplete] observer), guaranteeing
     * that [VideoDecoder.setSurface] is always called before the first video frame arrives.
     */
    /**
     * What a Native AA setup QR would carry, or null while that mode is not running.
     *
     * The settings screen asks through [instance], because the network, the port and the Bluetooth
     * identity only exist once the launcher has resolved them, and a QR built from anything else
     * would write a record the phone keeps and cannot use.
     */
    fun projectionQrSnapshot(): ProjectionQrSnapshot? =
        (wifiLauncherManager.active as? WifiLauncherNative)?.handshakeManager?.projectionQrSnapshot()

    private fun onConnected() {
        usbLauncherManager.setSwitchingToProjection(false)
        updateNotification()
        // Whatever the transport, the wake-up loop has nothing left to do. Event driven rather
        // than left to the loop's own 15 s poll. A wireless session also marks the P2P group as
        // one that works, which is what keeps it up when the phone leaves later.
        (wifiLauncherManager.active as? WifiLauncherNative)?.onSessionEstablished()
        quiesceWirelessForWiredSession()
        if (UsbSessionQuiescePolicy.shouldAcquireWifiLock(commManager.isWirelessSession)) {
            acquireWifiLock()
        }
        // After the quiesce, which may have just stopped the P2P group: shouldStartForSession()
        // asks for a wireless Native AA session, so a wired one gets no VPN either way.
        maybeStartSessionDummyVpn()

        // Silent audio hack removed to prevent mixing/resampling stuttering issues

        // Register the comprehensive steering wheel key receiver
        App.provide(this).carKeysManager.registerReceivers(this)

        // Reactivate the existing MediaSession (created in onCreate, kept alive across disconnects)
        safeMediaSessionCall { it.isActive = true }
        updateMediaSessionState(true)
        applyPlaceholderMediaMetadata()

        // Link audio focus state changes to our MediaSession state
        commManager.onAudioFocusStateChanged = { isPlaying ->
            updateMediaSessionState(isPlaying)
        }

        // Acquire permanent audio focus just before starting the AA handshake so we
        // don't steal audio during service autostart but still obtain focus when a
        // real connection is beginning.
        requestPermanentAudioFocus()

        // Start GpsLocationService and NightModeManager sensor tracking
        AppLog.i("AapService: Starting GpsLocationService and NightModeManager since connection is established")
        startService(GpsLocationService.intent(this))
        nightModeManager?.start()

        serviceScope.launch { commManager.startHandshake() }
    }

    /**
     * @param allowNotificationFallback whether a full-screen-intent notification may stand in when
     *   there is no overlay permission. False on the call path, where it would compete with the
     *   call screen's own full-screen intent.
     */
    private fun launchAapProjectionActivity(allowNotificationFallback: Boolean = true) {
        if (App.isPiPActive) {
            AppLog.i("AapService: Skipping projection launch because PiP is active")
            return
        }

        val intent = AapProjectionActivity.intent(this).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        val canOverlay = AppPermissions.isOverlayGranted(this)
        when (ActivityLaunchPolicy.chooseLaunchStrategy(Build.VERSION.SDK_INT, canOverlay)) {
            ActivityLaunchPolicy.LaunchStrategy.DIRECT -> {
                try { startActivity(intent) }
                catch (e: Exception) { AppLog.e("Projection launch failed: ${e.message}") }
            }
            ActivityLaunchPolicy.LaunchStrategy.OVERLAY -> {
                if (!launchViaOverlayTrampoline(intent)) {
                    AppLog.w("Projection overlay trampoline failed, trying direct")
                    try { startActivity(intent) }
                    catch (e: Exception) { AppLog.e("Projection direct fallback failed: ${e.message}") }
                }
            }
            ActivityLaunchPolicy.LaunchStrategy.NOTIFICATION ->
                if (allowNotificationFallback) launchProjectionViaNotification(intent)
                else AppLog.w("AapService: No overlay permission, not raising the projection")
        }
    }

    private fun launchProjectionViaNotification(launchIntent: Intent) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val fullScreenPi = PendingIntent.getActivity(this, PROJECTION_LAUNCH_NOTIFICATION_ID, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.android_auto_starting))
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(PROJECTION_LAUNCH_NOTIFICATION_ID, notification)
        serviceScope.launch {
            delay(5000)
            nm.cancel(PROJECTION_LAUNCH_NOTIFICATION_ID)
        }
    }

    private fun setupMediaSession() {
        val mbr = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "HeadunitRevived", mbr, null).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java) }

                    if (keyEvent != null) {
                        val actionStr = if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"
                        AppLog.d("MediaButtonEvent: Received key ${keyEvent.keyCode} ($actionStr)")

                        // Only handle ACTION_DOWN to prevent double triggers from standard Android behavior.
                        // Physical double triggers are handled by CommManager.sendKey deduplication.
                        if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            AppLog.i("MediaButtonEvent: Processing key ${keyEvent.keyCode}")
                            // Send a complete click sequence (press + release) immediately
                            commManager.sendKey(keyEvent.keyCode, true, keyEvent.downTime, "mediasession")
                            commManager.sendKey(keyEvent.keyCode, false, keyEvent.downTime, "mediasession")
                            return true
                        }

                        // Consume ACTION_UP to prevent fallback
                        if (keyEvent.action == android.view.KeyEvent.ACTION_UP) {
                            return true
                        }
                    }

                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onPause() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PAUSE")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, false, null, "transport-control")
                }

                override fun onPlay() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PLAY")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, false, null, "transport-control")
                }

                override fun onSkipToNext() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_NEXT")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, false, null, "transport-control")
                }

                override fun onSkipToPrevious() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PREVIOUS")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, false, null, "transport-control")
                }

                override fun onStop() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_STOP")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP, true, null, "transport-control")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP, false, null, "transport-control")
                }
            })
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
        }
        applyPlaceholderMediaMetadata()
    }

    /**
     * Called by [CommManager.ConnectionState.Disconnected] observer:
     * 1. Refreshing the notification (unless we are already tearing down)
     * 2. Releasing the [MediaSessionCompat]
     * 3. Stopping audio/video decoders on the IO thread
     * 4. Scheduling a reconnect attempt if applicable (see [scheduleReconnectIfNeeded])
     */
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        sessionConnectedAt = 0L
        cancelAllBtAutoDisconnects()
        usbLauncherManager.setSwitchingToProjection(false)
        releaseWifiLock()
        stopDummyVpn(DummyVpnPolicy.Reason.SESSION_ENDED)

        // Stop GpsLocationService and NightModeManager sensor tracking
        AppLog.i("AapService: Stopping GpsLocationService and NightModeManager since connection is disconnected")
        stopService(GpsLocationService.intent(this))
        nightModeManager?.stop()

        // Release any permanent audio focus we may have requested when connected
        releasePermanentAudioFocus()
        App.provide(this).carKeysManager.unregisterReceivers()

        if (!isDestroying) updateNotification()
        autoResumePlaybackJob?.cancel()
        autoResumePlaybackJob = null
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = null
        lastAaMediaMetadata = null
        lastAaPlaybackPositionMs = 0L
        wasPlayingBeforeDisconnect = (lastAaPlaybackIsPlaying == true)
        lastDisconnectTimestampMs = SystemClock.elapsedRealtime()
        AppLog.i("AapService: Disconnected. wasPlayingBeforeDisconnect=$wasPlayingBeforeDisconnect")
        lastAaPlaybackIsPlaying = null
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        applyPlaceholderMediaMetadata()
        // Keep MediaSession alive across disconnect/reconnect cycles.
        // Only deactivate it — do NOT release it. A released session can no longer
        // receive media button events, which means the keymap stops working until
        // the next connection. OpenHU keeps its session alive the entire service lifetime.
        safeMediaSessionCall { it.isActive = false }
        updateMediaSessionState(false)
        serviceScope.launch(Dispatchers.IO) {
            val rearmedAfterWiredSession = rearmWirelessAfterWiredSession()

            // Read before anything stops the launcher, and remembered. WifiLauncherManager.stop()
            // nulls `active`, so both decisions further down used to be taken from a launcher that
            // was already gone and could only ever answer no.
            val ranWifiDirect = wifiLauncherManager.active?.hasWifiDirect() ?: false
            var wirelessTornDown = false

            val sessionEnd = SessionEndGroupPolicy.decide(
                activeModeIsNative = wifiLauncherManager.activeMode == WifiLauncherMode.NATIVE,
                isUserExit = state.isUserExit,
                rearmedAfterWiredSession = rearmedAfterWiredSession,
            )
            when (sessionEnd) {
                SessionEndGroupPolicy.Action.STOP -> {
                    // Awaited before the stop, not after it. stop() takes the P2P group down along
                    // with the rest of the launcher, and removing the interface while the
                    // ByeByeRequest and the socket close are still in flight is the race the
                    // ordering below was written to prevent. Read off `active` afterwards, that
                    // ordering could never apply to this mode at all.
                    commManager.awaitDisconnectComplete()
                    AppLog.i("AapService: Native AA user exit. Stopping active launcher.")
                    wifiLauncherManager.stop()
                    wirelessTornDown = true
                }
                SessionEndGroupPolicy.Action.KEEP_AND_REARM -> {
                    // Nothing here removes the network, so the profile the phone saved still names
                    // one that exists and its scan and association is all a reconnect costs. A
                    // recreate gives the group a new address on units that re-address, and the
                    // phone then spends seconds on the one it stored. Awaited all the same: the
                    // listeners are about to reopen, and a connection accepted while CommManager
                    // is still tearing down is one that gets refused.
                    commManager.awaitDisconnectComplete()
                    val launcher = wifiLauncherManager.active as? WifiLauncherNative
                    AppLog.i("AapService: Native AA session ended; keeping the ${launcher?.strategy ?: "wireless"} network up for the phone's return.")
                    launcher?.rearmAfterSessionEnd()
                }
                SessionEndGroupPolicy.Action.NONE -> {}
            }

            // [FIX] User-initiated disconnect while a WiFi-Direct-hosting mode was active: tear
            // down the P2P group so the phone's OS-level connection actually drops (previously
            // only the AA session ended — the WiFi Direct network stayed up, with nothing to
            // tell Wireless Helper the session had ended). Skipped on unexpected disconnects,
            // where the network is kept up on purpose so the phone's saved profile still matches;
            // see SessionEndGroupPolicy. Must await CommManager's async teardown first so we never
            // remove the P2P interface while the ByeByeRequest/socket-close is still in flight.
            if (rearmedAfterWiredSession) {
                // Nothing further to tear down; the re-arm owns the wireless stack from here.
            } else if (state.isUserExit && ranWifiDirect && !wirelessTornDown) {
                commManager.awaitDisconnectComplete()
                AppLog.i("AapService: CommManager teardown complete. Stopping WiFi Direct group.")
                wifiLauncherManager.sharedServices.wifiDirectManager?.stop()
                wifiLauncherManager.restartDiscovery()
            } else if (state.isUserExit) {
                // The same question for the routes that run on a soft AP instead of a P2P group.
                // Closing the socket does not make the phone leave the network: it stays
                // associated and Android Auto retries its wireless setup until it throttles
                // itself, so the access point has to go, and for the same reason as above only
                // once CommManager has finished. Unlike a P2P group the access point is usually
                // the user's own, and switching one back on is best effort, so it only comes down
                // when they have already handed the app that job.
                //
                // Restarted rather than left down. It has to disappear for the phone to be put off
                // it, but leaving it off charges the whole bring-up, measured at ~20s on a unit
                // that refuses setSoftApConfiguration(), to the next connection with the phone
                // waiting through it. Paying it here spends the same seconds while the user is
                // already walking away.
                //
                // Asked of the settings, not of the launcher: on the native route the launcher is
                // stopped a few lines above, and this decision is the exact complement of the
                // WiFi Direct one, so a route that answers no there has to be able to answer yes
                // here.
                val action = UserExitHotspotPolicy.onUserExit(
                    settings.wifiConnectionMode,
                    settings.helperConnectionStrategy,
                    settings.nativeApStrategy,
                    settings.autoEnableHotspot,
                    settings.hotspotTeardownProvenUnsafe
                )
                if (action != HotspotExitAction.NONE) {
                    commManager.awaitDisconnectComplete()
                    // Stop watching an access point nobody is connecting over, either way: this
                    // holds a system broadcast receiver and can still re-enable the hotspot on its
                    // own long after the user has finished with it. The native launcher does it at
                    // this sequence position for exactly this reason.
                    wifiLauncherManager.stop(WifiLauncherStopSequence.BEFORE_HOTSPOT_DISABLE)
                }
                when (action) {
                    HotspotExitAction.DISABLE -> {
                        AppLog.i("AapService: CommManager teardown complete. Restarting the hotspot so the phone leaves the network.")
                        if (!HotspotManager.restart(this@AapService)) {
                            // The one way to learn that this radio will not host an access point
                            // again once it has been taken down. Remembered so it costs the user
                            // one hotspot rather than one per session: from here on this device's
                            // access point is left alone and the phone is told, in the branch
                            // below, what that means.
                            settings.hotspotTeardownProvenUnsafe = true
                            AppLog.w(
                                "AapService: This device did not bring its access point back after " +
                                    "the app took it down, so it will not be taken down again. " +
                                    "Ending a session will leave the phone on the network from now " +
                                    "on - end it from the phone's own Android Auto notification if " +
                                    "that becomes a problem."
                            )
                        }
                    }
                    HotspotExitAction.WARN_LEFT_UP -> AppLog.w(
                        "AapService: Stopping the connection does not switch this device's hotspot " +
                            "off - either the app was not given charge of it, or this device has " +
                            "already shown it cannot switch one back on. So the phone stays " +
                            "associated and Android Auto will keep retrying its wireless setup; " +
                            "switch the hotspot off by hand, or end the session from the phone."
                    )
                    HotspotExitAction.NONE -> {}
                }
            }

            if (btAutoDisconnectStandDown) {
                btAutoDisconnectStandDown = false
                // Every mode, not only Native: Helper's user-exit branch above restarts discovery
                // and Auto's loop keeps running, which would re-advertise to the phone right after
                // its Bluetooth ended the session. Something has to ask for the stack back: the
                // Bluetooth auto-start, the Home screen, or a service restart.
                commManager.awaitDisconnectComplete()
                AppLog.i("AapService: Bluetooth auto-disconnect: keeping the wireless bring-up down until something re-arms it.")
                wifiLauncherManager.stop(WifiLauncherStopSequence.LAST)
                userExitedAA = true
            }

            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDisconnect")
        }

        // [FIX] Set cooldown flag for ALL user exits (not just USB).
        // The WirelessServer checks this flag to reject instant reconnections.
        if (state.isUserExit) {
            userExitedAA = true
            userExitCooldownUntil = android.os.SystemClock.elapsedRealtime() + USER_EXIT_COOLDOWN_MS
            AppLog.i("AapService: User exit cooldown active for ${USER_EXIT_COOLDOWN_MS}ms")
        }

        scheduleReconnectIfNeeded(state)
    }

    /**
     * Schedules a reconnect attempt 2 seconds after an unexpected disconnect:
     * - **Server mode** ([wirelessServer] != null): always restarts the discovery loop.
     * - **Auto WiFi mode** (mode == 1): triggers a one-shot scan on unclean disconnect only.
     *
     * [CommManager.ConnectionState.Disconnected.isClean] is `true` only when the phone
     * explicitly sends a `ByeByeRequest`. All other causes (USB detach, read error, explicit
     * disconnect) produce `isClean = false`.
     */
    private fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) {
        if (selfLauncherManager.isActive) {
            // A launcher's own failed dial is not a session ending, and stopping the wireless
            // launcher there tore down a P2P group that was still coming up.
            val stopsWireless = SelfModeDisconnectPolicy.stopsWirelessLauncher(
                selfModeActive = true,
                launchInFlight = selfLauncherManager.isLaunchInFlight()
            )
            AppLog.i(
                "AapService: Self Mode disconnected. Not restarting" +
                    (if (stopsWireless) "" else "; the launch is still in flight, so the wireless launcher stays") + "."
            )
            selfLauncherManager.isActive = false
            // Beside isActive, so a disconnect that lands mid-launch cannot leave Self Mode
            // refusing every later request.
            selfLauncherManager.clearLaunchInFlight()
            if (stopsWireless) wifiLauncherManager.stop()
            return
        }

        val settings = App.provide(this).settings

        if (wifiLauncherManager.isActive) {
            // Skip reconnect for user-initiated exits — the user explicitly wants to stop.
            if (state.isUserExit) {
                AppLog.i("AapService: User exit with wirelessServer active. Not restarting discovery.")
                return
            }
            // Only where a discovery loop exists: Native stays armed across a session end now, and
            // the line would otherwise announce a restart that is a no-op there.
            if (wifiLauncherManager.active?.hasLocalDiscovery() == true) {
                AppLog.i("AapService: Disconnected. Restarting discovery loop in 2s...")
                serviceScope.launch {
                    delay(2000)
                    if (!commManager.isConnected)
                        wifiLauncherManager.restartDiscovery()
                }
            }
            return
        }

        val lastType = settings.lastConnectionType

        // USB auto-reconnect: try again after a delay to give dongles time to re-enumerate.
        // Skip if the user voluntarily exited AA — the dongle is likely still connected with
        // stale data, and reconnecting immediately just causes handshake failures. The next
        // USB attach event will re-trigger the flow cleanly.
        if (lastType == Settings.CONNECTION_TYPE_USB &&
            (settings.autoConnectLastSession || settings.autoConnectSingleUsbDevice)) {
            if (state.isUserExit && !(settings.autoStartOnUsb && settings.reopenOnReconnection)) {
                AppLog.i("AapService: USB disconnect after user Exit. Skipping auto-reconnect (waiting for dongle re-enumeration).")
                userExitedAA = true
                return
            }
            if (state.isUserExit && settings.autoStartOnUsb && settings.reopenOnReconnection) {
                AppLog.i("AapService: USB disconnect after user Exit with reopenOnReconnection enabled. Will reconnect on next USB attach.")
                return
            }
            AppLog.i("AapService: USB disconnect. Scheduling reconnect check in ${USB_RECONNECT_DELAY_MS}ms...")
            serviceScope.launch {
                delay(USB_RECONNECT_DELAY_MS)
                if (!commManager.isConnected) usbLauncherManager.checkAlreadyConnected(force = true)
            }
        }

        if (!state.isClean) {
            val mode = settings.wifiConnectionMode
            if (mode == WifiLauncherMode.AUTO && lastType != Settings.CONNECTION_TYPE_USB) {
                AppLog.i("AapService: Unclean WiFi disconnect in Auto Mode. Retrying discovery in 2s...")
                serviceScope.launch {
                    delay(2000)
                    if (!commManager.isConnected) wifiLauncherManager.startDiscovery(oneShot = true)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun registerReceivers() {
        usbLauncherManager.register()
        stationScanMonitor.start(this)
        ContextCompat.registerReceiver(
            this, nightModeUpdateReceiver,
            IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, sensorRefreshReceiver,
            IntentFilter(ACTION_REFRESH_SENSORS).apply { addAction(ACTION_RESTART_AUDIO) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, raiseProjectionReceiver,
            IntentFilter(ACTION_RAISE_PROJECTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Runtime-registered MEDIA_BUTTON receiver.
        // Unlike manifest-registered receivers, runtime receivers bypass the
        // Android 8+ implicit broadcast restriction. This is the primary mechanism
        // that makes steering wheel media buttons work on China headunits.
        ContextCompat.registerReceiver(
            this, mediaButtonReceiver,
            IntentFilter(Intent.ACTION_MEDIA_BUTTON),
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered runtime MEDIA_BUTTON receiver")

        // WiFi Auto-start: Dynamic registration for reliability on Android 8+
        wifiAutoStartReceiver = WifiAutoStartReceiver()
        ContextCompat.registerReceiver(
            this, wifiAutoStartReceiver,
            IntentFilter(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered dynamic WiFi Auto-start receiver")

        // Wake detection receiver: catches SCREEN_ON, SCREEN_OFF, POWER_CONNECTED,
        // and all known OEM boot/ACC intents. Enables hibernate wake detection on
        // Quick Boot head units where BOOT_COMPLETED never fires.
        val wakeFilter = IntentFilter().apply {
            // Screen events (only receivable by dynamic receivers on Android 8+)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            // Power events
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
            // Not a wake event: the warning that WiFi station mode is going away, which is the
            // only chance to close a session riding it before the interface does. See
            // LinkLossTeardownPolicy.
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            // Standard boot (dynamic duplicate — BootCompleteReceiver handles manifest side)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            // Quick boot variants
            addAction("android.intent.action.QUICKBOOT_POWERON")
            addAction("com.htc.intent.action.QUICKBOOT_POWERON")
            // MediaTek IPO (Instant Power On)
            addAction("com.mediatek.intent.action.QUICKBOOT_POWERON")
            addAction("com.mediatek.intent.action.BOOT_IPO")
            // FYT / GLSX head units (ACC ignition wake)
            addAction("com.fyt.boot.ACCON")
            addAction("com.glsx.boot.ACCON")
            addAction("android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT")
            // Microntek / MTCD / PX3 head units (ACC wake)
            addAction("com.cayboy.action.ACC_ON")
            addAction("com.carboy.action.ACC_ON")
        }
        ContextCompat.registerReceiver(
            this, wakeDetectReceiver,
            wakeFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered wake detection receiver (${wakeFilter.countActions()} actions)")

        // Registered whether or not a device is chosen: the list can change while the service
        // runs, and the handler returns on its first line when it is empty.
        ContextCompat.registerReceiver(
            this, btAutoDisconnectReceiver,
            IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered Bluetooth auto-disconnect receiver")
    }

    /**
     * Watches the links chosen under "Auto-disconnect on Bluetooth". Runtime-registered so it
     * cannot outlive the session, and on ACL because the selection is addresses, not profiles.
     */
    private val btAutoDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> true
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return
            }
            val watched = App.provide(this@AapService).settings.autoDisconnectBluetoothDeviceMacs
            if (watched.isEmpty()) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            }
            val mac = device?.address

            when (BtAutoDisconnectPolicy.onAclEvent(watched, mac, connected)) {
                BtAutoDisconnectArm.IGNORE -> {}
                BtAutoDisconnectArm.CANCEL -> {
                    if (btAutoDisconnectJobs.remove(mac)?.also { it.cancel() } != null) {
                        AppLog.i("AapService: Bluetooth auto-disconnect: $mac is back; the pending disconnect is cancelled.")
                    }
                }
                BtAutoDisconnectArm.ARM -> armBtAutoDisconnect(mac!!)
            }
        }
    }

    private fun isSessionUp(): Boolean =
        commManager.isConnected || commManager.connectionState.value is CommManager.ConnectionState.Connecting

    private fun armBtAutoDisconnect(mac: String) {
        if (!isSessionUp()) {
            // Deliberately not a stand-down of the wireless stack. Native AA's wake poke holds a
            // socket to the phone and closes it every cycle while it waits, which raises exactly
            // this event; standing down here would silence the poke while the phone is answering.
            AppLog.i("AapService: Bluetooth auto-disconnect: $mac went away, but nothing is projecting; leaving the connection stack alone.")
            return
        }
        val delayMs = BtAutoDisconnectPolicy.graceDelayMs(App.provide(this).settings.autoDisconnectBtDelaySeconds)
        btAutoDisconnectJobs.remove(mac)?.cancel()
        AppLog.i("AapService: Bluetooth auto-disconnect: $mac went away; ending the session in ${delayMs}ms unless it comes back.")
        btAutoDisconnectJobs[mac] = serviceScope.launch {
            delay(delayMs)
            btAutoDisconnectJobs.remove(mac)
            fireBtAutoDisconnect(mac)
        }
    }

    private fun fireBtAutoDisconnect(mac: String) {
        val sessionUp = isSessionUp()
        val ageMs = if (sessionConnectedAt == 0L) 0L else SystemClock.elapsedRealtime() - sessionConnectedAt
        // The device coming back cancels the job on this same thread, so a job that runs never
        // saw it return; the parameter exists so the rule is complete where it is tested.
        if (!BtAutoDisconnectPolicy.shouldEndSession(sessionUp, ageMs, deviceCameBack = false)) {
            AppLog.i("AapService: Bluetooth auto-disconnect: not ending the session for $mac (up=$sessionUp, age=${ageMs}ms).")
            return
        }
        AppLog.i("AapService: Bluetooth auto-disconnect: $mac stayed away; ending the session the way the Exit button does.")
        btAutoDisconnectStandDown = true
        commManager.disconnect(sendByeBye = true, isUserExit = true)
    }

    private fun cancelAllBtAutoDisconnects() {
        btAutoDisconnectJobs.values.forEach { it.cancel() }
        btAutoDisconnectJobs.clear()
    }

    private fun registerNetworkMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppLog.i("NetworkMonitor: Network available: $network")

                // [BUG_FIX] force start scan, now that we are connected — but do not stop the
                // scan already running to do it. stop() is cooperative, so the pair started a
                // second sweep beside the first, and two sweeps probing the head unit server's
                // port at once is how it ends up bound to a connection nobody owns.
                // startScan() is a no-op while a healthy scan is in flight, which is what this
                // wants: a scan is running, so the network is already being looked at.
                // onAvailable also fires repeatedly (per network, and again on re-validation),
                // hence the debounce.
                // Whatever else this network is, it ends the wait a WiFi teardown started. The
                // startScan() below is what actually revives the loop.
                if (discoveryDormantAfterWifiLoss) {
                    discoveryDormantAfterWifiLoss = false
                    AppLog.i("NetworkMonitor: network is back after a link-loss teardown; discovery resumes")
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNetworkAvailableKickMs < NETWORK_AVAILABLE_DEBOUNCE_MS) {
                    AppLog.d("NetworkMonitor: Ignoring repeat onAvailable within debounce window")
                    return
                }
                lastNetworkAvailableKickMs = now
                serviceScope.launch {
                    delay(500)

                    when (wifiLauncherManager.forceStartDiscoveryScan()) {
                        false -> {
                            // Folded into a sweep that was already running — which was started for
                            // the network we have just left. Do not cancel it; just do not make the
                            // next one wait ten seconds either.
                            rescanWithoutWaiting = true
                            AppLog.i("NetworkMonitor: a scan was already in flight; the next one will not wait")
                        }
                        // No discovery loop on this route at all, so there is nothing to hurry.
                        null -> AppLog.d("NetworkMonitor: no discovery loop to kick")
                        true -> {}
                    }
                }
            }
            override fun onLost(network: Network) {
                AppLog.w("NetworkMonitor: Network lost: $network")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                AppLog.d("NetworkMonitor: Capabilities changed: $network → $caps")
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, callback)
        AppLog.i("NetworkMonitor: Registered network change listener")
    }

    private fun unregisterNetworkMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        networkCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { }
            }
            networkCallback = null
        }
    }

    /**
     * Decides whether to call [initWifiMode] immediately or wait for WiFi connectivity.
     *
     * When "Wait for WiFi before WiFi Direct" is enabled AND WiFi connection mode is 2
     * (Wireless Helper), registers a [ConnectivityManager.NetworkCallback] filtered to
     * TRANSPORT_WIFI. [initWifiMode] fires as soon as WiFi connects, or after the
     * configured timeout — whichever comes first.
     *
     * When the setting is disabled, or the mode is not 2, [initWifiMode] runs immediately.
     */
    private fun initWifiModeWithOptionalWait() {
        val settings = App.provide(this).settings

        if (settings.wifiConnectionMode != WifiLauncherMode.HELPER || settings.helperConnectionStrategy != HelperStrategy.WIFI_DIRECT || !settings.waitForWifiBeforeWifiDirect) {
            wifiLauncherManager.setActiveFromSettings()
            return
        }

        wifiModeInitialized = false

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isWifiConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
        }

        if (isWifiConnected || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            if (isWifiConnected) AppLog.i("WifiWait: WiFi already connected, initializing immediately")
            else AppLog.i("WifiWait: Legacy device (API < 21), skipping wait.")

            wifiModeInitialized = true
            wifiLauncherManager.setActiveFromSettings()
            return
        }

        val timeoutSec = settings.waitForWifiTimeout.toLong()
        AppLog.i("WifiWait: Waiting up to ${timeoutSec}s for WiFi before initializing WiFi Direct...")

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLog.i("WifiWait: WiFi connected (network=$network)")
                    serviceScope.launch {
                        completeWifiWait("WiFi connected")
                    }
                }
            }
        } else null

        wifiReadyCallback = callback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && callback != null) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, callback)
        }

        wifiReadyTimeoutJob = serviceScope.launch {
            delay(timeoutSec * 1000)
            completeWifiWait("timeout (${timeoutSec}s)")
        }
    }

    private fun completeWifiWait(reason: String) {
        if (wifiModeInitialized || isDestroying) return
        wifiModeInitialized = true

        AppLog.i("WifiWait: Completing (reason=$reason)")

        wifiReadyTimeoutJob?.cancel()
        wifiReadyTimeoutJob = null

        wifiReadyCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
            }
            wifiReadyCallback = null
        }

        wifiLauncherManager.setActiveFromSettings()
    }

    /**
     * Whether this session's wireless teardown is ours to undo. Set by
     * [quiesceWirelessForWiredSession], cleared by [rearmWirelessAfterWiredSession], and read by
     * `WifiLauncherManager.setActive`, which refuses to arm the wireless stack while it is true and
     * a wired session is live.
     */
    @Volatile var wirelessQuiescedForWiredSession = false
        private set

    /**
     * Shut the wireless stack down for the duration of a USB session. See
     * [UsbSessionQuiescePolicy] for why any of it is running in the first place.
     */
    private fun quiesceWirelessForWiredSession() {
        if (!UsbSessionQuiescePolicy.shouldQuiesce(commManager.isWirelessSession)) return

        val settings = App.provide(this).settings
        val mode = settings.wifiConnectionMode
        val strategy = settings.helperConnectionStrategy

        AppLog.i(
            "AapService: USB session established while wireless mode $mode/$strategy was armed — " +
                "stopping the wireless stack for the duration of it"
        )
        wirelessQuiescedForWiredSession = true

        wifiLauncherManager.stop()
    }

    /**
     * Put back whatever [quiesceWirelessForWiredSession] took down. Runs on any end to the wired
     * session, user exit included: unplugging has to return the unit to its configured mode.
     */
    private fun rearmWirelessAfterWiredSession(): Boolean {
        val quiesced = wirelessQuiescedForWiredSession
        wirelessQuiescedForWiredSession = false
        val mode = App.provide(this).settings.wifiConnectionMode
        if (!UsbSessionQuiescePolicy.shouldRearmWireless(quiesced, mode != WifiLauncherMode.MANUAL)) return false

        AppLog.i("AapService: wired session ended — re-arming wireless mode $mode")
        serviceScope.launch {
            delay(1500) // Same settle the Native AA reconnect path allows the P2P hardware.
            wifiLauncherManager.setActiveFromSettings(force = true)
        }
        return true
    }

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HeadunitRevived:Connection")
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
            AppLog.i("WifiLock acquired (HIGH_PERF)")
        }
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            AppLog.i("WifiLock released")
        }
    }

    /**
     * Brings the dummy VPN up and records who it is for.
     *
     * [VpnControl.isPrepared] is a *query*, not a prompt: it is true once this app is the prepared
     * VPN app, which is the state the settings toggle left behind after its one consent dialog.
     * False means consent was never given, was revoked, or another VPN app has taken the slot, and
     * a Service can resolve none of those. On the Play Store flavor it is always false.
     */
    private fun startDummyVpn(owner: DummyVpnPolicy.Owner) {
        if (!VpnControl.isPrepared(this)) {
            AppLog.w(
                "AapService: the dummy VPN was wanted (owner=$owner) but this app is not the " +
                    "prepared VPN app - consent was never given, was withdrawn, or another VPN " +
                    "app holds the slot. Re-arm it in Settings > Advanced under Android Auto mode."
            )
            return
        }
        VpnControl.startVpn(this, excludeSelf = owner == DummyVpnPolicy.Owner.SESSION)
        dummyVpnOwner = owner
        AppLog.i(
            "AapService: dummy VPN requested (owner=$owner). While it is up, other apps on this " +
                "unit have no IPv4."
        )
    }

    /**
     * Takes the dummy VPN down, but only for a teardown that owns it - see [DummyVpnPolicy].
     */
    fun stopDummyVpn(reason: DummyVpnPolicy.Reason) {
        val owner = dummyVpnOwner
        if (!DummyVpnPolicy.shouldStop(owner, reason)) return
        AppLog.i("AapService: releasing the dummy VPN (owner=$owner, reason=$reason)")
        VpnControl.stopVpn(this)
        dummyVpnOwner = null
        selfLauncherManager.stopDummyVpnWatchdog()
    }

    /**
     * Brings the dummy VPN up for an ordinary Native AA session when the user asked for it.
     *
     * The mode test is not redundant with the setting: the toggle only renders inside the Native
     * AA block, so a user who turns it on and then switches connection mode keeps a preference
     * they can no longer see. Without this, that preference would put a blackholing tun on a USB
     * session.
     */
    private fun maybeStartSessionDummyVpn() {
        selfLauncherManager.stopDummyVpnWatchdog()

        val available = VpnControl.isVpnAvailable()
        val wanted = DummyVpnPolicy.shouldStartForSession(
            keepDuringSession = App.provide(this).settings.keepDummyVpnDuringSession,
            // The same value the settings list gates the toggle on, so what a user can see and
            // what runs cannot drift. activeWifiMode is deliberately not used: stopWirelessServer()
            // resets it to -1, and a session that outlives one of those would go unprotected.
            nativeWirelessSession = commManager.isWirelessSession &&
                App.provide(this).settings.wifiConnectionMode == WifiLauncherMode.NATIVE,
            currentOwner = dummyVpnOwner,
            selfMode = selfLauncherManager.isActive,
            vpnAvailable = available,
            // Short-circuited on purpose: the Play Store flavor must not reach a prepare() call
            // at all, and the stub would answer false anyway.
            alreadyPrepared = available && VpnControl.isPrepared(this),
        )
        if (wanted) startDummyVpn(DummyVpnPolicy.Owner.SESSION)
    }

    /**
     * Acquires a partial wake lock to resist MediaTek/Reglink background power
     * saving that force-stops third-party apps when ACC is off.
     * The wake lock has a 10-minute timeout as a safety net.
     */
    private fun acquireBootWakeLock() {
        if (bootWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        bootWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeadunitRevived::BootAutoStart"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout
        }
        AppLog.i("Boot WakeLock acquired (10min timeout)")

        // Log battery optimization status for diagnostics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val exempt = pm.isIgnoringBatteryOptimizations(packageName)
            AppLog.i("Battery optimization exempt: $exempt")
        }
    }

    private fun releaseBootWakeLock() {
        if (bootWakeLock?.isHeld == true) {
            bootWakeLock?.release()
            AppLog.i("Boot WakeLock released")
        }
        bootWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("AapService: onTaskRemoved — attempting restart")
        try {
            val restartIntent = Intent(this, AapService::class.java)
            ContextCompat.startForegroundService(this, restartIntent)
        } catch (e: Exception) {
            AppLog.e("AapService: failed to restart after task removal: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("WrongConstant")
    override fun onDestroy() {
        AppLog.i("AapService destroying... (wakeLock held=${bootWakeLock?.isHeld == true})")
        isDestroying = true
        // Nothing else clears it here, and the manager outlives the service instance.
        selfLauncherManager.isActive = false
        autoResumePlaybackJob?.cancel()
        autoResumePlaybackJob = null
        mediaMetadataDecodeJob?.cancel()
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        commManager.onAaMediaMetadata = null
        commManager.onAaPlaybackStatus = null
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(settingsPreferenceListener)
        settingsPrefs = null
        releaseBootWakeLock()

        wifiLauncherManager.stop(WifiLauncherStopSequence.BEFORE_HOTSPOT_DISABLE)
        // The access point is left up here. Taking it down serves one purpose, putting the phone
        // off the network, and onDisconnected already decides that through UserExitHotspotPolicy:
        // it restarts the access point rather than leaving it down, and remembers a device that
        // cannot bring one back. Switching it off again here undid that restart and stranded an
        // access point most unrooted units cannot switch on again, measured on a UNISOC unit where
        // every start path then failed.

        wifiReadyTimeoutJob?.cancel()
        wifiReadyTimeoutJob = null
        wifiReadyCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
            }
            wifiReadyCallback = null
        }

        releaseWifiLock()
        unregisterNetworkMonitor()
        stopForeground(true)
        wifiLauncherManager.stop(WifiLauncherStopSequence.LAST)
        stopDummyVpn(DummyVpnPolicy.Reason.SERVICE_DESTROYED)
        try {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
        } catch (e: Exception) {
            AppLog.e("Error releasing MediaSession: ${e.message}")
        }
        mediaSession = null
        // The claim outlives no service: a stale one would call startForeground on a dead instance.
        MicRecorder.foregroundClaim = null
        commManager.destroy()
        nightModeManager?.stop()
        stopService(GpsLocationService.intent(this))
        try {
            unregisterReceiver(nightModeUpdateReceiver)
            unregisterReceiver(sensorRefreshReceiver)
            unregisterReceiver(raiseProjectionReceiver)
        } catch (_: Exception) {}
        usbLauncherManager.unregister()
        try { unregisterReceiver(mediaButtonReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeDetectReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(btAutoDisconnectReceiver) } catch (_: Exception) {}
        cancelAllBtAutoDisconnects()
        btAutoDisconnectStandDown = false
        sessionConnectedAt = 0L
        stationScanMonitor.stop(this)
        try { App.provide(this).carKeysManager.unregisterReceivers() } catch (e: Exception) { AppLog.w("AapService: Error unregistering carKeysManager: ${e.message}") }
        try { wifiAutoStartReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try {
            if (::uiModeManager.isInitialized) {
                uiModeManager.disableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Error disabling car mode: ${e.message}")
        }
        try { selfLauncherManager.stop(wasConnected = commManager.isConnected) } catch (_: Exception) {}
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { LogExporter.stopCapture() } catch (_: Exception) {}
        super.onDestroy()
        instance = null
        if (killProcessOnDestroy) {
            AppLog.i("AapService: killProcessOnDestroy is true. Triggering System.exit(0).")
            System.exit(0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ForegroundServiceTypePolicy.baseTypeMask(Build.VERSION.SDK_INT))
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onStartCommand: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle stop before re-posting the notification to avoid a flash
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received. Broadcasting finish request to activities.")
            sendBroadcast(Intent("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES").apply {
                setPackage(packageName)
            })
            isDestroying = true
            if (commManager.isConnected) commManager.disconnect(sendByeBye = true)
            selfLauncherManager.stop(wasConnected = false)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Route MEDIA_BUTTON intents to the active MediaSession.
        safeMediaSessionCall { MediaButtonReceiver.handleIntent(it, intent) }
        // Launch the UI after boot.
        // Direct startActivity() is silently blocked on MIUI/HyperOS even from
        // a foreground service. We use an overlay window trampoline: creating a
        // zero-size overlay gives the app a "visible" context that bypasses OEM
        // background activity start restrictions. Falls back to full-screen
        // intent notification if overlay permission is not granted.
        // Acquire a partial wake lock on any boot/screen-on start to resist
        // aggressive power saving on MediaTek/Reglink head units that force-stop
        // third-party apps when ACC is off after a Quick Boot reboot.
        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true ||
            intent?.action == ACTION_CHECK_USB) {
            acquireBootWakeLock()
        }

        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true) {
            // Mark wake as handled so the dynamic wakeDetectReceiver doesn't double-trigger
            lastWakeHandledTimestamp = SystemClock.elapsedRealtime()
            launchMainActivityOnBoot()
        }

        when (intent?.action) {
            ACTION_START_SELF_MODE       -> selfLauncherManager.start()
            ACTION_STOP_SELF_MODE        -> selfLauncherManager.stop(wasConnected = commManager.isConnected)
            ACTION_START_WIRELESS        -> {
                // Asked for from the UI, so the user is present: release the boot-loop pause
                // rather than silently ignoring them.
                Settings.clearBootLoopState(this)
                wifiLauncherManager.setActiveFromSettings()
            }
            ACTION_START_WIRELESS_SCAN   -> {
                val settings = App.provide(this).settings
                val mode = settings.wifiConnectionMode

                AppLog.i("AapService: Force-starting WIFI-Scan from UI")

                // [FIX] Reset exit flags on manual scan start
                userExitedAA = false
                userExitCooldownUntil = 0L
                Settings.clearBootLoopState(this)
                wifiLauncherManager.setActiveFromSettings(force = true, noInfoToasts = false)

                if (mode == WifiLauncherMode.AUTO)
                    wifiLauncherManager.startDiscovery(oneShot = true)
            }
            ACTION_STOP_WIRELESS         -> wifiLauncherManager.stop()
            ACTION_NATIVE_AA_POKE        -> {
                val mac = intent?.getStringExtra(EXTRA_MAC)
                if (mac != null) {
                    AppLog.i("AapService: Received manual Native-AA poke request for $mac")
                    // [FIX] Reset exit flags so the subsequent connection is accepted
                    userExitedAA = false
                    userExitCooldownUntil = 0L

                    val settings = App.provide(this).settings
                    val activeLauncher = wifiLauncherManager.active

                    if (settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
                        // Asked before the re-init, not after it: setActiveFromSettings reads the
                        // stored mode, so on a Helper or Auto unit this button used to tear that
                        // mode down and build it again before finding there was nothing to poke.
                        AppLog.i("AapService: manual Native-AA poke ignored: the wireless mode is not Native AA.")
                        ToastUtils.showToast(this, "Native AA mode not active.")
                    } else {
                        if (wifiLauncherManager.activeMode != WifiLauncherMode.NATIVE) {
                            AppLog.i("AapService: Initializing Native AA mode before poke...")
                            wifiLauncherManager.setActiveFromSettings(force = true)
                        } else if (activeLauncher is WifiLauncherNative && activeLauncher.handshakeManager?.isActive() != true) {
                            // A completed handoff closes the AA listeners while leaving the manager
                            // running, and start() returns immediately on isRunning, so calling it here
                            // reopened nothing: the poke woke the phone, the phone opened RFCOMM, and
                            // nothing was listening. The listeners are reopened directly rather than by
                            // rebuilding the mode and its network under the phone.
                            AppLog.i("AapService: Native AA listeners are closed, reopening them before the poke.")
                            activeLauncher.rearmAfterSessionEnd()
                        } else {
                            AppLog.d("AapService: Already in Native AA mode, skipping re-init.")
                        }

                        // Kept on the re-init branch as well as the armed one: it is the only thing
                        // that clears the handshake backoff, and the button has to mean one thing.
                        // Its pre-flight refresh now waits on the create claimed above rather than
                        // starting a second one.
                        val launcher = wifiLauncherManager.active

                        if (launcher is WifiLauncherNative) {
                            launcher.handshakeManager?.manualPoke(mac)
                        } else {
                            ToastUtils.showToast(this, "Native AA mode not active.")
                        }
                    }
                }
            }
            ACTION_BT_AUTO_START          -> {
                // A Native mode stopped by a user exit stays dead unless something re-arms it, but
                // a re-arm recreates the network and costs the phone its saved profile. So Native is
                // forced only when it cannot accept at all. The rule is on BtAutoStartRearmPolicy;
                // Self Mode's half runs in MainActivity, which the receiver brings forward too.
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                val launcher = wifiLauncherManager.active as? WifiLauncherNative
                val attemptInFlight = launcher?.handshakeManager?.isAttemptInFlight()

                val networkComingUp = launcher?.networkComingUp()

                val actions = BtAutoStartRearmPolicy.actionsFor(
                    mode = settings.wifiConnectionMode,
                    wirelessSelected = settings.showsWifi(),
                    sessionUp = sessionUp,
                    wirelessArmed = wifiLauncherManager.isActive,
                    handshakeActive = launcher?.handshakeManager?.isActive(),
                    attemptInFlight = attemptInFlight,
                    groupUp = launcher?.hasLiveNetwork(),
                    networkComingUp = networkComingUp
                )
                if (!actions.doesNothing) {
                    AppLog.i("AapService: Bluetooth auto-start: $actions")
                }
                if (actions.clearUserExit) {
                    userExitedAA = false
                    userExitCooldownUntil = 0L
                }
                if (actions.forceRearmWireless) {
                    wifiLauncherManager.setActiveFromSettings(force = true)
                } else if (networkComingUp == true) {
                    // Nothing to do and nothing safe to do: the network this arrival would rebuild
                    // is the one already being asked for, and tearing it down starts a second
                    // create underneath the first.
                    AppLog.i("AapService: Bluetooth auto-start: the Native AA group is still being created, so it is left to answer.")
                } else if (actions.armWirelessIfIdle) {
                    wifiLauncherManager.setActiveFromSettings()
                } else if (settings.wifiConnectionMode == WifiLauncherMode.NATIVE && !sessionUp && attemptInFlight != true) {
                    // Armed with a live group, so the phone dials RFCOMM itself. Re-read the
                    // credentials rather than remake the group; that also catches a group that died.
                    // Skipped while an attempt is in flight, because that ACL is our own poke's.
                    AppLog.i("AapService: Bluetooth auto-start: Native AA is armed with a live group, leaving it alone.")
                    launcher?.triggerWifiDirectRefresh()
                }
            }
            ACTION_NEARBY_CONNECT         -> {
                val endpointId = intent?.getStringExtra(EXTRA_ENDPOINT_ID)
                if (endpointId != null) {
                    AppLog.i("AapService: Connecting to Nearby endpoint $endpointId")

                    // The endpoint id came from the advertiser that is running. Replacing the
                    // launcher first stopped it - and stopDiscovery() with it - then handed the id
                    // to a client that had discovered nothing, so requestConnection() was asking
                    // about an endpoint it had never seen. It also switched a user in Auto or
                    // Native mode over to Helper/Nearby without being asked.
                    //
                    // Only when nothing is running is a launcher built, and then the endpoint is
                    // stale by the same argument, so the fresh scan is all that can be offered.
                    val active = wifiLauncherManager.active as? WifiLauncherHelper
                    if (active != null && active.strategy == HelperStrategy.NEARBY_DEVICES) {
                        active.nearbyManager?.connectToEndpoint(endpointId)
                    } else {
                        AppLog.i("AapService: Nearby is not the running transport — arming it before connecting.")
                        val launcher = WifiLauncherHelper(wifiLauncherManager, HelperStrategy.NEARBY_DEVICES)
                        wifiLauncherManager.setActive(launcher, force = true)
                        launcher.nearbyManager?.connectToEndpoint(endpointId)
                    }
                }
            }
            ACTION_DISCONNECT            -> {
                AppLog.i("Disconnect action received.")
                // disconnect() has its own early-return when already Disconnected,
                // and unlike the previous isConnected guard it also covers the
                // Connecting state, so the UI cancel paths work before handshake
                // completes.
                commManager.disconnect()
            }
            ACTION_CONNECT_SOCKET        -> {
                // Caller already invoked commManager.connect(socket); the connectionState
                // observer in observeConnectionState() handles the rest — nothing to do here.
            }
            ACTION_CHECK_USB             -> usbLauncherManager.checkAlreadyConnected(force = true)
            else                         -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    usbLauncherManager.checkAlreadyConnected()
                }
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AapService::class.java).apply { action = ACTION_STOP_SERVICE },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Tap the notification to go back to the projection screen (if connected) or home
        val (notificationIntent, requestCode) = if (commManager.isConnected) {
            AapProjectionActivity.intent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } to 100
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } to 101
        }

        val contentText = if (commManager.isConnected)
            getString(R.string.notification_projection_active)
        else
            getString(R.string.notification_service_running)

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Open Headunit")
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(
                this, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    /**
     * Launch MainActivity after boot using a cascading fallback chain designed
     * to work across stock AOSP head units, Xiaomi MIUI/HyperOS, Samsung One UI,
     * Huawei EMUI, OPPO ColorOS, and other OEM ROMs.
     *
     * Strategy order:
     * 1. Direct startActivity (Android < 10, or any device without background
     *    activity restrictions — works on most head units running AOSP)
     * 2. Overlay window trampoline (Android 10+): creates a zero-size invisible
     *    overlay giving the app a "visible" context. Bypasses MIUI, EMUI, ColorOS
     *    background start restrictions. Requires SYSTEM_ALERT_WINDOW.
     * 3. Full-screen intent notification (Android 10+): high-priority notification
     *    with fullScreenIntent. Works on stock Android 10-13 and Samsung. On
     *    Android 14+ needs USE_FULL_SCREEN_INTENT permission.
     * 4. Tap-to-open notification (last resort): user taps notification to open.
     */
    /**
     * Launches MainActivity when reopenOnReconnection is enabled and no activity is currently
     * visible. Uses the same overlay trampoline technique as boot auto-start to bypass OEM
     * background activity start restrictions.
     */
    fun launchMainActivityIfNeeded(source: String) {
        val settings = App.provide(this).settings
        if (!settings.autoStartOnUsb || !settings.reopenOnReconnection) return

        AppLog.i("Reopen on reconnection: launching MainActivity ($source)")
        launchMainActivityOnBoot()
    }

    private fun launchMainActivityOnBoot() {
        // Android < 10: no background activity start restrictions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLog.i("Boot auto-start: launching directly (API ${Build.VERSION.SDK_INT} < 29)")
            launchDirectly()
            return
        }

        // Android 10+: try overlay trampoline (bypasses all known OEM restrictions)
        if (AppPermissions.isOverlayGranted(this)) {
            AppLog.i("Boot auto-start: launching via overlay window trampoline")
            if (launchViaOverlayTrampoline()) return
        }

        // Fallback: full-screen intent notification
        AppLog.i("Boot auto-start: falling back to full-screen intent notification")
        launchViaFullScreenIntent()
    }

    private fun launchDirectly() {
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
            }
            startActivity(launchIntent)
            AppLog.i("Boot auto-start: direct startActivity succeeded")
        } catch (e: Exception) {
            AppLog.e("Boot auto-start: direct startActivity failed: ${e.message}")
            launchViaFullScreenIntent()
        }
    }

    private fun launchViaOverlayTrampoline(): Boolean {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
        }
        return launchViaOverlayTrampoline(launchIntent)
    }

    private fun launchViaOverlayTrampoline(launchIntent: Intent): Boolean {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            0, 0, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val view = View(this)
        return try {
            wm.addView(view, params)
            startActivity(launchIntent)
            AppLog.i("Overlay trampoline: startActivity succeeded")
            true
        } catch (e: Exception) {
            AppLog.e("Overlay trampoline failed: ${e.message}")
            false
        } finally {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }

    private fun launchViaFullScreenIntent() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val fullScreenPi = PendingIntent.getActivity(this, 200, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_service_running))
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BOOT_START_NOTIFICATION_ID, notification)

        // Dismiss the boot notification after a short delay
        serviceScope.launch {
            delay(5000)
            nm.cancel(BOOT_START_NOTIFICATION_ID)
        }
    }

    // -------------------------------------------------------------------------
    // Boot-loop guard
    // -------------------------------------------------------------------------

    /**
     * Whether to skip wireless bring-up because starting it appears to be crashing the device, and
     * posts the notice explaining that if so.
     *
     * See [BootLoopPolicy]. The decision is taken from the strike count the receiver has already
     * written, so it needs nothing from the start intent and can run here in onCreate.
     */
    private fun applyBootLoopGuard(): Boolean {
        if (Settings.isWirelessPausedByBootLoop(this)) {
            AppLog.w("AapService: Wireless is still paused from an earlier boot loop. Open the app to re-enable it.")
            notifyBootLoopPause()
            return true
        }
        val strikes = Settings.getBootLoopStrikes(this)
        if (!BootLoopPolicy.shouldPauseWireless(strikes)) return false

        AppLog.w(
            "AapService: $strikes boot-started runs in a row ended before " +
                "${BootLoopPolicy.HEALTHY_RUN_MS / 1000}s. Pausing wireless bring-up — on some head units " +
                "the WiFi stack takes the whole system down when a phone joins, and auto-start then " +
                "repeats it forever."
        )
        Settings.setWirelessPausedByBootLoop(this, true)
        notifyBootLoopPause()
        return true
    }

    /**
     * Clears the strikes once this run has lasted long enough to count as healthy.
     *
     * Deliberately time-based rather than hung off a successful connection: on the head unit this
     * guard was written for, one cycle reached a complete projection session with audio playing and
     * the system died anyway, so a connection-based signal would reset the count every pass.
     */
    private fun scheduleBootLoopStrikeClear() {
        if (Settings.getBootLoopStrikes(this) == 0) return
        serviceScope.launch {
            delay(BootLoopPolicy.HEALTHY_RUN_MS)
            AppLog.i("AapService: This run has lasted ${BootLoopPolicy.HEALTHY_RUN_MS / 1000}s. Clearing the boot-loop strikes.")
            Settings.setBootLoopStrikes(this@AapService, 0)
        }
    }

    /**
     * Tells the user wireless was left off and what to do about it. Names the WiFi Direct join when
     * that is the configuration, because on the units this happens to, switching the Native AA
     * transport to the head unit's own hotspot avoids the P2P path altogether.
     */
    private fun notifyBootLoopPause() {
        val settings = App.provide(this).settings
        val onNativeWifiDirect = settings.wifiConnectionMode == WifiLauncherMode.NATIVE &&
            settings.nativeApStrategy == NativeStrategy.WIFI_DIRECT
        val text = getString(
            if (onNativeWifiDirect) R.string.boot_loop_paused_native_wifi_direct
            else R.string.boot_loop_paused_generic
        )

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot-loop guard")
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 201, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(getString(R.string.boot_loop_paused_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(BOOT_LOOP_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            AppLog.w("AapService: Could not post the boot-loop notice: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        @Volatile
        var instance: AapService? = null
            private set

        /**
         * If set to `true`, the service will call [System.exit] at the very end of [onDestroy].
         * This is used by `killOnDisconnect` to ensure all cleanup (like Car Mode) completes
         * before the process dies.
         */
        var killProcessOnDestroy: Boolean = false

        val wifiDirectName = MutableStateFlow<String?>(null)

        /**
         * Emits `true` while a WiFi NSD scan is in progress.
         * Observed by `HomeFragment` via a lifecycle-aware flow collector.
         */
        val scanningState = MutableStateFlow(false)

        private const val BOOT_START_NOTIFICATION_ID = 42
        private const val BOOT_LOOP_NOTIFICATION_ID = 43
        private const val PROJECTION_LAUNCH_NOTIFICATION_ID = 43

        // Service action strings used with startService() and sendBroadcast()
        const val ACTION_START_SELF_MODE           = "com.andrerinas.openheadunit.ACTION_START_SELF_MODE"
        const val ACTION_STOP_SELF_MODE            = "com.andrerinas.openheadunit.ACTION_STOP_SELF_MODE"
        const val ACTION_START_WIRELESS            = "com.andrerinas.openheadunit.ACTION_START_WIRELESS"
        const val ACTION_BT_AUTO_START              = "com.andrerinas.openheadunit.ACTION_BT_AUTO_START"
        const val ACTION_START_WIRELESS_SCAN       = "com.andrerinas.openheadunit.ACTION_START_WIRELESS_SCAN"
        const val ACTION_STOP_WIRELESS             = "com.andrerinas.openheadunit.ACTION_STOP_WIRELESS"
        const val ACTION_NATIVE_AA_POKE            = "com.andrerinas.openheadunit.ACTION_NATIVE_AA_POKE"
        const val ACTION_NEARBY_CONNECT             = "com.andrerinas.openheadunit.ACTION_NEARBY_CONNECT"
        const val ACTION_CHECK_USB                 = "com.andrerinas.openheadunit.ACTION_CHECK_USB"
        const val ACTION_STOP_SERVICE              = "com.andrerinas.openheadunit.aap.action.STOP_SERVICE"
        const val ACTION_DISCONNECT                = "com.andrerinas.openheadunit.ACTION_DISCONNECT"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.openheadunit.aap.action.REQUEST_NIGHT_MODE_UPDATE"
        const val ACTION_NIGHT_MODE_CHANGED      = "com.andrerinas.openheadunit.ACTION_NIGHT_MODE_CHANGED"
        const val ACTION_ORIENTATION_CHANGED     = "com.andrerinas.openheadunit.ACTION_ORIENTATION_CHANGED"
        const val ACTION_REFRESH_SENSORS         = "com.andrerinas.openheadunit.aap.action.REFRESH_SENSORS"
        const val ACTION_RESTART_AUDIO           = "com.andrerinas.openheadunit.aap.action.RESTART_AUDIO"
        const val ACTION_RAISE_PROJECTION        = "com.andrerinas.openheadunit.aap.action.RAISE_PROJECTION"
        /**
         * Sent after the caller has already invoked [CommManager.connect(socket)].
         * The [observeConnectionState] flow observer handles the result — [onStartCommand]
         * does nothing for this action.
         */
        const val ACTION_CONNECT_SOCKET            = "com.andrerinas.openheadunit.ACTION_CONNECT_SOCKET"

        /** Delay before retrying USB connection after an unexpected disconnect. */
        private const val USB_RECONNECT_DELAY_MS = 3000L

        /**
         * `NetworkCallback.onAvailable` fires per network and again on re-validation, so a
         * single join can produce several. One discovery kick per join is what is wanted.
         */
        private const val NETWORK_AVAILABLE_DEBOUNCE_MS = 1000L


        /**
         * How long a link-loss teardown may take. The interface is already on its way down and the
         * broadcast is holding the system up, so this is a budget rather than a target: the
         * ByeBye and the socket close together take well under 200 ms when the link still works,
         * and when it does not there is nothing to wait for.
         */
        private const val LINK_LOSS_TEARDOWN_BUDGET_MS = 1500L

        /** Cooldown period after user-initiated exit. During this window, the WirelessServer
         *  rejects incoming connections to prevent the phone from instantly reconnecting. */
        private const val USER_EXIT_COOLDOWN_MS = 5000L

        /** Screen-off duration (ms) above which SCREEN_ON is treated as a hibernate wake.
         *  60 seconds filters out normal screen timeouts while catching any hibernate/quick boot. */
        private const val HIBERNATE_WAKE_THRESHOLD_MS = 60_000L

        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_ENDPOINT_ID = "extra_endpoint_id"
    }
}
