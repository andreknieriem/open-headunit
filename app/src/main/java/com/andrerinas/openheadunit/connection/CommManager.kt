package com.andrerinas.openheadunit.connection
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.aap.AapSslContext
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.KeyDebouncePolicy
import com.andrerinas.openheadunit.aap.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.aap.PlaybackFocusPolicy
import com.andrerinas.openheadunit.aap.VideoStarvationPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.decoder.VideoDecoder
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.messages.SensorEvent
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import java.net.Socket
import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Input.TouchEvent.PointerAction
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbySocket

/**
 * Central connection and transport lifecycle manager.
 *
 * CommManager owns the full lifecycle of both the physical connection ([AccessoryConnection])
 * and the AAP protocol layer ([AapTransport]). It exposes a single [connectionState] flow as
 * the source of truth; all consumers (AapService, AapProjectionActivity, UI fragments) observe
 * this flow reactively instead of being called imperatively.
 *
 * ## State machine
 * ```
 *   Disconnected ──connect()──► Connecting ──success──► Connected
 *                                                            │
 *                                                   startHandshake()
 *                                                            │
 *                                                   StartingTransport
 *                                                            │
 *                                                     SSL done
 *                                                            │
 *                                                   HandshakeComplete
 *                                                            │
 *                                                    startReading()
 *                                                            │
 *                                                    TransportStarted
 *                                                            │
 *                                      disconnect() / read error / phone bye-bye
 *                                                            │
 *                                                      Disconnected
 * ```
 *
 * ## Thread safety
 * [_transport] and [_connection] are `@Volatile`. All state mutations go through
 * [_connectionState] (a [MutableStateFlow]) which is thread-safe. The internal [_scope] uses
 * [Dispatchers.IO] with a [SupervisorJob] so individual coroutine failures do not cancel the
 * entire scope.
 */
