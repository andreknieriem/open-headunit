package com.andrerinas.openheadunit.aap

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
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.app.BootCompleteReceiver
import com.andrerinas.openheadunit.app.WifiAutoStartReceiver
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.aap.protocol.messages.NightModeEvent
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.NetworkDiscovery
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.andrerinas.openheadunit.connection.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.UsbReceiver
import com.andrerinas.openheadunit.location.GpsLocationService
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.LogExporter
import com.andrerinas.openheadunit.utils.NightModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.view.View
import android.view.WindowManager
import android.media.AudioManager
import com.andrerinas.openheadunit.connection.wifi.LinkLossTeardownPolicy
import com.andrerinas.openheadunit.connection.wifi.LinkLossTrigger
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherStopSequence
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherHelper
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServer
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.utils.Settings
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
class AapService : Service(), UsbReceiver.Listener {

    // SupervisorJob prevents a child coroutine failure from cancelling the whole scope
    val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var uiModeManager: UiModeManager
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var wifiAutoStartReceiver: WifiAutoStartReceiver? = null
    private var mediaSession: MediaSessionCompat? = null
    private val wifiLauncherManager = WifiLauncherManager(this)

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
    private var permanentFocusRequest: android.media.AudioFocusRequest? = null

    private var lastAaMediaMetadata: MediaPlayback.MediaMetaData? = null
    private var lastAaPlaybackPositionMs: Long = 0L
    private var lastAaPlaybackIsPlaying: Boolean? = null
    private var mediaSessionIsPlaying = false
    private var mediaMetadataDecodeJob: Job? = null
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
    private var accessoryHandshakeFailures = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var wifiReadyCallback: ConnectivityManager.NetworkCallback? = null

    private var wifiReadyTimeoutJob: Job? = null
    private var wifiModeInitialized = false

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
     * Guards against duplicate [UsbAccessoryMode.connectAndSwitch] calls AND duplicate
     * [connectUsbWithRetry] calls for devices already in accessory mode.
     *
     * Set to `true` synchronously on the main thread before launching any background
     * USB connect/switch coroutine. Checked in [checkAlreadyConnectedUsb] to prevent
     * multiple concurrent connection attempts on the same device.
     * Cleared in the coroutine's finally block, or on disconnect.
     */
    private val isSwitchingToAccessory = AtomicBoolean(false)

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