class CommManager(
    private val context: Context,
    private val settings: Settings,
    private val audioDecoder: AudioDecoder,
    private val videoDecoder: VideoDecoder) {

    // Single AapSslContext for the lifetime of this CommManager. Its internal SSLContext holds
    // JSSE's ClientSessionContext session cache, which survives transport recreations and enables
    // abbreviated TLS handshakes on reconnect (session resumption).
    private val aapSslContext: AapSslContext = AapSslContext(SingleKeyKeyManager(context))

    /**
     * Represents the lifecycle state of the Android Auto connection.
     *
     * State transitions are strictly sequential — see the class-level diagram.
     */
    sealed class ConnectionState {
        /**
         * No active connection.
         * @param isClean `true` if the phone sent a graceful `ByeByeRequest` before closing;
         *                `false` for all other disconnect causes (USB detach, read error,
         *                socket timeout, explicit user disconnect).
         */
        data class Disconnected(
            val isClean: Boolean = false,
            val isUserExit: Boolean = false
        ) : ConnectionState()

        /** Physical connection handshake in progress (USB open or TCP connect). */
        object Connecting : ConnectionState()

        /** Physical connection established; AAP handshake not yet started. */
        object Connected : ConnectionState()

        /** AAP SSL handshake in progress. */
        object StartingTransport : ConnectionState()

        /**
         * SSL handshake complete;
         */
        object HandshakeComplete : ConnectionState()

        /** AAP handshake complete; the transport is ready to send and receive messages. */
        object TransportStarted : ConnectionState()

        /** A non-fatal error occurred. The manager transitions to [Disconnected] immediately after. */
        data class Error(val message: String) : ConnectionState()
    }

    /** IO-bound coroutine scope for all async connection work. SupervisorJob prevents one
     *  failing child from cancelling the rest. */
    private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cached answer of the Bluetooth media probe used by media-key routing, and when it was taken. */
    private var btMediaLinkCached: Boolean? = null
    private var btMediaLinkCheckedAt: Long? = null
    private val BT_MEDIA_LINK_CACHE_MS = 2_000L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())

    /** Callback for audio focus state changes (isPlaying). Set by AapService. */
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null

    /** Now-playing metadata from the phone (AAP media channel). Set by AapService. */
    var onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null
    /** Playback status from the phone (AAP media channel), includes current position. */
    var onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null

    /** @Volatile: written on IO thread, read on Main and IO threads. */
    @Volatile private var _transport: AapTransport? = null
    var onUpdateUiConfigReplyReceived: (() -> Unit)? = null
    @Volatile private var _connection: AccessoryConnection? = null

    /**
     * Tracks the most-recently-launched [doDisconnect] coroutine.
     * [connect] overloads join this job before opening a new connection, ensuring the previous
     * device is fully closed before `openDevice()` is called on it again.
     */
    @Volatile private var _disconnectJob: kotlinx.coroutines.Job? = null

    // Whether the session now ending ever completed its handshake, and how many sessions in a row
    // have completed one and then carried no video at all. See VideoStarvationPolicy.
    @Volatile private var sessionReachedHandshake = false
    @Volatile private var starvedSessionStreak = 0

    private val _backgroundNotification = BackgroundNotification(context)

    /** Public read-only view of [_connectionState]. */
    val connectionState = _connectionState.asStateFlow()

    /**
     * `true` while a physical connection exists, regardless of whether the AAP transport
     * handshake has completed. Covers [ConnectionState.Connected], [ConnectionState.StartingTransport],
     * and [ConnectionState.TransportStarted].
     */
    val isConnected: Boolean
        get() = connectionState.value.let {
            it is ConnectionState.Connected ||
            it is ConnectionState.StartingTransport ||
            it is ConnectionState.HandshakeComplete ||
            it is ConnectionState.TransportStarted
        }

    /**
     * Returns `true` if the current USB connection is to [device].
     * Used by AapService to decide whether a USB detach event should trigger a disconnect.
     */
    fun isConnectedToUsbDevice(device: UsbDevice): Boolean =
        (_connection as? UsbAccessoryConnection)?.isDeviceRunning(device) == true ||
        (_connection as? LibusbAccessoryConnection)?.isDeviceRunning(device) == true

    // -----------------------------------------------------------------------------------------
    // connect() overloads — one for each transport type
    // -----------------------------------------------------------------------------------------

    /**
     * Opens a USB accessory connection to [device].
     *
     * On success emits [ConnectionState.Connected] and persists the device as the last-used
     * connection so it can be auto-reconnected on the next launch.
     */
    suspend fun connect(device: UsbDevice) = withContext(Dispatchers.IO) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return@withContext


        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            _connectionState.emit(ConnectionState.Error("USB permission not granted for device"))
            return@withContext
        }

        // Wait for any in-progress cleanup to finish before opening the USB device.
        // Without this, openDevice() on the same hardware can return null because the previous
        // UsbDeviceConnection hasn't been close()d yet.
        _disconnectJob?.join()

        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = if (settings.useLibusb) {
                LibusbAccessoryConnection(usbManager, device)
            } else {
                UsbAccessoryConnection(usbManager, device)
            }

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_USB, usbDevice = UsbDeviceCompat.getUniqueName(device))
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Wraps an already-connected [Socket] (e.g. accepted by `WirelessServer`) in a
     * [SocketAccessoryConnection] and advances to [ConnectionState.Connected].
     *
     * The socket must already be connected; this overload skips the TCP handshake and only
     * sets up the AAP framing layer.
     */
    suspend fun connect(socket: Socket) = withContext(Dispatchers.IO) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return@withContext


        _disconnectJob?.join()

        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = SocketAccessoryConnection(socket, context)

            if (_connection?.connect() ?: false) {
                // [FIX] Don't overwrite NEARBY connection type with WIFI + localhost IP (::1)
                if (socket !is NearbySocket) {
                    settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = socket.inetAddress?.hostAddress ?: "")
                }
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Opens a TCP connection to [ip]:[port] and advances to [ConnectionState.Connected].
     *
     * Used by the manual IP entry flow and the NSD-discovered device list.
     */
    suspend fun connect(ip: String, port: Int) = withContext(Dispatchers.IO) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return@withContext

        _disconnectJob?.join()

        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = SocketAccessoryConnection(ip, port, context)

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = ip)
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Transport lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Phase 1: runs the SSL handshake over the current connection.
     *
     * Must only be called when state is [ConnectionState.Connected]. On success, emits
     * [ConnectionState.HandshakeComplete] and returns; the inbound message loop is NOT
     * started yet. Call [startReading] after [VideoDecoder.setSurface] has been invoked
     * to begin receiving messages.
     *
     * The [AapTransport.onQuit] callback is wired here; it fires whenever the transport
     * stops (read error, phone bye-bye, timeout) and triggers [transportedQuited].
     *
     * Called by [com.andrerinas.openheadunit.aap.AapService] in parallel with the
     * projection activity startup, so the handshake latency is hidden behind activity
     * inflation time rather than added on top of it.
     */
    suspend fun startHandshake() = withContext(Dispatchers.IO) {
        // Another caller already started the handshake — do nothing.
        if (_connectionState.value is ConnectionState.StartingTransport) return@withContext

        try {
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.emit(ConnectionState.StartingTransport)

                if (_transport == null) {
                    val audioManager = context.getSystemService(Application.AUDIO_SERVICE) as AudioManager
                    _transport = AapTransport(
                        audioDecoder,
                        videoDecoder,
                        audioManager,
                        settings,
                        _backgroundNotification,
                        context,
                        externalSsl = aapSslContext,
                        onAaMediaMetadata = { meta -> onAaMediaMetadata?.invoke(meta) },
                        onAaPlaybackStatus = { status -> onAaPlaybackStatus?.invoke(status) }
                    )
                    _transport!!.onQuit = { isClean ->
                        val oldTransport = _transport
                        _transport = null

                        if (oldTransport != null) {
                            transportedQuited(isClean)
                        }
                    }
                    _transport!!.onAudioFocusStateChanged = { isPlaying -> onAudioFocusStateChanged?.invoke(isPlaying) }
                    _transport!!.onUpdateUiConfigReplyReceived = { onUpdateUiConfigReplyReceived?.invoke() }
                }
                if (_transport?.startHandshake(_connection!!) == true) {
                    // A session that got this far had a working link to carry video on. See
                    // VideoStarvationPolicy for what it means when one ends without carrying any.
                    sessionReachedHandshake = true
                    videoDecoder.framesRenderedThisSession = 0L
                    _connectionState.emit(ConnectionState.HandshakeComplete)
                } else {
                    _connectionState.emit(ConnectionState.Error("Handshake failed"))
                    disconnect()
                }
            } else {
                _connectionState.emit(ConnectionState.Error("Starting handshake without connection"))
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Handshake failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Phase 2: starts the inbound message loop.
     *
     * Must only be called when state is [ConnectionState.HandshakeComplete], which implies
     * both that the SSL handshake has succeeded **and** that [VideoDecoder.setSurface] has
     * already been called by [com.andrerinas.openheadunit.aap.AapProjectionActivity].
     * This ordering guarantees no video frame is ever decoded before a render target exists.
     *
     * On success:
     * 1. In Static Audio Focus mode, claims permanent audio focus for `STREAM_MUSIC`.
     * 2. Starts the [AapTransport] read loop.
     * 3. Emits [ConnectionState.TransportStarted].
     */
    suspend fun startReading() = withContext(Dispatchers.IO) {
        if (_connectionState.value !is ConnectionState.HandshakeComplete) return@withContext

        try {
            // Capture the @Volatile _transport once: it can be cleared concurrently by a
            // disconnect, so a stable local reference avoids racing reads and lets us bail out
            // early instead of emitting TransportStarted when no reading actually started.
            val transport = _transport ?: return@withContext
            // Only grab permanent AUDIOFOCUS_GAIN in Static Audio Focus mode, matching the
            // gating in AapService.requestPermanentAudioFocus and AapControl.audioFocusRequest.
            // In the default (dynamic) mode focus is acquired on demand via the AA protocol, so
            // an unconditional grab here would evict other media (e.g. the car radio) the moment
            // the phone connects, before AA plays anything.
            //
            // And even in static mode, not when the player we would evict is the head unit's own
            // A2DP sink: it answers by AVRCP-pausing the phone that is about to project to us.
            if (settings.enableAudioSink && settings.staticAudioFocus) {
                val mode = settings.playbackFocusMode
                val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
                if (PlaybackFocusPolicy.shouldAcquirePermanent(
                        mode = mode,
                        staticAudioFocus = true,
                        audioSinkEnabled = true,
                        btMediaLinkActive = btMediaLinkActive)) {
                    transport.aapAudio?.requestFocusChange(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN,
                        AudioManager.OnAudioFocusChangeListener { }
                    )
                } else {
                    AppLog.i("CommManager: Static Audio Focus - leaving system audio focus alone " +
                            "(mode=$mode, bluetoothMedia=$btMediaLinkActive)")
                }
            }
            transport.startReading()
            _connectionState.emit(ConnectionState.TransportStarted)
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Start reading failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Called by `AapTransport.onQuit` when the transport stops itself (read error, socket
     * timeout, or phone-initiated graceful close).
     *
     * Sets state to [ConnectionState.Disconnected] synchronously (so [isConnected] returns
     * `false` immediately) then schedules cleanup. `sendByeBye` is `false` because the
     * connection is already dead — there is no point sending a `ByeByeRequest`.
     */
    private fun transportedQuited(isClean: Boolean) {
        val wasUserExit = _transport?.wasUserExit ?: false
        _connectionState.value = ConnectionState.Disconnected(isClean, isUserExit = wasUserExit)
        // Transport already quit on its own — no ByeByeRequest needed (connection is dead).
        _disconnectJob = _scope.launch { doDisconnect(sendByeBye = false) }
        if (settings.killOnDisconnect) {
            context.sendBroadcast(android.content.Intent("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES").apply {
                setPackage(context.packageName)
            })
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Stop the foreground service first to remove the notification
                val stopIntent = android.content.Intent(context, com.andrerinas.openheadunit.aap.AapService::class.java).apply {
                    action = com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SERVICE
                }
                com.andrerinas.openheadunit.aap.AapService.killProcessOnDestroy = true
                context.stopService(stopIntent)
                // Finish all tasks (API 21+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.appTasks.forEach { it.finishAndRemoveTask() }
                }
            }, 500)
        }
    }

    // -----------------------------------------------------------------------------------------
    // send() overloads — fire-and-forget; silently dropped if not TransportStarted
    // -----------------------------------------------------------------------------------------

    private val keyStates = mutableMapOf<Int, KeyDebouncePolicy.KeyState>()

    /**
     * Sends a key press or release event to the phone with remapping and de-duplication.
     * This is the single entry point for all key events in the application.
     *
     * @param downTime `KeyEvent.getDownTime()` when the caller has a real event. It identifies the
     *                 physical press, so [KeyDebouncePolicy] can tell one press arriving by several
     *                 routes from two presses in quick succession. Callers holding only a keycode —
     *                 the proprietary OEM broadcasts — leave it null and get the time window.
     * @param source   where this delivery came from, for the log. One press reaching several
     *                 sources is the normal case on these head units, and naming them is what makes
     *                 a user's log readable.
     */
    fun sendKey(keyCode: Int, isPress: Boolean, downTime: Long? = null, source: String = "unknown") {
        if (_connectionState.value !is ConnectionState.TransportStarted) {
            return
        }

        // 1. Remapping (Physical -> Logical)
        // Check if the physical keyCode is mapped to a logical action in settings.
        // If not mapped, we use the original keyCode as the logical code.
        var logicalCode = settings.keyCodes.entries.find { it.value == keyCode }?.key ?: keyCode

        // 2. Proprietary Key Filtering
        // If the key is proprietary (internal ID > 1000) and NOT mapped, we drop it.
        // These keys are intended to be learned/mapped in the Keymap settings.
        // Sending them directly to AA would result in KEYCODE_UNKNOWN.
        if (keyCode >= 1000 && logicalCode == keyCode) {
            AppLog.v("CommManager: Ignoring unmapped proprietary key $keyCode")
            return
        }

        // [FIX] BMW/Rotary Enter remapping: Most AA apps expect DPAD_CENTER (23) for selection,
        // but physical rotary knobs often send ENTER (66). Remap 66 -> 23 to ensure selection works.
        if (logicalCode == KeyEvent.KEYCODE_ENTER) {
            logicalCode = KeyEvent.KEYCODE_DPAD_CENTER
        }

        val isMedia = isMediaKey(logicalCode)

        // 3. Routing: a media button the head unit's own Bluetooth side is already acting on must
        // not be sent again, or one press performs the action twice. See MediaKeyRoutingPolicy.
        // Only media keys can be held back, so nothing else pays for the Bluetooth probe.
        if (isMedia) {
            val routing = settings.mediaKeyRouting
            if (!MediaKeyRoutingPolicy.shouldForward(routing, true, btMediaLinkForKeys())) {
                AppLog.v("CommManager: Not sending media key $logicalCode to Android Auto " +
                        "(routing=$routing, src=$source)")
                return
            }
        }

        // 4. De-duplication: one press reaches us from several delivery paths at once.
        val state = keyStates[logicalCode] ?: KeyDebouncePolicy.KeyState()
        val decision = KeyDebouncePolicy.decide(
            state = state,
            isPress = isPress,
            downTime = downTime,
            isMediaKey = isMedia,
            now = SystemClock.elapsedRealtime()
        )
        keyStates[logicalCode] = decision.state

        if (!decision.forward) {
            AppLog.v("CommManager: Dropping key $logicalCode (isPress=$isPress, src=$source) - ${decision.dropReason}")
            return
        }

        if (decision.releaseFirst) {
            AppLog.i("CommManager: Key $logicalCode was still held from an earlier press with no release - releasing it first")
            _transport?.send(logicalCode, false)
        }

        AppLog.i("CommManager: TX Key -> AA=$logicalCode (isPress=$isPress) src=$source")
        _transport?.send(logicalCode, isPress)
    }

    /**
     * Whether a Bluetooth media link is up, for [MediaKeyRoutingPolicy]. Null when the adapter would
     * not say.
     *
     * Cached briefly rather than probed per press: `getProfileConnectionState` is a binder call and
     * this runs on the main thread, from the projection activity's key dispatch. The window is short
     * enough that toggling media audio on the phone's Bluetooth entry takes effect without
     * reconnecting, which is how this gets tested.
     */
    private fun btMediaLinkForKeys(): Boolean? {
        val now = SystemClock.elapsedRealtime()
        // Nullable rather than a zero stamp: a head unit that starts projecting seconds after boot
        // has an elapsedRealtime small enough to look like a fresh cache entry.
        btMediaLinkCheckedAt?.let { if (now - it < BT_MEDIA_LINK_CACHE_MS) return btMediaLinkCached }
        btMediaLinkCheckedAt = now
        val state = BluetoothHelper.a2dpMediaLinkState(context)
        if (state != btMediaLinkCached) {
            AppLog.i("CommManager: Bluetooth media link state for key routing: $state")
            btMediaLinkCached = state
        }
        return state
    }

    private fun isMediaKey(code: Int): Boolean {
        return code == KeyEvent.KEYCODE_MEDIA_NEXT ||
               code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
               code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
               code == KeyEvent.KEYCODE_MEDIA_PLAY ||
               code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
               code == KeyEvent.KEYCODE_MEDIA_STOP ||
               code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
               code == KeyEvent.KEYCODE_MEDIA_REWIND
    }

    /**
     * [Legacy] Internal transport send. Use sendKey() for physical button inputs.
     * @deprecated Use sendKey(keyCode, isPress) for unified remapping and debouncing.
     */
    fun send(keyCode: Int, isPress: Boolean) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            _transport?.send(keyCode, isPress)
        }
    }

    /** Sends a sensor event (e.g. driving status, night mode) to the phone. */
    fun send(sensor: SensorEvent) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            _transport?.send(sensor)
        }
    }

    /** Sends a raw [AapMessage] (e.g. touch event, video focus request) to the phone. */
    fun send(message: AapMessage) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            _transport?.send(message)
        }
    }

    fun sendToggleVoiceAssistant() {
        if (_connectionState.value != ConnectionState.TransportStarted)
            return

        val transport = _transport ?: return

        // close
        if (transport.isAssistantActive) {
            sendKey(KeyEvent.KEYCODE_BACK, true, null, "assistant")
            sendKey(KeyEvent.KEYCODE_BACK, false, null, "assistant")
            loseFocus() // otherwise button stays marked

        // open
        } else {
            sendKey(KeyEvent.KEYCODE_SEARCH, false, null, "assistant") // up/down must be reversed
            sendKey(KeyEvent.KEYCODE_SEARCH, true, null, "assistant")
        }
    }

    fun loseFocus() {
        val transport = _transport ?: return
        val ts = SystemClock.elapsedRealtime()

        transport.send(TouchEvent(
            ts,
            PointerAction.TOUCH_ACTION_DOWN,
            0,
            mutableListOf(Triple(0, 0, 0))))
    }

    fun sendUpdateUiConfigRequest(left: Int, top: Int, right: Int, bottom: Int) {
        val request = com.andrerinas.openheadunit.aap.protocol.messages.UpdateUiConfigRequest(left, top, right, bottom)
        AppLog.i("[UI_DEBUG_FIX] TX UpdateUiConfigRequest: L=$left T=$top R=$right B=$bottom")
        send(request)
        // Always sends VideoFocusNotification(PROJECTED, unsolicited=true) after
        // updating the UI config. This triggers a keyframe from the phone.
        send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain = true, unsolicited = true))
    }

    fun updateAudioGains() {
        _transport?.aapAudio?.updateGains()
    }

    fun restartAudio() {
        _transport?.aapAudio?.restartAudio()
    }

    // -----------------------------------------------------------------------------------------
    // Disconnect
    // -----------------------------------------------------------------------------------------

    /**
     * Initiates a disconnect.
     *
     * Sets state to [ConnectionState.Disconnected] synchronously so callers see the change
     * immediately, then schedules async cleanup via [doDisconnect]. A ByeByeRequest is sent
     * to the phone before closing the connection.
     */
    fun disconnect(sendByeBye: Boolean = true, isUserExit: Boolean = true) {
        if (_connectionState.value is ConnectionState.Disconnected) return

        HeadUnitScreenConfig.unlockResolution()

        _connectionState.value = ConnectionState.Disconnected(isUserExit = isUserExit)
        if (isUserExit) {
            _transport?.wasUserExit = true
        }
        _disconnectJob = _scope.launch { doDisconnect(sendByeBye) }
        if (settings.killOnDisconnect) {
            context.sendBroadcast(android.content.Intent("com.andrerinas.openheadunit.ACTION_FINISH_ACTIVITIES").apply {
                setPackage(context.packageName)
            })
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Stop the foreground service first to remove the notification
                val stopIntent = android.content.Intent(context, com.andrerinas.openheadunit.aap.AapService::class.java).apply {
                    action = com.andrerinas.openheadunit.aap.AapService.ACTION_STOP_SERVICE
                }
                com.andrerinas.openheadunit.aap.AapService.killProcessOnDestroy = true
                context.stopService(stopIntent)
                // Finish all tasks (API 21+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.appTasks.forEach { it.finishAndRemoveTask() }
                }
            }, 500)
        }
    }

    /**
     * Suspends until the most recently launched [doDisconnect] coroutine (started by
     * [disconnect]) has finished — i.e. the ByeByeRequest has actually been sent and the
     * physical connection actually closed, not just that [connectionState] flipped.
     * Mirrors the `_disconnectJob?.join()` idiom already used internally by the `connect(...)`
     * overloads (e.g. line 192).
     */
    suspend fun awaitDisconnectComplete() {
        _disconnectJob?.join()
    }

    /**
     * Tears down the transport and physical connection.
     *
     * **Null-first pattern**: [_transport] and [_connection] are captured and nulled at the
     * very start. This prevents re-entrant double-cleanup: `AapTransport.stop()` fires `onQuit`
     * → [transportedQuited] → a second [doDisconnect] call — which now finds both fields null
     * and exits cleanly.
     *
     * @param sendByeBye `true` (default) when the user initiates the disconnect — calls
     *   `AapTransport.stop()`, which sends a `ByeByeRequest` to the phone and waits ~150 ms
     *   for acknowledgement. `false` when the transport self-quit (read error, socket timeout):
     *   the connection is already dead, so `AapTransport.quit()` is called directly to skip
     *   the send and the sleep.
     */
    private fun doDisconnect(sendByeBye: Boolean = true) {
        // Capture and null out immediately to prevent a second doDisconnect() call
        // (from transportedQuited firing onQuit during stop()) from double-stopping.
        val transport = _transport
        val connection = _connection
        _transport = null
        _connection = null
        keyStates.clear()
        btMediaLinkCached = null
        btMediaLinkCheckedAt = null
        // Counts frames over the whole session rather than reading lastFrameRenderedMs, which the
        // decoder zeroes every time the projection surface goes away — leaving projection before
        // disconnecting is normal, and would otherwise make every such session look starved.
        // Self-guarding against the re-entrant second call described above: the flag is consumed
        // here, so the second pass sees a session that never reached the handshake and counts
        // nothing.
        noteSessionEnded(renderedAnyFrame = videoDecoder.framesRenderedThisSession > 0L)
        try {
            // Only send ByeByeRequest when we are initiating the disconnect (e.g. user pressed
            // disconnect). When the transport self-quit (read error, soTimeout), the connection
            // is already dead — skip the send and the 150 ms sleep inside stop().
            if (sendByeBye) transport?.stop() else transport?.quit()

            // Explicitly stop and release decoders to prevent MediaCodec finalize() timeouts
            videoDecoder.stop("CommManager: doDisconnect")
            audioDecoder.stop()

            connection?.disconnect()
        } catch (e: Exception) {
            AppLog.e("doDisconnect error: ${e.message}")
        } finally {
            if (_connectionState.value !is ConnectionState.Disconnected) {
                _connectionState.value = ConnectionState.Disconnected()
            }
        }
    }

    /**
     * Counts a finished session against [VideoStarvationPolicy] and, on a long enough run of
     * sessions that carried no video at all, says what that means and what to do about it.
     *
     * The phone gives no reason we can see — it closes the socket and our read reports a plain EOF
     * — so without this the log shows nothing but a healthy connection repeating forever.
     */
    private fun noteSessionEnded(renderedAnyFrame: Boolean) {
        val reachedHandshake = sessionReachedHandshake
        sessionReachedHandshake = false
        starvedSessionStreak = VideoStarvationPolicy.nextStreak(
            starvedSessionStreak, reachedHandshake, renderedAnyFrame
        )
        if (VideoStarvationPolicy.shouldAdvise(starvedSessionStreak)) {
            AppLog.w(
                "CommManager: $starvedSessionStreak sessions in a row ended without a single video " +
                    "frame arriving. The phone is connecting and then giving up on the video stream, " +
                    "which is what a WiFi link too slow to carry it looks like. Measured on a 2.4 GHz " +
                    "access point at 1080p/60, where the same link held 800x480/30 indefinitely: move " +
                    "the access point to 5 GHz, or lower the resolution and frame rate in Video settings."
            )
        }
    }

    /**
     * Performs a final disconnect and cancels the internal coroutine scope.
     *
     * Call this when the owning component (e.g. the foreground service) is destroyed.
     * After [destroy], the CommManager instance must not be used again.
     */
    fun destroy() {
        doDisconnect()
        _scope.cancel()
    }
}