    private val commManager get() = App.provide(this).commManager

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
            isSwitchingToAccessory.get()) {
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
            checkAlreadyConnectedUsb(force = true)
        }
    }

    /**
     * Called on events that MIGHT indicate a wake (e.g. POWER_CONNECTED) but aren't
     * conclusive alone. Only checks USB — does not launch the UI.
     */
    private fun onPossibleWake(trigger: String) {
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) return

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: possible wake, checking USB (trigger=$trigger)")
            checkAlreadyConnectedUsb(force = true)
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
        val launcher = wifiLauncherManager.active ?: return

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
            isSwitchingToAccessory.get()) {
            AppLog.i("WakeDetect: already connecting, skipping screen-on auto-start")
            return
        }

        // Not connected — launch UI (which triggers auto-connect via HomeFragment)
        AppLog.i("WakeDetect: launching UI on screen on")
        launchMainActivityOnBoot()

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices on screen on")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onCreate: ${e.message}", e)
            stopSelf()
            return
        }
        setupCarMode()
        setupNightMode()
        observeConnectionState()
        registerReceivers()

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
        checkAlreadyConnectedUsb()
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
                    is CommManager.ConnectionState.Connected -> onConnected()
                    is CommManager.ConnectionState.HandshakeComplete -> {
                        launchAapProjectionActivity()
                    }
                    is CommManager.ConnectionState.TransportStarted -> {
                        hasEverConnected = true
                        accessoryHandshakeFailures = 0
                        sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
                            setPackage(packageName)
                        })
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
                            onHandshakeFailed()
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
    private fun onConnected() {
        isSwitchingToAccessory.set(false)
        updateNotification()
        acquireWifiLock()

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

    private fun launchAapProjectionActivity() {
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
            ActivityLaunchPolicy.LaunchStrategy.NOTIFICATION -> launchProjectionViaNotification(intent)
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
        isSwitchingToAccessory.set(false)
        releaseWifiLock()

        // Stop GpsLocationService and NightModeManager sensor tracking
        AppLog.i("AapService: Stopping GpsLocationService and NightModeManager since connection is disconnected")
        stopService(GpsLocationService.intent(this))
        nightModeManager?.stop()

        // Release any permanent audio focus we may have requested when connected
        releasePermanentAudioFocus()
        App.provide(this).carKeysManager.unregisterReceivers()

        if (!isDestroying) updateNotification()
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = null
        lastAaMediaMetadata = null
        lastAaPlaybackPositionMs = 0L
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
            if (wifiLauncherManager.getActiveMode() == WifiLauncherMode.NATIVE && !state.isUserExit) {
                // Unexpected disconnect — reset and re-initialize for auto-reconnect.
                AppLog.i("AapService: Native AA Mode disconnected. Resetting manager and group in 1.5s...")
                serviceScope.launch {
                    delay(1500) // Give hardware time to settle before re-initializing P2P
                    wifiLauncherManager.setActiveFromSettings(force = true)
                }
            }

            // [FIX] User-initiated disconnect while a WiFi-Direct-hosting mode was active: tear
            // down the P2P group so the phone's OS-level connection actually drops (previously
            // only the AA session ended — the WiFi Direct network stayed up, with nothing to
            // tell Wireless Helper the session had ended). Skipped on unexpected disconnects:
            // mode==3's re-init above and scheduleReconnectIfNeeded() both want to keep/reuse
            // the existing group for fast reconnection there. Must await CommManager's async
            // teardown first so we never remove the P2P interface while the
            // ByeByeRequest/socket-close is still in flight.
            if (state.isUserExit && wifiLauncherManager.active?.hasWifiDirect() ?: false) {
                commManager.awaitDisconnectComplete()
                AppLog.i("AapService: CommManager teardown complete. Stopping WiFi Direct group.")
                wifiLauncherManager.sharedServices.wifiDirectManager?.stop()
                wifiLauncherManager.restartDiscovery()
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
        if (selfMode) {
            AppLog.i("AapService: Self Mode disconnected. Not restarting.")
            selfMode = false
            wifiLauncherManager.stop()
            return
        }

        val settings = App.provide(this).settings

        if (wifiLauncherManager.isActive()) {
            // Skip reconnect for user-initiated exits — the user explicitly wants to stop.
            if (state.isUserExit) {
                AppLog.i("AapService: User exit with wirelessServer active. Not restarting discovery.")
                return
            }
            AppLog.i("AapService: Disconnected. Restarting discovery loop in 2s...")
            serviceScope.launch {
                delay(2000)
                if (!commManager.isConnected)
                    wifiLauncherManager.restartDiscovery()
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
                if (!commManager.isConnected) checkAlreadyConnectedUsb(force = true)
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
        usbReceiver = UsbReceiver(this)
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
            this, usbReceiver,
            UsbReceiver.createFilter(),
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

                    if (!wifiLauncherManager.forceStartDiscoveryScan()) {
                        // Folded into a sweep that was already running — which was started for the
                        // network we have just left. Do not cancel it; just do not make the next
                        // one wait ten seconds either.
                        rescanWithoutWaiting = true
                        AppLog.i("NetworkMonitor: a scan was already in flight; the next one will not wait")
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
        mediaMetadataDecodeJob?.cancel()
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        commManager.onAaMediaMetadata = null
        commManager.onAaPlaybackStatus = null
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(settingsPreferenceListener)
        settingsPrefs = null
        releaseBootWakeLock()

        wifiLauncherManager.stop(WifiLauncherStopSequence.BEFORE_HOTSPOT_DISABLE)
        if (App.provide(this).settings.autoEnableHotspot) {
            AppLog.i("AapService: Auto-disabling hotspot...")
            HotspotManager.setHotspotEnabled(this, false)
        }

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
        try {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
        } catch (e: Exception) {
            AppLog.e("Error releasing MediaSession: ${e.message}")
        }
        mediaSession = null
        commManager.destroy()
        nightModeManager?.stop()
        stopService(GpsLocationService.intent(this))
        try {
            unregisterReceiver(nightModeUpdateReceiver)
            unregisterReceiver(sensorRefreshReceiver)
        } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaButtonReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeDetectReceiver) } catch (_: Exception) {}
        try { App.provide(this).carKeysManager.unregisterReceivers() } catch (e: Exception) { AppLog.w("AapService: Error unregistering carKeysManager: ${e.message}") }
        try { wifiAutoStartReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try {
            if (::uiModeManager.isInitialized) {
                uiModeManager.disableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Error disabling car mode: ${e.message}")
        }
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { LogExporter.stopCapture() } catch (_: Exception) {}
        super.onDestroy()
        if (killProcessOnDestroy) {
            AppLog.i("AapService: killProcessOnDestroy is true. Triggering System.exit(0).")
            System.exit(0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
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
            ACTION_START_SELF_MODE       -> startSelfMode()
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

                    if (wifiLauncherManager.getActiveMode() != WifiLauncherMode.NATIVE || settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
                        AppLog.i("AapService: Initializing Native AA mode before poke...")
                        wifiLauncherManager.setActiveFromSettings(force = true)
                    } else if (activeLauncher is WifiLauncherNative && activeLauncher.handshakeManager?.isActive() != true) {
                        // A completed handoff closes the AA listeners while leaving the manager
                        // running, and start() returns immediately on isRunning - so calling it here
                        // reopened nothing. The poke then woke the phone, the phone opened RFCOMM,
                        // and nothing was listening: the button appeared to do nothing however many
                        // times it was pressed. A full re-init is what reopens them, which is what
                        // the Bluetooth auto-start path below already does for the same reason.
                        AppLog.i("AapService: Native AA listeners are closed — re-arming before the poke.")
                        wifiLauncherManager.stop()
                        wifiLauncherManager.setActiveFromSettings(force = true)
                    } else {
                        AppLog.d("AapService: Already in Native AA mode, skipping re-init.")
                    }

                    val launcher = wifiLauncherManager.active

                    if (launcher is WifiLauncherNative) {
                        launcher.handshakeManager?.manualPoke(mac)
                    } else {
                        ToastUtils.showToast(this, "Native AA mode not active.")
                    }
                }
            }
            ACTION_BT_AUTO_START          -> {
                // AutoStartReceiver fires this on ACL_CONNECTED from a trusted device. If the
                // service process was already alive (e.g. survived a prior disconnect/exit in
                // this same session), onCreate()'s initWifiMode() never re-runs, so a Native AA
                // mode that was stopped after a user exit (nativeAaHandshakeManager.stop() at
                // disconnect) would otherwise stay dead forever despite the phone reconnecting.
                // Only force a re-init when it's actually stopped — on a genuine cold start,
                // onCreate() already armed everything moments ago and re-running would tear
                // down and recreate the P2P group (new random SSID/passphrase) right as it's
                // being delivered to the phone.
                // A successful handoff closes the AA listeners, so isActive() is false for the
                // whole life of a working session — without the connection check below, any
                // later ACL_CONNECTED (the phone's own Bluetooth profiles reconnecting, or one
                // of our pokes) would tear down a session that is projecting fine.
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                val launcher = wifiLauncherManager.active

                if (launcher is WifiLauncherNative && !sessionUp &&
                    launcher.handshakeManager?.isActive() != true &&
                    launcher.handshakeManager?.isAttemptInFlight() != true) {
                    AppLog.i("AapService: Bluetooth auto-start — Native AA handshake manager was stopped, re-arming.")
                    userExitedAA = false
                    userExitCooldownUntil = 0L
                    wifiLauncherManager.setActiveFromSettings(force = true)
                }
            }
            ACTION_NEARBY_CONNECT         -> {
                val endpointId = intent?.getStringExtra(EXTRA_ENDPOINT_ID)
                if (endpointId != null) {
                    AppLog.i("AapService: Connecting to Nearby endpoint $endpointId")

                    val launcher = WifiLauncherHelper(wifiLauncherManager, HelperStrategy.NEARBY_DEVICES)
                    wifiLauncherManager.setActive(launcher, force = true)
                    launcher.nearbyManager?.connectToEndpoint(endpointId)
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
            ACTION_CHECK_USB             -> checkAlreadyConnectedUsb(force = true)
            else                         -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    checkAlreadyConnectedUsb()
                }
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // USB
    // -------------------------------------------------------------------------

    override fun onUsbAttach(device: UsbDevice) {
        if (!UsbDeviceCompat.isAndroidDevice(device)) {
            AppLog.i("Ignoring non-Android USB device attached in service (VID: ${device.vendorId}): ${device.deviceName}")
            return
        }
        userExitedAA = false
        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            launchMainActivityIfNeeded("USB accessory attach")
            checkAlreadyConnectedUsb(force = true)
        } else {
            // UsbAttachedActivity normally handles normal-mode devices via a manifest intent
            // filter. However, some headunits (especially Chinese MediaTek units) don't
            // deliver USB_DEVICE_ATTACHED to activities on cold start. As a fallback,
            // check after a delay to give UsbAttachedActivity a chance to handle it first.
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Normal USB device attached: $deviceName. Will check auto-connect in ${USB_ATTACH_FALLBACK_DELAY_MS}ms...")
            launchMainActivityIfNeeded("USB normal attach ($deviceName)")
            serviceScope.launch {
                delay(USB_ATTACH_FALLBACK_DELAY_MS)
                if (!commManager.isConnected && !isSwitchingToAccessory.get()) {
                    AppLog.i("UsbAttachedActivity didn't handle $deviceName. Trying from service...")
                    checkAlreadyConnectedUsb(force = true)
                }
            }
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        userExitedAA = false
        if (commManager.isConnectedToUsbDevice(device)) {
            // Cable physically removed — the USB connection is already dead, so skip the
            // ByeByeRequest send (which would block ~1 s trying to write to a gone device).
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }
    }

    override fun onUsbAccessoryDetach() {
        AppLog.i("USB Accessory detached. This might be a transient state (e.g., 100% battery). Attempting to re-sync...")
        userExitedAA = false
        if (commManager.isConnected) {
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }

        // Wait a bit and check if the device is still there in normal mode
        serviceScope.launch {
            delay(1500) // Give the phone/system time to settle its USB state
            AppLog.i("Accessory detach cooldown finished. Checking for re-connection...")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {
        if (!UsbDeviceCompat.isAndroidDevice(device)) {
            AppLog.i("Ignoring USB permission callback for non-Android device (VID: ${device.vendorId}): ${device.deviceName}")
            return
        }
        val deviceName = UsbDeviceCompat(device).uniqueName
        if (granted) {
            AppLog.i("USB permission granted for $deviceName")
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                isSwitchingToAccessory.set(true)
                serviceScope.launch {
                    try {
                        connectUsbWithRetry(device)
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
            } else {
                isSwitchingToAccessory.set(true)
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val settings = App.provide(this).settings
                val usbMode = UsbAccessoryMode(usbManager)
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                            AppLog.i("Successfully requested switch to accessory mode for $deviceName")
                        } else {
                            AppLog.w("USB permission granted but connectAndSwitch failed for $deviceName")
                        }
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
            }
        } else {
            AppLog.w("USB permission denied for $deviceName")
            ToastUtils.showToast(this, getString(R.string.usb_permission_denied), Toast.LENGTH_LONG)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = UsbReceiver.createPermissionPendingIntent(this)
        AppLog.i("Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        try {
            ToastUtils.showToast(this, getString(R.string.requesting_usb_permission), Toast.LENGTH_SHORT)
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to request USB permission: ${e.message}. This device might not support USB permission dialogs.", e)
            ToastUtils.showToast(this, getString(R.string.error_usb_permission_failed), Toast.LENGTH_LONG)
        }
    }

    /**
     * Called when a handshake fails. If an accessory-mode device is still present,
     * it's likely a stale wireless AA dongle. Force re-enumeration by sending AOA
     * descriptors — this resets the dongle's USB state so the next connection
     * starts with clean buffers.
     */
    private fun onHandshakeFailed() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessoryDevice = usbManager.deviceList.values.firstOrNull {
            UsbDeviceCompat.isInAccessoryMode(it)
        } ?: return

        accessoryHandshakeFailures++
        val deviceName = UsbDeviceCompat(accessoryDevice).uniqueName
        AppLog.w("Handshake failed on accessory device $deviceName (failure #$accessoryHandshakeFailures)")

        if (accessoryHandshakeFailures > MAX_STALE_ACCESSORY_RETRIES) {
            AppLog.i("Stale accessory detected: forcing re-enumeration via AOA descriptors for $deviceName")
            accessoryHandshakeFailures = 0
            val settings = App.provide(this).settings
            val usbMode = UsbAccessoryMode(usbManager)
            isSwitchingToAccessory.set(true)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(accessoryDevice, settings.useLibusb)) {
                        AppLog.i("AOA re-enumeration requested for stale device $deviceName")
                    } else {
                        AppLog.w("AOA re-enumeration failed for $deviceName")
                    }
                } catch (e: Exception) {
                    AppLog.e("AOA re-enumeration for $deviceName failed with exception", e)
                } finally {
                    isSwitchingToAccessory.set(false)
                }
            }
        }
    }

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     */
    private fun checkAlreadyConnectedUsb(force: Boolean = false) {
        val settings = App.provide(this).settings
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice
        val usbAutoStart = settings.autoStartOnUsb

        if (!force && !lastSession && !singleUsb && !usbAutoStart) return
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) return

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList.values.filter { UsbDeviceCompat.isAndroidDevice(it) }

        // Check for devices already in accessory mode first.
        // After AOA switch the device re-enumerates and appears as a new USB device — we must
        // request permission for this new device before openDevice(), or SecurityException occurs.
        for (device in deviceList) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                val deviceName = UsbDeviceCompat(device).uniqueName
                AppLog.i("Found device already in accessory mode: $deviceName")
                if (!usbManager.hasPermission(device)) {
                    AppLog.i("Accessory-mode device has no permission (re-enumerated); requesting permission: $deviceName")
                    requestUsbPermission(device)
                    return
                }
                isSwitchingToAccessory.set(true)
                serviceScope.launch {
                    try {
                        connectUsbWithRetry(device)
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
                return
            }
        }

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        isSwitchingToAccessory.set(true)
                        val usbMode = UsbAccessoryMode(usbManager)
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                                    AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                                } else {
                                    AppLog.w("connectAndSwitch failed for ${deviceCompat.uniqueName}")
                                }
                            } finally {
                                isSwitchingToAccessory.set(false)
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestUsbPermission(device)
                        return
                    }
                }
            }
        }

        // USB auto-start mode: attempt AOA switch for any single non-accessory device
        if (usbAutoStart) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                performSingleUsbConnect(nonAccessoryDevices[0])
                return
            }
        }

        // Single-USB mode: connect if there's exactly one candidate device.
        // If the user has marked specific devices as "Allowed" in the USB list,
        // only count those — so non-AA peripherals (dashcams, USB audio, etc.)
        // don't prevent auto-connect. Falls back to counting all devices when
        // no devices have been explicitly allowed (fresh install).
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowed = settings.allowedDevices
            val candidates = if (allowed.isNotEmpty()) {
                nonAccessoryDevices.filter { allowed.contains(UsbDeviceCompat(it).uniqueName) }
            } else {
                nonAccessoryDevices
            }
            if (allowed.isNotEmpty() && candidates.size != nonAccessoryDevices.size) {
                AppLog.i("Single USB auto-connect: ${nonAccessoryDevices.size} USB device(s) present, ${candidates.size} allowed")
            }
            if (candidates.size == 1) {
                performSingleUsbConnect(candidates[0])
                return
            }
        }

        // Fallback: if force=true and we have a single Google VID device in normal mode,
        // switch it to accessory mode. This handles cases where UsbAttachedActivity didn't fire.
        if (force) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val googleDevices = nonAccessoryDevices.filter { it.vendorId == 0x18D1 }
            if (googleDevices.size == 1) {
                AppLog.i("Fallback: force=true and found single Google normal-mode device ${UsbDeviceCompat(googleDevices[0]).uniqueName}. Switching to accessory mode.")
                performSingleUsbConnect(googleDevices[0])
            }
        }
    }

    private fun performSingleUsbConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            isSwitchingToAccessory.set(true)
            val usbMode = UsbAccessoryMode(usbManager)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                        AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                    } else {
                        AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                    }
                } finally {
                    isSwitchingToAccessory.set(false)
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestUsbPermission(device)
        }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    private suspend fun connectUsbWithRetry(device: UsbDevice, maxRetries: Int = 3) {
        var retryCount = 0
        var success = false
        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            commManager.connect(device)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }
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
    private fun launchMainActivityIfNeeded(source: String) {
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
    // Self Mode
    // -------------------------------------------------------------------------

    /**
     * "Self Mode" connects the device to itself over the loopback interface.
     *
     * Starts [WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    private fun isAaVersion174OrHigher(): Boolean {
        return try {
            val pInfo = packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
            val vName = pInfo.versionName ?: ""
            val parts = vName.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            AppLog.i("SelfMode: Installed AA version: $vName (major=$major, minor=$minor)")
            major > 17 || (major == 17 && minor >= 4)
        } catch (e: Exception) {
            AppLog.w("SelfMode: Failed to query AA version: ${e.message}")
            false
        }
    }

    private fun openAaSettings() {
        val intent = Intent().apply {
            setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                    data = android.net.Uri.parse("package:com.google.android.projection.gearhead")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                AppLog.e("SelfMode: Failed to open AA settings: ${e2.message}")
            }
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun startSelfMode() {
        selfMode = true

        serviceScope.launch(Dispatchers.Main) {
            if (isAaVersion174OrHigher()) {
                AppLog.i("SelfMode: AA 17.4+ detected. Connecting directly to Headunit Server on 127.0.0.1:5277...")
                val success = withContext(Dispatchers.IO) {
                    commManager.connect("127.0.0.1", 5277)
                    commManager.isConnected
                }
                if (!success && !commManager.isConnected) {
                    AppLog.w("SelfMode: Headunit Server (127.0.0.1:5277) is NOT running.")
                    ToastUtils.showToast(
                        this@AapService,
                        "Android Auto 17.4+ detected: Please start 'Headunit Server' in Android Auto Developer Settings!",
                        Toast.LENGTH_LONG
                    )
                    openAaSettings()
                }
                return@launch
            }

            AppLog.i("SelfMode: AA < 17.4 detected. Starting WirelessServer on 5288 and running legacy triggers...")
            wifiLauncherManager.setActive(WifiLauncherMode.NATIVE)

            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager.activeNetwork == null) {
                // Wait up to 1 second for the Dummy VPN to become the active network
                for (i in 1..10) {
                    if (connectivityManager.activeNetwork != null) break
                    delay(100)
                }
            }

            val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                connectivityManager.activeNetwork else null
            val networkToUse = activeNetwork ?: createFakeNetwork(0)
            val fakeWifiInfo = createFakeWifiInfo()

            val magicalIntent = Intent().apply {
                setClassName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", 5288)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
            }

            try {
                AppLog.i("Launching AA Wireless Startup via Activity...")
                startActivity(magicalIntent)
            } catch (e: Exception) {
                AppLog.w("Activity launch failed (${e.message}). Attempting Broadcast fallback...")
                try {

                    AppLog.w("WirelessStartupActivity not found (AA 16.4+ detected).")
                    if (Build.VERSION.SDK_INT <= 29) {
                        // On Android 10, if Activity is gone, Broadcast will definitely be blocked by Gearhead's version check.
                        AppLog.e("Self-mode blocked by Google on Android 10 (AA 16.4+). Skipping broadcast fallback.")
                        ToastUtils.showToast(this@AapService, getString(R.string.failed_self_mode_android10), Toast.LENGTH_LONG)
                    } else {
                        val receiverIntent = Intent().apply {
                            setClassName(
                                "com.google.android.projection.gearhead",
                                "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
                            )
                            action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                            putExtra("ip_address", "127.0.0.1")
                            putExtra("projection_port", 5288)
                            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                            fakeWifiInfo?.let { putExtra("wifi_info", it) }
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        sendBroadcast(receiverIntent)
                        AppLog.i("Broadcast fallback 1 (WirelessStartupReceiver) sent.")

                        // Fallback 2: WifiBluetoothReceiver (START_WIRELESS_PROJECTION) for AA 17.4+
                        val bondedAddress = try {
                            val adapter = BluetoothHelper.getBluetoothAdapter(this@AapService)
                            val bonded = adapter?.bondedDevices
                            val connectedDevice = bonded?.firstOrNull { dev ->
                                try {
                                    val m = dev.javaClass.getMethod("isConnected")
                                    (m.invoke(dev) as? Boolean) == true
                                } catch (e: Exception) { false }
                            }
                            val targetDev = connectedDevice ?: bonded?.firstOrNull()
                            val selfAddr: String? = try { adapter?.address } catch (se: SecurityException) { null }
                            AppLog.i("SelfMode BT Discovery: bondedCount=${bonded?.size ?: 0}, connectedMac=${connectedDevice?.address}, selectedMac=${targetDev?.address}")
                            targetDev?.address ?: if (!selfAddr.isNullOrBlank() && selfAddr != "02:00:00:00:00:00") selfAddr else null
                        } catch (e: Throwable) {
                            AppLog.w("Failed to get bonded BT device address: ${e.message}")
                            null
                        } ?: "00:11:22:33:44:55"

                        val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                            setClassName(
                                "com.google.android.projection.gearhead",
                                "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver"
                            )
                            putExtra("DEVICE_ADDRESS", bondedAddress)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        sendBroadcast(btReceiverIntent)
                        AppLog.i("Broadcast fallback 2 (WifiBluetoothReceiver START_WIRELESS_PROJECTION with MAC $bondedAddress) sent.")
                    }
                } catch (e2: Exception) {
                    AppLog.e("All triggers failed", e2)
                    ToastUtils.showToast(this@AapService, getString(R.string.failed_start_android_auto), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /** Reflectively constructs an `android.net.Network` from a raw network ID integer. */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    /** Reflectively constructs a `WifiInfo` with a fake SSID for the Self Mode intent. */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID")
                    .apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (e: Exception) {}
            wifiInfo
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        /**
         * If set to `true`, the service will call [System.exit] at the very end of [onDestroy].
         * This is used by `killOnDisconnect` to ensure all cleanup (like Car Mode) completes
         * before the process dies.
         */
        var killProcessOnDestroy: Boolean = false

        /** `true` while a Self Mode session is active. */
        var selfMode = false

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
        /**
         * Sent after the caller has already invoked [CommManager.connect(socket)].
         * The [observeConnectionState] flow observer handles the result — [onStartCommand]
         * does nothing for this action.
         */
        const val ACTION_CONNECT_SOCKET            = "com.andrerinas.openheadunit.ACTION_CONNECT_SOCKET"

        /** Max handshake failures on a stale accessory device before forcing AOA re-enumeration. */
        private const val MAX_STALE_ACCESSORY_RETRIES = 1

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

        /** Delay before AapService tries to handle a normal-mode USB attach as a fallback
         *  when UsbAttachedActivity doesn't fire (common on Chinese MediaTek headunits). */
        private const val USB_ATTACH_FALLBACK_DELAY_MS = 2000L

        /** Screen-off duration (ms) above which SCREEN_ON is treated as a hibernate wake.
         *  60 seconds filters out normal screen timeouts while catching any hibernate/quick boot. */
        private const val HIBERNATE_WAKE_THRESHOLD_MS = 60_000L

        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_ENDPOINT_ID = "extra_endpoint_id"
    }
}
