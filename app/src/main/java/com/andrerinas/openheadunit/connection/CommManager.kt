package com.andrerinas.openheadunit.connection

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.aap.AapSslContext
import com.andrerinas.openheadunit.aap.AaPresentationSession
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.NarrowBandProfilePolicy
import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.ProjectionHealthPolicy
import com.andrerinas.openheadunit.utils.ProjectionHealthRecorder
import com.andrerinas.openheadunit.utils.ProjectionHealthStore
import com.andrerinas.openheadunit.utils.ProjectionSessionSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
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
import com.andrerinas.openheadunit.connection.projection.AbstractUsbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.connection.projection.LibusbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection
import com.andrerinas.openheadunit.connection.usb.UsbRecoveryDevice
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbySocket

/**
 * Central connection and transport lifecycle manager.
 *
 * CommManager owns the full lifecycle of both the physical connection ([com.andrerinas.openheadunit.connection.projection.ProjectionConnection])
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

    enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART, PROJECTION_UNRAISED }

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
        // Each instance ends a distinct attempt. StateFlow must deliver it even when
        // the flags match the previous end and every intervening live state was skipped.
        class Disconnected(
            val isClean: Boolean = false,
            val isUserExit: Boolean = false,
            val reason: DisconnectReason = DisconnectReason.CONNECTION_ENDED,
            // Only outgoing IP connections are dialled again; an accepted socket's port is ephemeral.
            val restartEndpoint: Pair<String, Int>? = null,
            val settingsRestartUntilMs: Long = 0L,
            // Preserve the retiring route even when StateFlow skips every live phase and
            // cleanup has already cleared the physical connection before the observer runs.
            val wasLoopbackSession: Boolean = false,
            // Captured by the connection owner; collectors may miss Connected entirely.
            val hadPhysicalConnection: Boolean = false,
            // Only a failed physical USB open may inherit Save. Ordinary link failures and
            // newer sessions must not grant its retry loop permission to replace their state.
            val settingsRetryOwner: Disconnected? = null,
            // The connectionAttempt this terminal ended, read under transportLifecycleLock with
            // the publication. A launcher armed while that attempt was still Connecting uses it
            // to recognise the attempt's later end as older than the arm (see rearmSnapshot).
            internal val endedAttempt: Any? = null,
        ) : ConnectionState() {
            override fun toString() = "Disconnected(isClean=$isClean, isUserExit=$isUserExit, " +
                "reason=$reason, restartEndpoint=$restartEndpoint, settingsRestartUntilMs=$settingsRestartUntilMs, " +
                "wasLoopbackSession=$wasLoopbackSession, hadPhysicalConnection=$hadPhysicalConnection)"

            val isSettingsRestart get() = reason == DisconnectReason.SETTINGS_RESTART

            // The projection screen never came up twice: no automatic reconnect follows this end.
            val holdsRecovery get() = reason == DisconnectReason.PROJECTION_UNRAISED

            // Created with Save, before asynchronous teardown. A later manual Self launch
            // revokes this permission even while it is still waiting to dial our listener.
            @Volatile private var settingsRestartCancelled = false
            private var settingsLaunch: Job? = null

            internal fun acceptsSettingsRestart(current: ConnectionState): Boolean =
                (current === this || (current is Disconnected && current.settingsRetryOwner === this)) &&
                    isSettingsRestart && !settingsRestartCancelled

            internal val isSettingsRestartCancelled get() = settingsRestartCancelled

            internal fun cancelSettingsRestart() {
                val launch = synchronized(this) {
                    settingsRestartCancelled = true
                    settingsLaunch.also { settingsLaunch = null }
                }
                launch?.cancel()
            }

            /** Save owns queued launch/USB work until cancellation or another connection takes over. */
            internal fun trackSettingsLaunch(job: Job) {
                val cancelled = synchronized(this) {
                    if (settingsRestartCancelled) true else { settingsLaunch = job; false }
                }
                job.invokeOnCompletion {
                    synchronized(this) { if (settingsLaunch === job) settingsLaunch = null }
                }
                if (cancelled) job.cancel()
            }

            @Volatile private var settingsWakeReleased = false

            internal fun releaseSettingsWake() { settingsWakeReleased = true }

            fun holdsSettingsWake(nowMs: Long): Boolean =
                isSettingsRestart && !settingsRestartCancelled && !settingsWakeReleased && nowMs < settingsRestartUntilMs
        }

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
     *  failing child from cancelling the rest.
     *
     *  A var, because [destroy] cancels it and this object outlives the service that destroyed it:
     *  every disconnect path launches here, so a scope left cancelled makes them all silent no-ops.
     */
    private var _scope = newDisconnectScope()

    private fun newDisconnectScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cached answer of the Bluetooth media probe used by media-key routing, and when it was taken. */
    private var btMediaLinkCached: Boolean? = null
    private var btMediaLinkCheckedAt: Long? = null
    private val BT_MEDIA_LINK_CACHE_MS = 2_000L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())

    /** Callback for audio focus state changes (isPlaying). Set by AapService. */
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null

    /** Latest now-playing metadata, delivered on Main with its owning session. Set by AapService. */
    var onAaMediaMetadata: ((MediaPlayback.MediaMetaData, AaPresentationSession) -> Unit)? = null
    /** Merged playback status/position, delivered on Main with its owning session. */
    var onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus, AaPresentationSession) -> Unit)? = null
    /** Runs on Main after the retired transport's final claimed display callback. */
    var onAaPresentationClosed: ((AaPresentationSession) -> Unit)? = null

    // Creation/publication, saved audio settings and the first disconnect decision share one
    // boundary. Network handshake and final connection teardown run outside this lock.
    private val transportLifecycleLock = Any()
    private val appExitPolicy = AppExitPolicy()
    private val appExitDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    private val connectionIoDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val appExitHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAppExit: Runnable? = null
    private var serviceExitFinished: CompletableDeferred<Unit>? = null
    private val appEntryScope = CoroutineScope(SupervisorJob() + appExitDispatcher)

    internal fun appOwnershipRevision(): Long = synchronized(transportLifecycleLock) { appExitPolicy.revision }

    /** A reopened idle home owns the app too; connection state alone cannot revoke an exit. */
    internal fun onAppRelaunched(): Long = synchronized(transportLifecycleLock) {
        appExitPolicy.supersede().also { cancelAppExitCallback() }
    }

    internal fun acceptAppResume(expectedRevision: Long): Long? = synchronized(transportLifecycleLock) {
        appExitPolicy.acceptResume(expectedRevision)?.also { cancelAppExitCallback() }
    }

    internal fun isAppRunOwner(owner: Long): Boolean = synchronized(transportLifecycleLock) {
        appExitPolicy.isRunOwner(owner)
    }

    /** The gate is physical only; every continuation rechecks its logical owner after waiting. */
    internal fun restoreAppForEntry(owner: Long) {
        appEntryScope.launch {
            while (isAppRunOwner(owner)) {
                val ending = synchronized(transportLifecycleLock) { serviceExitFinished }
                if (ending != null) { ending.await(); continue }
                if (!isAppRunOwner(owner)) return@launch
                if (com.andrerinas.openheadunit.aap.AapService.instance == null) {
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(context,
                            com.andrerinas.openheadunit.aap.AapService.entryIntent(context, owner))
                    } catch (failure: Exception) {
                        AppLog.e("CommManager: failed to restore service for app entry: ${failure.message}", failure)
                    }
                }
                return@launch
            }
        }
    }

    private fun appConnectionTicket(): Long = synchronized(transportLifecycleLock) { appExitPolicy.stopEpoch }

    private fun cancelAppExitCallback() {
        pendingAppExit?.let { appExitHandler.removeCallbacks(it) }
        pendingAppExit = null
    }

    private fun acceptAppConnection(ticket: Long, acquireOwnership: Boolean = false): Boolean {
        if (!appExitPolicy.acceptsConnection(ticket)) return false
        if (acquireOwnership) appExitPolicy.supersede()
        cancelAppExitCallback()
        return true
    }

    /** Called under the lifecycle lock; retire only this cancelled attempt, never its replacement. */
    private fun stoppedConnectionAttempt(ticket: Long, attempt: Any): Boolean {
        if (appExitPolicy.stopEpoch == ticket) return false
        if (connectionAttempt === attempt && !disconnectRequested) {
            disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
        }
        return true
    }

    // stopSelf cannot retract an onDestroy already queued by Android. Do not publish a new
    // attempt into that service's cleanup; wait outside the lifecycle lock for its completion.
    private suspend fun publishConnectionAttempt(
        ticket: Long,
        action: () -> Pair<Any, ProjectionConnection?>?,
    ): Pair<Any, ProjectionConnection?>? {
        // Finish receivers also run on Main, including broadcasts delivered after destruction.
        // Keep ownership transfers on that dispatcher; native I/O remains on the caller.
        var published: Pair<Any, ProjectionConnection?>? = null
        try {
            while (true) {
                var ending: CompletableDeferred<Unit>? = null
                var retirement: Job? = null
                val attempted = withContext(appExitDispatcher) {
                    synchronized(transportLifecycleLock) {
                        if (appExitPolicy.stopEpoch != ticket) return@withContext true
                        ending = serviceExitFinished
                        retirement = _disconnectJob?.takeUnless { it.isCompleted }
                        if (ending == null && retirement == null) {
                            published = action()
                            true
                        } else false
                    }
                }
                if (attempted) return published
                // Cleanup can be queued after our initial join but before this Main turn.
                // Await actual teardown before transferring resources into its capture window.
                if (ending != null) {
                    checkNotNull(ending).await()
                } else checkNotNull(retirement).join()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Main may publish Connecting before cancellation wins the handback to IO.
            // The publication loop keeps newer attempts behind this attempt's queued cleanup.
            published?.let { (attempt, previous) ->
                synchronized(transportLifecycleLock) {
                    if (connectionAttempt === attempt && !disconnectRequested) {
                        disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
                    }
                }
                try { previous?.disconnect() } catch (failure: Exception) {
                    AppLog.w("CommManager: displaced connection cleanup failed: ${failure.message}")
                }
            }
            throw cancelled
        }
    }

    internal fun ownsAppExit(id: Long): Boolean = synchronized(transportLifecycleLock) {
        appExitPolicy.owns(id, settings.killOnDisconnect)
    }

    internal fun appExitIsUserStop(request: AppExitPolicy.Request): Boolean = synchronized(transportLifecycleLock) {
        appExitPolicy.userStopAtDestroy(request)
    }

    internal fun requestExplicitAppExit(killProcess: Boolean): AppExitPolicy.Request {
        val request = synchronized(transportLifecycleLock) {
            appExitPolicy.request(killProcess, userStop = true, requiresKillOnDisconnect = false).also {
                cancelAppExitCallback()
                usbRecoveryOwner = Any()
                cancelPendingSettingsRestart()
                dropOwedScans()
            }
        }
        // Retained OS ownership can outlive the service. Never call native/Binder under our lock.
        com.andrerinas.openheadunit.connection.wifi.direct.RetainedNativeGroup.requestStop {
            ownsAppExit(request.id)
        }
        return request
    }

    internal fun acceptedAppExit(id: Long): AppExitPolicy.Request? = synchronized(transportLifecycleLock) {
        appExitPolicy.requestFor(id)
    }

    /** Returns the physical A, including when this fresh B joins its existing teardown. */
    internal fun commitAppExit(request: AppExitPolicy.Request): AppExitPolicy.Request? = synchronized(transportLifecycleLock) {
        val physical = appExitPolicy.commit(request, settings.killOnDisconnect) ?: return@synchronized null
        cancelAppExitCallback()
        if (serviceExitFinished == null) serviceExitFinished = CompletableDeferred()
        physical
    }

    internal fun completeAppExit(request: AppExitPolicy.Request, allowProcessExit: Boolean = true): AppExitPolicy.Completion {
        val (completion, finished) = synchronized(transportLifecycleLock) {
            val completion = appExitPolicy.destroyed(request, settings.killOnDisconnect && allowProcessExit)
            val finished = if (completion.effect != AppExitPolicy.Effect.NONE) {
                serviceExitFinished.also { serviceExitFinished = null }
            } else null
            completion to finished
        }
        finished?.complete(Unit)
        return completion
    }

    internal fun finishCurrentAppExitTasks() {
        val request = synchronized(transportLifecycleLock) {
            appExitPolicy.requestFor(appExitPolicy.revision)?.takeIf { it.killProcess }
        } ?: return
        finishAppExitTasks(request)
    }

    internal fun broadcastAppExit(request: AppExitPolicy.Request) {
        if (!ownsAppExit(request.id)) return
        context.sendBroadcast(android.content.Intent(AppExitPolicy.ACTION_FINISH_ACTIVITIES).apply {
            setPackage(context.packageName)
            putExtra(AppExitPolicy.EXTRA_EXIT_OWNER, request.id)
        })
    }

    internal fun finishAppExitTasks(request: AppExitPolicy.Request) {
        if (!ownsAppExit(request.id)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.appTasks.forEach { it.finishAndRemoveTask() }
        }
    }

    private fun scheduleAppExit(userStop: Boolean) {
        // A terminal may be published on IO. Observe its revision there, but accept the
        // logical decision on Main; a newer UI command must win over that queued terminal.
        val observedRevision = appExitPolicy.revision
        appEntryScope.launch {
            synchronized(transportLifecycleLock) {
                if (appExitPolicy.revision != observedRevision || !settings.killOnDisconnect) return@launch
                val request = appExitPolicy.requestAutomatic(userStop) ?: return@launch
                cancelAppExitCallback()
                val callback = Runnable {
                    val physical = commitAppExit(request) ?: return@Runnable
                    broadcastAppExit(request)
                    val service = com.andrerinas.openheadunit.aap.AapService.instance
                    service?.stopForAppExit(physical)
                    finishAppExitTasks(request)
                    if (service == null && physical === request) completeAppExit(physical, allowProcessExit = false)
                }
                pendingAppExit = callback
                appExitHandler.postDelayed(callback, AppExitPolicy.DELAY_MS)
            }
        }
    }

    // The first disconnect chooses app-exit policy until the next physical attempt. Error/UI
    // state changes must not reopen that decision while the same session is being retired.
    private var disconnectRequested = false
    private var connectionAttempt: Any? = null
    // Guarded by transportLifecycleLock and reset for each physical connection attempt.
    private var physicalConnectionReached = false
    /** @Volatile: written on IO thread, read on Main and IO threads. */
    @Volatile private var _transport: AapTransport? = null
    @Volatile private var projectionVideoFocusSuppressed = false
    val isProjectionVideoFocusSuppressed: Boolean
        get() = projectionVideoFocusSuppressed
    val deviceVideoControl = com.andrerinas.openheadunit.device.ProjectionVideoSuppression { suppressed ->
        setProjectionVideoFocusSuppressed(suppressed)
        if (suppressed) {
            send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain = false, unsolicited = false))
        }
    }

    /**
     * `ip:port` of the most recent connection attempt, or `null` when it was not a socket
     * (USB). Lets a caller attribute a handshake failure to the peer it happened with, which
     * matters for the one failure that is a property of that peer rather than of the link —
     * see [ERROR_HANDSHAKE_PEER_SILENT].
     */
    @Volatile var lastAttemptedEndpoint: String? = null
        private set

    /**
     * Consecutive handshakes against one endpoint where the peer accepted the connection and then
     * sent nothing at all. Read by the discovery rescheduler to back off — see
     * [com.andrerinas.openheadunit.connection.UnresponsivePeerPolicy].
     *
     * Counted here rather than from a [ConnectionState.Error] collector because that state is not
     * observable: [connectionState] is a `MutableStateFlow`, so collection is conflated, and
     * [startHandshake] calls [disconnect] with no suspension point after emitting the error — the
     * value has already moved on to `Disconnected` before any collector resumes.
     */
    @Volatile var silentPeerFailures: Int = 0
        private set

    /** The endpoint [silentPeerFailures] is counting; a different one starts its own streak. */
    @Volatile private var silentPeerEndpoint: String? = null
    var onUpdateUiConfigReplyReceived: (() -> Unit)? = null

    /**
     * Called where a failure is discovered, not from a [connectionState] collector.
     * [ConnectionState.Error] never reaches one: the flow is conflated and a synchronous
     * `disconnect()` follows the emit, so the value has moved on before any collector resumes.
     */
    var onSessionFailure: ((reason: String) -> Unit)? = null
    // Written with the connection token under transportLifecycleLock.
    private var outgoingEndpoint: Pair<String, Int>? = null

    /** Carries the attempted enumeration and its recovery interval across delivery to Main. */
    data class UsbHandshakeOutcome(val failure: AapTransport.HandshakeFailure,
        val device: UsbDevice, val recoveryOwner: Any, val identity: UsbRecoveryDevice? = null)

    /**
     * Called after a USB handshake ends, [AapTransport.HandshakeFailure.NONE] on success, and after
     * `disconnect()` on failure. The `Error` state never reaches a collector.
     */
    var onUsbHandshakeEnded: ((UsbHandshakeOutcome) -> Unit)? = null
    @Volatile private var _connection: ProjectionConnection? = null

    /**
     * Tracks the most-recently-launched [doDisconnect] coroutine.
     * [connect] overloads join this job before opening a new connection, ensuring the previous
     * device is fully closed before `openDevice()` is called on it again.
     */
    @Volatile private var _disconnectJob: kotlinx.coroutines.Job? = null

    // Whether the session now ending ever completed its handshake, and how many sessions in a row
    // have completed one and then carried no video at all. See VideoStarvationPolicy.
    @Volatile private var sessionReachedHandshake = false
    @Volatile private var sessionClaim: ConnectionArbiter.Claim? = null
    // The claim that set the endpoint travels with it, so a preempted loser cannot clear its successor's.
    @Volatile private var claimedEndpointOwner: Pair<ConnectionArbiter.Claim, String>? = null
    /** The endpoint an attempt holds a claim for, until SSL; null outside that window. */
    private val claimedEndpoint: String? get() = claimedEndpointOwner?.second
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
     * When the phone last sent anything on any AAP channel, or `0` if it has not yet this session.
     *
     * Proof the link is alive, as distinct from proof the picture is moving. See
     * [AapTransport.lastMessageReceivedMs].
     */
    val lastAapMessageMs: Long
        get() = _transport?.lastMessageReceivedMs ?: 0L

    /**
     * `true` while a connection exists **or** one is being set up.
     *
     * [isConnected] deliberately excludes [ConnectionState.Connecting], which is right for the
     * questions it was written for. It is wrong for discovery: a scan started during that window
     * finds the head unit server, hands the socket over, and [connect] refuses it at its own
     * `Connecting` guard and closes it — and that server is wedged by any connection it accepts
     * and nobody follows through. Closing afterwards does not undo it; the damage is done at
     * `accept()`. Ask this before opening a probe, not [isConnected].
     */
    val isBusy: Boolean
        get() = isConnected || connectionState.value is ConnectionState.Connecting

    /**
     * `true` when the running session rides a socket rather than USB.
     *
     * The stored wireless mode says nothing about this — a USB session can be live while
     * `wifiConnectionMode` names a WiFi route — so anything reacting to the WiFi radio going away
     * has to ask the session, not the settings.
     */
    val isWirelessSession: Boolean
        get() = _connection is SocketProjectionConnection

    /**
     * Identity of an opened, accepted radio socket. The launcher can remain active while an
     * IP/deep-link client dials out, so launcher state alone cannot authorize scan control.
     * Read the socket, direction and attempt together; use the attempt as the session key so
     * a conflated disconnect/reconnect still cancels the previous FYT startup.
     */
    internal val acceptedWirelessSession: Any?
        get() = synchronized(transportLifecycleLock) {
            val socket = _connection as? SocketProjectionConnection
            if (socket != null && !socket.isLoopbackPeer && !socket.isSingleMessage &&
                outgoingEndpoint == null && physicalConnectionReached && !disconnectRequested)
                connectionAttempt else null
        }

    /**
     * Vendor A2DP suppression belongs only to an accepted session that actually receives audio.
     * Read the immutable transport config with its identity: Save may already hold the next
     * session's preference while the old transport is still retiring.
     */
    internal val acceptedWirelessAudioSession: Any?
        get() = synchronized(transportLifecycleLock) {
            val state = _connectionState.value
            if ((state is ConnectionState.HandshakeComplete || state is ConnectionState.TransportStarted) &&
                _transport?.aapAudio?.sessionConfig?.enabled == true) acceptedWirelessSession else null
        }

    /** `true` when this session, or the attempt in flight, runs over a USB cable. */
    val isUsbSession: Boolean
        get() = _connection is AbstractUsbProjectionConnection

    /**
     * `true` when this session's peer is this device itself — Self Mode, on either of its routes.
     *
     * Asked instead of `SelfLauncherManager.isActive`, which is set before the launchers run and
     * outlives a launch that never connected. A socket session alone cannot answer it: Self Mode
     * is one too, and only the endpoint separates them.
     */
    val isLoopbackSession: Boolean
        get() = (_connection as? SocketProjectionConnection)?.isLoopbackPeer == true

    /**
     * Returns `true` if the current USB connection is to [device].
     * Used by AapService to decide whether a USB detach event should trigger a disconnect.
     */
    fun isConnectedToUsbDevice(device: UsbDevice): Boolean =
        (_connection as? AbstractUsbProjectionConnection)?.isDeviceRunning(device) == true

    // -----------------------------------------------------------------------------------------
    // connect() overloads — one for each transport type
    // -----------------------------------------------------------------------------------------

    /**
     * Opens a USB accessory connection to [device].
     *
     * On success emits [ConnectionState.Connected] and persists the device as the last-used
     * connection so it can be auto-reconnected on the next launch.
     */
    suspend fun connect(
        device: UsbDevice,
        tier: ConnectionPriorityPolicy.Tier = ConnectionPriorityPolicy.Tier.USB,
        expectedState: ConnectionState.Disconnected? = null,
    ) {
        val ticket = appConnectionTicket()
        withContext(connectionIoDispatcher) {
            synchronized(transportLifecycleLock) {
                if (appExitPolicy.stopEpoch != ticket ||
                    (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value))) return@withContext
            }
            val description = "USB ${UsbDeviceCompat.getUniqueName(device)}"
            val claim = synchronized(transportLifecycleLock) {
                if (appExitPolicy.stopEpoch != ticket) null else claimUsb(tier, description, expectedState)
            } ?: return@withContext
            try { connectUsb(device, ticket, expectedState, appClaim = claim) } finally { releaseClaim(claim) }
        }
    }

    /** A recovery retry cannot borrow the authority of a later Save, Exit or completed session. */
    internal suspend fun reconnectUsb(device: UsbDevice, recoveryOwner: Any,
        recoveryClaim: ConnectionArbiter.Claim,
        admission: ConnectionAdmission = ConnectionAdmission.UNRESTRICTED) {
        val ticket = appConnectionTicket()
        withContext(connectionIoDispatcher) {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val claim = synchronized(transportLifecycleLock) {
                job?.ensureActive()
                if (appExitPolicy.stopEpoch != ticket) return@withContext
                if (usbRecoveryOwner !== recoveryOwner) return@withContext
                if (_connectionState.value !is ConnectionState.Disconnected &&
                    _connectionState.value !is ConnectionState.Error) return@withContext
                var next: ConnectionArbiter.Claim? = null
                admission.run {
                    next = ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USB, ConnectionPriorityPolicy.Owner.USB,
                        "USB recovery of ${UsbDeviceCompat.getUniqueName(device)}", expectedHolder = recoveryClaim)
                }
                next
            } ?: return@withContext
            attemptUserRequested = false
            try { connectUsb(device, ticket, recoveryOwner = recoveryOwner, recoveryClaim = claim, recoveryAdmission = admission) } finally { releaseClaim(claim) }
        }
    }

    internal fun claimUsb(
        tier: ConnectionPriorityPolicy.Tier,
        description: String,
        expectedState: ConnectionState.Disconnected?,
    ): ConnectionArbiter.Claim? = synchronized(transportLifecycleLock) {
        // A later manual launch can revoke Save without changing the Disconnected object.
        // Claim admission and cancellation must agree before USB can preempt that launch.
        if (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value)) null
        else ConnectionArbiter.claim(tier, ConnectionPriorityPolicy.Owner.USB, description)
    }?.also { attemptUserRequested = tier == ConnectionPriorityPolicy.Tier.USER }

    /** Whether the user asked for the attempt in flight; the raise deadline does not count those. */
    @Volatile var attemptUserRequested = false
        private set

    // Failed physical attempts and permission errors belong to the same recovery interval.
    // End it at the producer, not the conflated observer: a complete session can start and end
    // before the service receives either event. Explicit stop and Save also revoke old checks.
    private var usbRecoveryOwner: Any = Any()

    internal fun usbRecheckOwner(ended: ConnectionState.Disconnected): Any? =
        synchronized(transportLifecycleLock) {
            usbRecoveryOwner.takeIf {
                _connectionState.value === ended && !ended.isUserExit && !ended.isSettingsRestart && !ended.holdsRecovery
            }
        }

    /** Attach/re-enumeration scans can be owed while the current attempt is still running. */
    internal fun usbRecheckOwner(): Any = synchronized(transportLifecycleLock) { usbRecoveryOwner }

    internal fun ownsUsbRecheck(owner: Any): Boolean =
        synchronized(transportLifecycleLock) { usbRecoveryOwner === owner }

    /** Returns true while a connection attempt owns the scan slot; another owner owes USB a retry. */
    internal fun deferUsbCheckForConnectionAttempt(): Boolean = synchronized(transportLifecycleLock) {
        when (_connectionState.value) {
            ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.StartingTransport -> {
                // An open socket is not a completed handshake. Register the debt under the same
                // lock that settles the session claim, so handshake completion cannot pass between
                // the state check and holdUsbCheck(). The arbiter returns it after a failed attempt;
                // a successful session keeps it parked until that session ends. A USB-owned
                // attempt already serves this scan and needs no second debt to itself.
                ConnectionArbiter.holdUsbCheck()
                true
            }
            else -> false
        }
    }

    @Volatile private var settingsUsbRestartInFlight: ConnectionState.Disconnected? = null

    // The Save of the current USB attempt, kept until SSL so an end after the open still carries it.
    // Guarded by transportLifecycleLock.
    private var usbSaveOwner: ConnectionState.Disconnected? = null

    /** A re-enumerated accessory still belongs to Save, but only inside its original window. */
    internal fun pendingUsbSettingsRestart(): ConnectionState.Disconnected? {
        val current = _connectionState.value as? ConnectionState.Disconnected ?: return null
        val owner = current.settingsRetryOwner ?: current
        return owner.takeIf {
            settings.lastConnectionType == Settings.CONNECTION_TYPE_USB &&
                it.acceptsSettingsRestart(current) && SystemClock.elapsedRealtime() < it.settingsRestartUntilMs
        }
    }

    private suspend fun connectUsb(device: UsbDevice, ticket: Long, expectedState: ConnectionState.Disconnected? = null,
        recoveryOwner: Any? = null, recoveryClaim: ConnectionArbiter.Claim? = null,
        recoveryAdmission: ConnectionAdmission = ConnectionAdmission.UNRESTRICTED,
        appClaim: ConnectionArbiter.Claim? = null) {
        if (recoveryOwner != null && !ownsUsbRecheck(recoveryOwner)) return
        if (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value)) return
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return


        lastAttemptedEndpoint = null

        val usbManager = UsbDeviceCompat.usbManager(context)
        if (usbManager == null || !usbManager.hasPermission(device)) {
            // USB re-enumeration revokes the old UsbDevice's permission. Keep Save's
            // terminal token so a fresh attach/permission reply can continue this recovery.
            if (expectedState != null || recoveryOwner != null) return
            onSessionFailure?.invoke("connect_failed")
            _connectionState.emit(ConnectionState.Error("USB permission not granted for device"))
            return
        }
        // Permission rejection is not an accepted attempt. A valid held claim, however,
        // already owns the app while the previous connection is still being torn down.
        if (appClaim != null && !acceptAppConnectionClaim(appClaim, ticket, expectedState)) return

        // Wait for any in-progress cleanup to finish before opening the USB device.
        // Without this, openDevice() on the same hardware can return null because the previous
        // UsbDeviceConnection hasn't been close()d yet.
        _disconnectJob?.join()

        // A same-path detach/re-attach can preserve every descriptor. Carry the producer's
        // detach evidence across dispatcher handoff and teardown, not only the device list.
        if (recoveryOwner != null && !recoveryAdmission.run {}) return

        // Teardown can suspend while USB re-enumerates. Recovery opens the latest matching
        // bus object, and never turns a lost permission into an error for a newer connection.
        val currentDevice = if (recoveryOwner == null) device else {
            val current = if (recoveryAdmission is UsbRecoveryAdmission) recoveryAdmission.resolve(usbManager) ?: return
                else usbManager.deviceList[device.deviceName] ?: return
            if (current.vendorId != device.vendorId || current.productId != device.productId ||
                !UsbDeviceCompat.isInAccessoryMode(current) || !UsbDeviceCompat.isConnectable(context, current) ||
                !usbManager.hasPermission(current)) return
            current
        }

        // Call only under transportLifecycleLock (lifecycle -> recovery identity/detach admission -> arbiter).
        // A same-owner USB claim can be replaced without a preempt callback. Keep its
        // check and each state transfer together; a separate holds() check is not enough.
        fun publishIfOwned(action: () -> Unit): Boolean {
            if (recoveryOwner == null) { action(); return true }
            if (usbRecoveryOwner !== recoveryOwner || recoveryClaim == null) return false
            var published = false
            recoveryAdmission.run { published = ConnectionArbiter.runIfHeld(recoveryClaim, action) }
            return published
        }

        var conn: ProjectionConnection? = null
        val (attempt, previous) = publishConnectionAttempt(ticket) {
            // Retirement can suspend after admission. Recheck the same Save permission at
            // publication, before opening hardware or replacing another connection's state.
            if (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value)) return@publishConnectionAttempt null
            if (recoveryOwner != null && _connectionState.value !is ConnectionState.Disconnected &&
                _connectionState.value !is ConnectionState.Error) return@publishConnectionAttempt null
            if (!appExitPolicy.acceptsConnection(ticket)) return@publishConnectionAttempt null
            val token = Any()
            var previous: ProjectionConnection? = null
            if (!publishIfOwned {
                check(acceptAppConnection(ticket, acquireOwnership = appClaim == null))
                settingsUsbRestartInFlight = expectedState
                usbSaveOwner = expectedState
                outgoingEndpoint = null
                connectionAttempt = token
                physicalConnectionReached = false
                disconnectRequested = false
                _connectionState.value = ConnectionState.Connecting
                previous = _connection
                _connection = null
            }) return@publishConnectionAttempt null
            token to previous
        } ?: return
        try {
            previous?.disconnect()
            conn = if (settings.useLibusb) {
                LibusbProjectionConnection(usbManager, currentDevice)
            } else {
                StandardUsbProjectionConnection(usbManager, currentDevice)
            }
            val candidate = conn
            val registered = synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return@synchronized false
                if (connectionAttempt !== attempt || disconnectRequested) false
                else {
                    val accepted = publishIfOwned { _connection = candidate }
                    // Cleanup may invoke callbacks; it must stay outside the arbiter monitor.
                    if (!accepted) disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
                    accepted
                }
            }
            if (!registered) return

            val opened = candidate.connect()
            // A targeted fallback may wait inside openDevice while the bus changes again.
            val targetStillCurrent = recoveryAdmission !is UsbRecoveryAdmission || recoveryAdmission.resolve(usbManager) != null
            synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return@synchronized
                if (connectionAttempt !== attempt || disconnectRequested || _connection !== candidate) {
                    // The cancelled candidate is closed below, outside the lifecycle monitor.
                    return@synchronized
                }
                // Settings persistence can run callbacks and must not hold the arbiter monitor.
                // Recheck ownership atomically with publication after it returns.
                if (opened && targetStillCurrent && expectedState?.isSettingsRestartCancelled != true) {
                    settings.saveLastConnection(type = Settings.CONNECTION_TYPE_USB, usbDevice = UsbDeviceCompat.getUniqueName(currentDevice))
                    if (publishIfOwned {
                        physicalConnectionReached = true
                        _connectionState.value = ConnectionState.Connected
                    }) return@synchronized
                }
                disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false, settingsRetryOwner = expectedState)
            }
        } catch (e: Exception) {
            synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return
                if (connectionAttempt !== attempt || disconnectRequested) return
                val published = publishIfOwned {
                    _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                }
                if (published) onSessionFailure?.invoke("connect_failed")
                // A thrown open failure has the same retry policy as connect() returning false.
                // No user chose Exit, and an unopened session must not close the service either.
                disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false, settingsRetryOwner = expectedState)
            }
        } finally {
            val retired = synchronized(transportLifecycleLock) {
                if (connectionAttempt === attempt && settingsUsbRestartInFlight === expectedState) settingsUsbRestartInFlight = null
                connectionAttempt !== attempt || disconnectRequested || _connection !== conn
            }
            if (retired) conn?.disconnect()
        }
    }

    /**
     * Wraps an already-connected [Socket] (e.g. accepted by `WirelessServer`) in a
     * [SocketProjectionConnection] and advances to [ConnectionState.Connected].
     *
     * The socket must already be connected; this overload skips the TCP handshake and only
     * sets up the AAP framing layer.
     */
    suspend fun connect(
        socket: Socket,
        tier: ConnectionPriorityPolicy.Tier = ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE,
        restartEndpoint: Pair<String, Int>? = null,
        expectedState: ConnectionState.Disconnected? = null,
        admission: ConnectionAdmission = ConnectionAdmission.UNRESTRICTED,
    ) {
        val ticket = appConnectionTicket()
        connectSocketWithTicket(socket, tier, restartEndpoint, expectedState, admission, ticket)
    }

    /** An IP-held-socket handoff is the same attempt, including its original cancellation ticket. */
    private suspend fun connectSocketWithTicket(
        socket: Socket,
        tier: ConnectionPriorityPolicy.Tier,
        restartEndpoint: Pair<String, Int>?,
        expectedState: ConnectionState.Disconnected?,
        admission: ConnectionAdmission,
        ticket: Long,
    ) {
        var entered = false
        try {
            withContext(connectionIoDispatcher) {
                entered = true
                var claim: ConnectionArbiter.Claim? = null
                // Match publication's lock order: lifecycle, producer, then arbiter.
                synchronized(transportLifecycleLock) {
                    if (appExitPolicy.stopEpoch != ticket) return@synchronized
                    admission.run {
                        if (!socket.isClosed &&
                            (expectedState == null || expectedState.acceptsSettingsRestart(_connectionState.value))) {
                            claim = claimSocket(tier, "the socket from ${socket.inetAddress?.hostAddress}", expectedState, ticket)
                        }
                    }
                }
                val ownedClaim = claim
                if (ownedClaim == null) {
                    HeldServerSocket.abandon(socket)
                    try { socket.close() } catch (_: Exception) {}
                    if (admission !== ConnectionAdmission.UNRESTRICTED) throw ConnectionAdmissionRejectedException()
                    return@withContext
                }
                noteClaimedEndpoint(ownedClaim, socketEndpoint(socket))
                HeldServerSocket.settle(socket)
                var delegated = false
                try {
                    if (admission === ConnectionAdmission.UNRESTRICTED) {
                        if (socket.isClosed || !acceptAppConnectionClaim(ownedClaim, ticket, expectedState)) return@withContext
                        delegated = true
                        connectSocket(socket, restartEndpoint, expectedState, ticket)
                    } else {
                        delegated = true
                        connectAdmittedSocket(socket, admission.withClaim(ownedClaim), restartEndpoint, expectedState, ticket)
                    }
                } finally {
                    if (!delegated) try { socket.close() } catch (_: Exception) {}
                    releaseClaim(ownedClaim)
                }
            }
        } finally {
            // A cancelled IO dispatch never enters the inner ownership frame.
            if (!entered) withContext(kotlinx.coroutines.NonCancellable + connectionIoDispatcher) {
                try { socket.close() } catch (failure: Exception) {
                    AppLog.w("CommManager: unentered socket cleanup failed: ${failure.message}")
                }
            }
        }
    }

    private suspend fun connectAdmittedSocket(
        socket: Socket,
        admission: ConnectionAdmission,
        restartEndpoint: Pair<String, Int>?,
        expectedState: ConnectionState.Disconnected?,
        ticket: Long,
    ) {
        var transferred = false
        var terminal: ConnectionState? = null
        val publication = ConnectionAdmission { action ->
            synchronized(transportLifecycleLock) { admission.run(action) }
        }
        try {
            // Keep preparation local. Only the final transfer joins CommManager's lifecycle;
            // cancellation closes this candidate without disconnecting a replacement session.
            transferred = publication.prepareAndPublish(
                awaitRetirement = {
                    _disconnectJob?.join()
                    synchronized(transportLifecycleLock) { terminal = _connectionState.value }
                },
                create = { SocketProjectionConnection(socket, context) },
                open = { candidate ->
                    if (!candidate.connect()) throw java.io.IOException("Cannot open Nearby tunnel streams")
                },
                publish = { candidate ->
                    // Permission failures can leave Error without owning a connection. It is
                    // reusable like Disconnected, but only while that exact state and both
                    // resource owners remain unchanged throughout candidate preparation.
                    // Cleanup can be queued between the initial join and terminal snapshot;
                    // reject rather than transfer a candidate into that cleanup's capture.
                    if (socket.isClosed || _connectionState.value !== terminal ||
                        (terminal !is ConnectionState.Disconnected && terminal !is ConnectionState.Error) ||
                        _connection != null || _transport != null || !appExitPolicy.acceptsConnection(ticket) ||
                        _disconnectJob?.isCompleted == false ||
                        (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value))) {
                        false
                    } else {
                        check(acceptAppConnection(ticket, acquireOwnership = true))
                        lastAttemptedEndpoint = socketEndpoint(socket)
                        outgoingEndpoint = restartEndpoint
                        connectionAttempt = Any()
                        physicalConnectionReached = true
                        disconnectRequested = false
                        _connection = candidate
                        _connectionState.value = ConnectionState.Connected
                        transferred = true
                        true
                    }
                },
                dispose = { it.disconnect() },
                publicationContext = appExitDispatcher,
            )
            // Closing the candidate alone leaves Nearby's endpoint/pipe bookkeeping alive.
            // Signal rejection back through the awaited handoff so that producer can retire it.
            if (!transferred) throw ConnectionAdmissionRejectedException()
        } finally {
            // Also covers cancellation while waiting, before a wrapper has been allocated.
            if (!transferred) try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun socketEndpoint(socket: Socket): String? =
        socket.inetAddress?.hostAddress?.let { SameEndpointConnectPolicy.endpoint(it, socket.port) }

    private suspend fun connectSocket(
        socket: Socket,
        restartEndpoint: Pair<String, Int>?,
        expectedState: ConnectionState.Disconnected?,
        ticket: Long,
    ) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting) {
            // [BUG_FIX] But close what we are refusing. A socket handed to connect() has no
            // other owner: NetworkDiscovery gives ownership up at this call, and WirelessServer's
            // accept loop closes the sockets it refuses itself, so returning without closing
            // leaves the peer holding a session nobody will ever read from. This guard is the
            // only one of the three that did not, and it is the reachable one — isConnected
            // deliberately excludes Connecting, and WirelessServer checks it from a detached
            // coroutine, so two connections arriving together both get past it.
            //
            // The cost is worst on the head unit server path, where the server binds to one
            // connection for the life of its process and an abandoned one leaves it deaf until
            // the user restarts it by hand.
            AppLog.i("CommManager: Connect already in progress; closing the handed-over socket")
            try { socket.close() } catch (e: Exception) {}
            return
        }

        var conn: ProjectionConnection? = null
        val publication = try {
            _disconnectJob?.join()
            publishConnectionAttempt(ticket) {
                if (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value)) {
                    return@publishConnectionAttempt null
                }
                if (!acceptAppConnection(ticket)) return@publishConnectionAttempt null
                lastAttemptedEndpoint = socketEndpoint(socket)
                outgoingEndpoint = restartEndpoint
                val token = Any()
                connectionAttempt = token
                physicalConnectionReached = false
                usbSaveOwner = null
                disconnectRequested = false
                _connectionState.value = ConnectionState.Connecting
                val previous = _connection
                _connection = null
                token to previous
            }
        } catch (failure: Throwable) {
            try { socket.close() } catch (_: Exception) {}
            throw failure
        }
        val (attempt, previous) = publication ?: run {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        try {
            previous?.disconnect()
            conn = SocketProjectionConnection(socket, context)
            val candidate = conn
            val registered = synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return@synchronized false
                if (connectionAttempt !== attempt || disconnectRequested) false
                else { _connection = candidate; true }
            }
            if (!registered) return

            val opened = candidate.connect()
            synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return@synchronized
                if (connectionAttempt !== attempt || disconnectRequested || _connection !== candidate) {
                    // The cancelled candidate is closed below, outside the lifecycle monitor.
                    return@synchronized
                }
                if (opened) {
                    physicalConnectionReached = true
                    // [FIX] Don't overwrite NEARBY connection type with WIFI + localhost IP (::1)
                    if (socket !is NearbySocket) {
                        settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = socket.inetAddress?.hostAddress ?: "")
                    }
                    _connectionState.value = ConnectionState.Connected
                } else {
                    disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
                }
            }
        } catch (e: Exception) {
            synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return
                if (connectionAttempt !== attempt || disconnectRequested) return
                onSessionFailure?.invoke("connect_failed")
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            }
        } finally {
            val retired = synchronized(transportLifecycleLock) {
                connectionAttempt !== attempt || disconnectRequested || _connection !== conn
            }
            if (retired) conn?.disconnect()
        }
    }

    /**
     * Opens a TCP connection to [ip]:[port] and advances to [ConnectionState.Connected].
     *
     * Used by the manual IP entry flow and the NSD-discovered device list.
     */
    suspend fun connect(
        ip: String,
        port: Int,
        tier: ConnectionPriorityPolicy.Tier = ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE,
        expectedState: ConnectionState.Disconnected? = null,
    ) {
        val ticket = appConnectionTicket()
        withContext(connectionIoDispatcher) {
            // Save may have waited in the IO queue while another session took over. Check before
            // claiming the arbiter, then again under the publication lock after teardown waits.
            synchronized(transportLifecycleLock) {
                if (appExitPolicy.stopEpoch != ticket ||
                    (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value))) return@withContext
            }
            val endpoint = SameEndpointConnectPolicy.endpoint(ip, port)
            val held = if (HeldServerSocket.isHeld(endpoint)) endpoint else null
            if (SameEndpointConnectPolicy.route(held, null, endpoint) == SameEndpointConnectPolicy.Route.ADOPT_HELD) {
                val adopted = synchronized(transportLifecycleLock) {
                    if (appExitPolicy.stopEpoch != ticket) return@withContext
                    HeldServerSocket.take(endpoint)
                }
                adopted?.let {
                    AppLog.i("CommManager: $endpoint adopting the socket discovery already opened")
                    try {
                        connectSocketWithTicket(it, tier, ip to port, expectedState, ConnectionAdmission.UNRESTRICTED, ticket)
                    } catch (e: CancellationException) { HeldServerSocket.abandon(it); throw e }
                    return@withContext
                }
            }
            // The taken mark is read before the claim: discovery records its claim before it settles the mark.
            val inFlight = if (HeldServerSocket.isTaken(endpoint)) endpoint else claimedEndpoint
            if (SameEndpointConnectPolicy.route(null, inFlight, endpoint) == SameEndpointConnectPolicy.Route.JOIN) {
                AppLog.i("CommManager: $endpoint is already connecting; not preempting it")
                return@withContext
            }
            val claim = claimSocket(tier, endpoint, expectedState, ticket) ?: return@withContext
            noteClaimedEndpoint(claim, endpoint)
            try {
                if (acceptAppConnectionClaim(claim, ticket, expectedState)) connectIp(ip, port, expectedState, ticket)
            } finally { releaseClaim(claim) }
        }
    }

    private suspend fun connectIp(ip: String, port: Int, expectedState: ConnectionState.Disconnected?, ticket: Long) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return

        _disconnectJob?.join()

        var conn: ProjectionConnection? = null
        val (attempt, previous) = publishConnectionAttempt(ticket) {
            if (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value)) return@publishConnectionAttempt null
            if (!acceptAppConnection(ticket)) return@publishConnectionAttempt null
            lastAttemptedEndpoint = SameEndpointConnectPolicy.endpoint(ip, port)
            outgoingEndpoint = ip to port
            val token = Any()
            connectionAttempt = token
            physicalConnectionReached = false
            usbSaveOwner = null
            disconnectRequested = false
            _connectionState.value = ConnectionState.Connecting
            val previous = _connection
            _connection = null
            token to previous
        } ?: return
        try {
            previous?.disconnect()
            conn = SocketProjectionConnection(ip, port, context)
            val candidate = conn
            val registered = synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return@synchronized false
                if (connectionAttempt !== attempt || disconnectRequested) false
                else { _connection = candidate; true }
            }
            if (!registered) return

            val opened = candidate.connect()
            synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return@synchronized
                if (connectionAttempt !== attempt || disconnectRequested || _connection !== candidate) {
                    // The cancelled candidate is closed below, outside the lifecycle monitor.
                    return@synchronized
                }
                if (opened) {
                    physicalConnectionReached = true
                    settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = ip)
                    _connectionState.value = ConnectionState.Connected
                } else {
                    disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
                }
            }
        } catch (e: Exception) {
            synchronized(transportLifecycleLock) {
                if (stoppedConnectionAttempt(ticket, attempt)) return
                if (connectionAttempt !== attempt || disconnectRequested) return
                onSessionFailure?.invoke("connect_failed")
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            }
        } finally {
            val retired = synchronized(transportLifecycleLock) {
                connectionAttempt !== attempt || disconnectRequested || _connection !== conn
            }
            if (retired) conn?.disconnect()
        }
    }

    /**
     * What a launcher arm supersedes: the terminal already published, or else the attempt
     * still in progress. One lock read, because IO publishes Connecting and its terminal
     * under this lock while the arm runs on Main; reading state and attempt separately
     * could pair an old terminal with a newer attempt, or miss the attempt between them.
     */
    internal fun rearmSnapshot(): Pair<ConnectionState.Disconnected?, Any?> = synchronized(transportLifecycleLock) {
        val terminal = _connectionState.value as? ConnectionState.Disconnected
        terminal to (if (terminal == null) connectionAttempt else null)
    }

    /** A manual start or stop supersedes Save before it publishes an AAP connection. */
    fun cancelPendingSettingsRestart(): Unit = synchronized(transportLifecycleLock) {
        val terminal = _connectionState.value as? ConnectionState.Disconnected
        (terminal?.settingsRetryOwner ?: terminal)?.cancelSettingsRestart()
        settingsUsbRestartInFlight?.cancelSettingsRestart()
    }

    // An unrestricted, held claim owns an attempt even while previous teardown still runs.
    // Do not wait for Connecting to revoke its predecessor's exit. Restricted candidates
    // transfer only at prepareAndPublish; a refused/preempted claim revokes nothing.
    private suspend fun acceptAppConnectionClaim(
        claim: ConnectionArbiter.Claim,
        ticket: Long,
        expectedState: ConnectionState.Disconnected? = null,
    ): Boolean = withContext(appExitDispatcher) {
        synchronized(transportLifecycleLock) {
            if (appExitPolicy.stopEpoch != ticket) return@synchronized false
            if (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value)) return@synchronized false
            ConnectionArbiter.runIfHeld(claim) {
                appExitPolicy.supersede()
                cancelAppExitCallback()
            }
        }
    }

    private fun claimSocket(
        tier: ConnectionPriorityPolicy.Tier,
        description: String,
        expectedState: ConnectionState.Disconnected?,
        ticket: Long,
    ): ConnectionArbiter.Claim? {
        val claim = synchronized(transportLifecycleLock) {
            if (appExitPolicy.stopEpoch != ticket ||
                (expectedState != null && !expectedState.acceptsSettingsRestart(_connectionState.value))) null
            else ConnectionArbiter.claim(tier, socketOwner(tier), description)
        }
        if (claim != null) attemptUserRequested = tier == ConnectionPriorityPolicy.Tier.USER
        return claim
    }

    /** A socket the user asked for is theirs; any other belongs to the wireless stack. */
    private fun socketOwner(tier: ConnectionPriorityPolicy.Tier) =
        if (tier == ConnectionPriorityPolicy.Tier.USER) ConnectionPriorityPolicy.Owner.MANUAL
        else ConnectionPriorityPolicy.Owner.WIRELESS_STACK

    /** An opened transport keeps its claim through the handshake; anything else lets it go. */
    private fun releaseClaim(claim: ConnectionArbiter.Claim): Unit = synchronized(transportLifecycleLock) {
        // The observer can start or finish SSL before connect() reaches its finally block.
        // Keep the claim through StartingTransport; releasing it there would lose USB checks
        // arriving during SSL. If SSL has already finished, settle it as a formed session.
        val state = _connectionState.value
        if ((state is ConnectionState.Connected || state is ConnectionState.StartingTransport) &&
            ConnectionArbiter.holds(claim)) {
            sessionClaim = claim
        } else {
            clearClaimedEndpoint(claim)
            ConnectionArbiter.release(claim, sessionFormed =
                state is ConnectionState.HandshakeComplete || state is ConnectionState.TransportStarted)
        }
    }

    private fun noteClaimedEndpoint(claim: ConnectionArbiter.Claim, endpoint: String?) {
        claimedEndpointOwner = endpoint?.let { claim to it }
    }

    private fun clearClaimedEndpoint(claim: ConnectionArbiter.Claim) {
        if (claimedEndpointOwner?.first === claim) claimedEndpointOwner = null
    }

    /** Ends the claim an opened transport carried into its handshake. */
    private fun settleSessionClaim(formed: Boolean) {
        val claim = sessionClaim ?: return
        sessionClaim = null
        clearClaimedEndpoint(claim)
        ConnectionArbiter.release(claim, sessionFormed = formed)
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
        // Service and Activity may both enqueue this call. A delayed request after startup or
        // retirement is a no-op, not a failure belonging to the current/new session.
        if (_connectionState.value !is ConnectionState.Connected) return@withContext

        val attemptedConnection = _connection
        var recoveryOwner = usbRecheckOwner()
        // Preserve known identity before handshake failure/cleanup can revoke USB permission.
        // A delayed callback must not replace it with another enumeration at the same address.
        val usbIdentity = (attemptedConnection as? AbstractUsbProjectionConnection)?.accessoryDevice?.let(UsbRecoveryDevice::capture)
        try {
            if (_connectionState.value is ConnectionState.Connected) {
                val conn = attemptedConnection ?: return@withContext
                val transport = synchronized(transportLifecycleLock) {
                    if (disconnectRequested || _connection !== conn || _connectionState.value !is ConnectionState.Connected) return@withContext
                    _connectionState.value = ConnectionState.StartingTransport
                    if (_transport == null) {
                        val audioManager = context.getSystemService(Application.AUDIO_SERVICE) as AudioManager
                        val candidate = AapTransport(
                            audioDecoder, videoDecoder, audioManager, settings, _backgroundNotification,
                            context, externalSsl = aapSslContext,
                            onAaMediaMetadata = { meta, session -> onAaMediaMetadata?.invoke(meta, session) },
                            onAaPlaybackStatus = { status, session -> onAaPlaybackStatus?.invoke(status, session) },
                            onAaPresentationClosed = { session -> onAaPresentationClosed?.invoke(session) }
                        )
                        candidate.setProjectionVideoFocusSuppressed(projectionVideoFocusSuppressed)
                        // A late quit belongs only to the transport that installed this callback.
                        candidate.onQuit = { isClean -> transportedQuited(candidate, isClean) }
                        candidate.onAudioFocusStateChanged = { isPlaying -> onAudioFocusStateChanged?.invoke(isPlaying) }
                        candidate.onUpdateUiConfigReplyReceived = { onUpdateUiConfigReplyReceived?.invoke() }
                        _transport = candidate
                    }
                    checkNotNull(_transport)
                }
                val usb = conn is AbstractUsbProjectionConnection
                // Held locally: startHandshake can retire itself and clear the published owner.
                val shook = transport.startHandshake(conn)
                if (_connection !== conn || (shook && _transport !== transport)) return@withContext
                if (shook) {
                    val live = withLiveTransport(transport, ConnectionState.StartingTransport) {
                        // A session that got this far had a working link to carry video on. See
                        // VideoStarvationPolicy for what it means when one ends without carrying any.
                        usbRecoveryOwner = Any()
                        recoveryOwner = usbRecoveryOwner
                        usbSaveOwner = null
                        sessionReachedHandshake = true
                        videoDecoder.beginSession()
                        ProjectionHealthRecorder.reset()
                        silentPeerFailures = 0
                        // The peer answered, which is what the deaf-server record claims it cannot.
                        ConnectionIssues.clear(context, ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING)
                        settleSessionClaim(formed = true)
                        _connectionState.value = ConnectionState.HandshakeComplete
                    }
                    if (live && usb) onUsbHandshakeEnded?.invoke(UsbHandshakeOutcome(
                        AapTransport.HandshakeFailure.NONE, (conn as AbstractUsbProjectionConnection).accessoryDevice, recoveryOwner, usbIdentity))
                } else {
                    val failure = transport.lastHandshakeFailure
                    val live = withLiveTransport(transport, ConnectionState.StartingTransport) {
                        val silent = failure == AapTransport.HandshakeFailure.PEER_SILENT
                        noteHandshakeOutcome(silent)
                        // Here, not from a ConnectionState.Error collector: disconnect() follows with no
                        // suspension point, so the conflated flow never delivers Error and the pill kept
                        // the step it had. Measured stuck on "Securing the connection" for 3m39s.
                        ConnectionStageTracker.endAttempt()
                        onSessionFailure?.invoke(if (silent) "peer_silent" else "handshake_failed")
                        _connectionState.value = ConnectionState.Error(if (silent) ERROR_HANDSHAKE_PEER_SILENT else "Handshake failed")
                        settleSessionClaim(formed = false)
                        // Not a user exit: nobody chose to end a session that never formed.
                        disconnect(sendByeBye = false, isUserExit = false)
                    }
                    // Outside the lock: the USB side starts recovery, which can connect again.
                    if (live && usb) onUsbHandshakeEnded?.invoke(UsbHandshakeOutcome(
                        failure, (conn as AbstractUsbProjectionConnection).accessoryDevice, recoveryOwner, usbIdentity))
                }
            }
        } catch (e: Exception) {
            val usb = attemptedConnection is AbstractUsbProjectionConnection
            synchronized(transportLifecycleLock) {
                if (disconnectRequested || _connection !== attemptedConnection || _connectionState.value !is ConnectionState.StartingTransport) return@withContext
                // An exception is never the silent-peer case; clear the streak rather than leaving it
                // to age into a backoff that no longer describes what is happening.
                noteHandshakeOutcome(silent = false)
                onSessionFailure?.invoke("handshake_failed")
                _connectionState.value = ConnectionState.Error("Handshake failed: ${e.message}")
                settleSessionClaim(formed = false)
                disconnect(sendByeBye = false, isUserExit = false)
            }
            if (usb) onUsbHandshakeEnded?.invoke(UsbHandshakeOutcome(
                AapTransport.HandshakeFailure.OTHER, (attemptedConnection as AbstractUsbProjectionConnection).accessoryDevice, recoveryOwner, usbIdentity))
        }
    }

    /** A late handshake/read completion must not undo a disconnect that already chose its policy. */
    private inline fun withLiveTransport(source: AapTransport, expected: ConnectionState, action: () -> Unit): Boolean =
        synchronized(transportLifecycleLock) {
            if (disconnectRequested || _transport !== source || _connectionState.value != expected) false
            else { action(); true }
        }

    /**
     * Updates [silentPeerFailures] after a failed handshake, and explains the situation once when
     * the streak reaches the point where the retry cadence drops.
     */
    private fun noteHandshakeOutcome(silent: Boolean) {
        if (!silent) {
            // Any other failure means this is not the deaf-peer case, so the streak — and the
            // backoff resting on it — starts over.
            silentPeerFailures = 0
            return
        }
        val endpoint = lastAttemptedEndpoint
        silentPeerFailures =
            UnresponsivePeerPolicy.countAfterSilentFailure(silentPeerFailures, silentPeerEndpoint, endpoint)
        silentPeerEndpoint = endpoint
        // Only Android Auto's head unit server — the peer on 5277, so the discovery path and Self
        // Mode — is something the user can restart. The phone dialling our own server on 5288 and
        // the Nearby helper can both reach this branch, and sending their users off to force stop
        // Android Auto after a switch they never turned on would be worse than saying nothing.
        if (endpoint?.endsWith(":5277") == true) {
            // On the first one, not on the third the log explanation waits for: the record is
            // cleared the moment a handshake succeeds, so a premature banner self-heals, while a
            // missing one leaves the user a failed connection and no remedy.
            ConnectionIssues.raise(context, ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING)
        }
        if (UnresponsivePeerPolicy.shouldExplain(silentPeerFailures) && endpoint?.endsWith(":5277") == true) {
            AppLog.e(
                "CommManager: $endpoint has accepted $silentPeerFailures connections in a row " +
                    "without answering any of them. Slowing discovery to one attempt every " +
                    "${UnresponsivePeerPolicy.BACKOFF_RESCAN_MS / 1000}s. Android Auto hands each " +
                    "accepted connection to its own car service and waits there with no timeout, so " +
                    "it does not recover on its own. Stop and start the head unit server on the " +
                    "phone; this will reconnect by itself."
            )
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

        // Keep the attempted owner: completion and failure must not undo a later disconnect.
        val transport = _transport ?: return@withContext
        try {
            // This transport owns the only permanent focus client. The service deliberately
            // does not acquire one during handshake: its StateFlow observer can miss an early
            // disconnect, whereas AapAudio closes with its owning transport even before Start.
            // Only grab AUDIOFOCUS_GAIN in Static Audio Focus mode, matching AapControl.
            // In the default (dynamic) mode focus is acquired on demand via the AA protocol, so
            // an unconditional grab here would evict other media (e.g. the car radio) the moment
            // the phone connects, before AA plays anything.
            //
            // And even in static mode, not when the player we would evict is the head unit's own
            // A2DP sink: it answers by AVRCP-pausing the phone that is about to project to us.
            val audioConfig = transport.aapAudio.sessionConfig
            if (audioConfig.enabled && audioConfig.staticFocus) {
                val mode = audioConfig.focusMode
                val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
                if (PlaybackFocusPolicy.shouldAcquirePermanent(
                        mode = mode,
                        staticAudioFocus = true,
                        audioSinkEnabled = true,
                        btMediaLinkActive = btMediaLinkActive)) {
                    transport.aapAudio?.postProtocolFocusChange(
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
            withLiveTransport(transport, ConnectionState.HandshakeComplete) {
                _connectionState.value = ConnectionState.TransportStarted
            }
        } catch (e: Exception) {
            withLiveTransport(transport, ConnectionState.HandshakeComplete) {
                _connectionState.value = ConnectionState.Error("Start reading failed: ${e.message}")
                // Startup failed locally. Preserve automatic recovery and do not send the peer
                // a USER_SELECTION ByeBye for an exit the user never requested.
                disconnect(sendByeBye = false, isUserExit = false)
            }
        }
    }

    /** Reports an internal failure without applying the user's explicit Exit policy. */
    suspend fun emitError(msg: String) {
        synchronized(transportLifecycleLock) {
            _connectionState.value = ConnectionState.Error(msg)
            disconnect(sendByeBye = false, isUserExit = false)
        }
    }

    /**
     * Reports a failure without touching the connection.
     *
     * For a caller that has run out of patience rather than out of hope: a Self Mode launch that has
     * not reported in yet may still be in flight, and the server it will arrive on, plus the dummy
     * VPN it was handed, both have to stay up for that to happen. See
     * [com.andrerinas.openheadunit.connection.self.SelfLaunchTimeoutPolicy.mayDisconnect].
     */
    suspend fun reportError(msg: String, settingsRestart: ConnectionState.Disconnected? = null) {
        synchronized(transportLifecycleLock) {
            if (settingsRestart != null) {
                // A failed launcher has not handed ownership to another connection. Preserve
                // Save's Disconnected token so its bounded automatic fallback can still run.
                // A late failure from a superseded/cancelled Save cannot change the new state.
                if (settingsRestart.acceptsSettingsRestart(_connectionState.value)) AppLog.e(msg)
                return
            }
            _connectionState.value = ConnectionState.Error(msg)
        }
    }

    // A user exit owes no held USB scan. A wireless stack that USB stood down still comes back.
    private fun dropOwedScans() = ConnectionArbiter.dropUsbDebt()

    private fun reachedSsl() = _connectionState.value.let {
        it is ConnectionState.HandshakeComplete || it is ConnectionState.TransportStarted
    }

    /**
     * Called by `AapTransport.onQuit` when the transport stops itself (read error, socket
     * timeout, or phone-initiated graceful close).
     *
     * Sets state to [ConnectionState.Disconnected] synchronously (so [isConnected] returns
     * `false` immediately) then schedules cleanup. `sendByeBye` is `false` because the
     * connection is already dead — there is no point sending a `ByeByeRequest`.
     */
    private fun transportedQuited(source: AapTransport, isClean: Boolean): Unit = synchronized(transportLifecycleLock) {
        // An explicit disconnect has already chosen whether the app should stay open. The
        // matching EOF must not choose again, and an old transport cannot retire a replacement.
        if (_transport !== source || disconnectRequested) return@synchronized
        disconnectRequested = true
        val wasUserExit = source.wasUserExit
        if (wasUserExit) {
            usbRecoveryOwner = Any()
            dropOwedScans()
        }
        val save = SettingsRestartRecovery.ownerAtEnd(wasUserExit, reachedSsl(), null, usbSaveOwner)
        usbSaveOwner = null
        // Keep the retiring owner published until doDisconnect captures it. Its callback
        // precedes final cleanup, so reconnect must await its actual termination as well.
        // Publish cleanup before state: a reconnect observer must be able to await this job.
        _disconnectJob = _scope.launch { doDisconnect(sendByeBye = false) }
        source.retirePresentation()
        _connectionState.value = ConnectionState.Disconnected(isClean, isUserExit = wasUserExit, wasLoopbackSession = isLoopbackSession, hadPhysicalConnection = physicalConnectionReached, settingsRetryOwner = save, endedAttempt = connectionAttempt)
        if (settings.killOnDisconnect) scheduleAppExit(wasUserExit)
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
            if (source != "auto-resume" && !MediaKeyRoutingPolicy.shouldForward(routing, true, btMediaLinkForKeys())) {
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
        // The trailing gain-only notification does *not* bring a keyframe forward, whatever this
        // comment used to claim. Dropped-frame-keyframe round 4 fired sixteen of these config
        // requests on a live stream: the phone acknowledged every one and its keyframe cadence never
        // moved off its fixed ~69s period. Kept because it costs one message and nothing has measured
        // the surface-mismatch path this serves without it.
        if (!projectionVideoFocusSuppressed) {
            send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain = true, unsolicited = true))
        }
    }

    private fun setProjectionVideoFocusSuppressed(suppressed: Boolean) {
        synchronized(transportLifecycleLock) {
            projectionVideoFocusSuppressed = suppressed
            _transport?.setProjectionVideoFocusSuppressed(suppressed)
        }
    }

    /**
     * First half of a video-focus cycle: release focus so the phone tears its video sink down and
     * has to set it up again, which is what makes it start the next stream with a keyframe. The
     * caller sends the matching gain after a gap.
     *
     * The release makes the phone answer with a video sink stop. [AapTransport.ignoreNextStopRequest]
     * marks that one as ours, so it stays distinguishable in the log from a sink stop nobody asked
     * for - which is what a video-black failure looks like, and has been the decisive line in four
     * rounds of hardware testing.
     *
     * Two policies decide when this is warranted, and nothing else should: [com.andrerinas.openheadunit.decoder.video.WarmRelaunchKeyframePolicy]
     * for a surface that has never shown a frame, and [com.andrerinas.openheadunit.decoder.video.KeyframeCycleEscalationPolicy] for a picture
     * left corrupt by a shed reference frame. The latter sends its own release from [AapTransport]
     * rather than calling here, because it has to pair the release with a regain on the send handler.
     * Releasing focus across a stream that is rendering is a known way to lose one permanently, which
     * is why both sets of gates are as reluctant as they are.
     *
     * Both go through the transport's single claim on the lever, so a release can never be issued
     * while another cycle is still waiting to send its regain. The returned cycle owns the
     * transport that sent the release; a delayed regain must never target a replacement session.
     */
    class VideoFocusCycle internal constructor(internal val transport: AapTransport)

    fun releaseVideoFocusForKeyframe(): VideoFocusCycle? {
        if (projectionVideoFocusSuppressed) return null
        if (_connectionState.value !is ConnectionState.TransportStarted) return null
        val transport = _transport ?: return null
        if (!transport.beginFocusCycle()) {
            AppLog.i("CommManager: a video-focus cycle is already in flight - not starting a second")
            return null
        }
        AppLog.i("CommManager: releasing video focus to force a keyframe")
        transport.sendKeyframeCycleRelease()
        return VideoFocusCycle(transport)
    }

    /** Complete only the cycle whose transport sent the release, and only while it is live. */
    fun retakeVideoFocusForKeyframe(cycle: VideoFocusCycle): Boolean {
        if (projectionVideoFocusSuppressed) {
            cycle.transport.endFocusCycle()
            return false
        }
        val transport = cycle.transport
        if (_transport !== transport || _connectionState.value !is ConnectionState.TransportStarted) return false
        transport.send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain = true, unsolicited = true))
        transport.endFocusCycle()
        return true
    }

    /** Regain focus for the current transport during wake/resume; warm relaunch uses its cycle token. */
    fun retakeVideoFocusForKeyframe() {
        if (projectionVideoFocusSuppressed) return
        if (_connectionState.value !is ConnectionState.TransportStarted) {
            AppLog.w("CommManager: skipping retakeVideoFocusForKeyframe because transport is not started (${_connectionState.value})")
            return
        }
        _transport?.let {
            it.send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain = true, unsolicited = true))
            it.endFocusCycle()
        }
    }

    fun pauseForSleep() {
        _transport?.pauseForSleep()
    }

    fun updateAudioGains() {
        _transport?.aapAudio?.updateGains()
    }

    /**
     * Apply saved audio preferences without destroying the service/process. Rebuilding tracks
     * cannot renegotiate PCM/AAC or replace a session's focus policy. End that session instead;
     * a settings restart reuses the saved route without reporting a link failure.
     */
    fun applyAudioSettings(): Unit = synchronized(transportLifecycleLock) {
        val audio = _transport?.aapAudio ?: return@synchronized
        if (audio.needsSessionRestart()) {
            AppLog.i("CommManager: audio settings changed; reconnecting the projection session")
            disconnect(isUserExit = false, honorKillOnDisconnect = false,
                reason = DisconnectReason.SETTINGS_RESTART)
        } else {
            audio.restartAudio()
        }
    }

    /** Quick Settings rebuilds tracks only; explicit Save owns session renegotiation. */
    fun restartAudio(): Unit = synchronized(transportLifecycleLock) {
        _transport?.aapAudio?.restartAudio()
        Unit
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
    fun disconnect(
        sendByeBye: Boolean = true,
        isUserExit: Boolean = true,
        byeByeReason: com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason = com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason.USER_SELECTION,
        // A disconnect the app itself takes in order to show something next cannot honour "close
        // app on disconnect": there would be nothing left to show it on.
        honorKillOnDisconnect: Boolean = true,
        reason: DisconnectReason = DisconnectReason.CONNECTION_ENDED,
        settingsRetryOwner: ConnectionState.Disconnected? = null,
    ): Unit = synchronized(transportLifecycleLock) {
        if (isUserExit || reason == DisconnectReason.SETTINGS_RESTART || reason == DisconnectReason.PROJECTION_UNRAISED) usbRecoveryOwner = Any()
        if (isUserExit && reason != DisconnectReason.SETTINGS_RESTART) {
            cancelPendingSettingsRestart()
            dropOwedScans()
        }
        if (disconnectRequested || _connectionState.value is ConnectionState.Disconnected) return@synchronized
        disconnectRequested = true
        val save = if (reason == DisconnectReason.SETTINGS_RESTART) null
            else SettingsRestartRecovery.ownerAtEnd(isUserExit, reachedSsl(), settingsRetryOwner, usbSaveOwner)
        usbSaveOwner = null

        HeadUnitScreenConfig.unlockResolution()

        if (isUserExit) {
            _transport?.wasUserExit = true
        }
        _disconnectJob = _scope.launch { doDisconnect(sendByeBye, byeByeReason, reason) }
        _transport?.retirePresentation()
        _connectionState.value = ConnectionState.Disconnected(
            isUserExit = isUserExit, reason = reason,
            settingsRetryOwner = save,
            wasLoopbackSession = isLoopbackSession,
            hadPhysicalConnection = physicalConnectionReached,
            restartEndpoint = if (reason == DisconnectReason.SETTINGS_RESTART) outgoingEndpoint else null,
            settingsRestartUntilMs = if (reason == DisconnectReason.SETTINGS_RESTART)
                SystemClock.elapsedRealtime() + SettingsRestartRecovery.WINDOW_MS else 0L,
            endedAttempt = connectionAttempt,
        )
        if (settings.killOnDisconnect && honorKillOnDisconnect) scheduleAppExit(isUserExit)
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
    private fun doDisconnect(
        sendByeBye: Boolean = true,
        byeByeReason: com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason = com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason.USER_SELECTION,
        reason: DisconnectReason = DisconnectReason.CONNECTION_ENDED,
    ) {
        val (transport, connection, closeAudio) = synchronized(transportLifecycleLock) {
            // destroy() also reaches this path without a preceding disconnect(). Retire an
            // unpublished candidate before the owner-null fast path, just like an active owner.
            disconnectRequested = true
            val endedAttempt = connectionAttempt
            connectionAttempt = null
            if (_connectionState.value !is ConnectionState.Disconnected) {
                _transport?.retirePresentation()
                _connectionState.value = ConnectionState.Disconnected(wasLoopbackSession = isLoopbackSession, hadPhysicalConnection = physicalConnectionReached, endedAttempt = endedAttempt)
            }
            val transport = _transport
            val connection = _connection
            if (transport == null && connection == null) return
            val session = audioDecoder.captureCleanup()
            _transport = null
            _connection = null
            Triple(transport, connection, session)
        }
        settleSessionClaim(formed = false)
        keyStates.clear()
        btMediaLinkCached = null
        btMediaLinkCheckedAt = null
        // Counts frames over the whole session rather than reading lastFrameRenderedMs, which the
        // decoder zeroes every time the projection surface goes away — leaving projection before
        // disconnecting is normal, and would otherwise make every such session look starved.
        // Self-guarding against the re-entrant second call described above: the flag is consumed
        // here, so the second pass sees a session that never reached the handshake and counts
        // nothing.
        recordProjectionHealth()
        noteSessionEnded(renderedAnyFrame = videoDecoder.framesRenderedThisSession > 0L, deliberateEnd = reason != DisconnectReason.CONNECTION_ENDED)
        // The close is in its own phase because it is the one that must happen: a throw from the
        // ByeBye send or either decoder stop used to skip it, leaving the phone's head unit server
        // holding a peer that never came back. See TeardownGuard.
        TeardownGuard.runThenClose(
            teardown = {
                try {
                    // Only send ByeByeRequest when we are initiating the disconnect (e.g. user pressed
                    // disconnect). When the transport self-quit (read error, soTimeout), the connection
                    // is already dead — skip the send and the 150 ms sleep inside stop().
                    try {
                        if (sendByeBye) transport?.stop(byeByeReason)
                    } finally {
                        transport?.quit()
                        // quit may already be running on the poll thread. Notification is not
                        // completion: wait outside our monitor before shared decoders/TLS are reused.
                        transport?.awaitTermination()
                    }

                    // Explicitly stop and release decoders to prevent MediaCodec finalize() timeouts
                    videoDecoder.stop("CommManager: doDisconnect")
                } finally {
                    closeAudio()
                }
            },
            close = { connection?.disconnect() },
            onError = { phase, e -> AppLog.e("CommManager: doDisconnect $phase failed: ${e.message}") }
        )
    }

    /**
     * Counts a finished session against [VideoStarvationPolicy] and, on a long enough run of
     * sessions that carried no video at all, lowers the profile the next one is offered and says so.
     *
     * The phone gives no reason we can see — it closes the socket and our read reports a plain EOF
     * — so without this the log shows nothing but a healthy connection repeating forever.
     *
     * The record is retired by a session that renders, which is the hardware answering the
     * question. No setting disproves it, so it has no `remedyApplied` entry. **The cap is not
     * retired there**: capping is what made that session render, so releasing it on the same event
     * would uncap, starve three more times and earn it again forever. Only the user changing the
     * resolution or the frame rate takes it off (`SettingsFragment`).
     */
    private fun noteSessionEnded(renderedAnyFrame: Boolean, deliberateEnd: Boolean = false) {
        val reachedHandshake = sessionReachedHandshake
        sessionReachedHandshake = false
        // An intentional end says nothing about the link's ability to carry video.
        if (deliberateEnd) return
        starvedSessionStreak = VideoStarvationPolicy.nextStreak(
            starvedSessionStreak, reachedHandshake, renderedAnyFrame
        )
        if (reachedHandshake && renderedAnyFrame) {
            ConnectionIssues.clear(context, ConnectionIssue.VIDEO_LINK_TOO_SLOW)
        }
        if (VideoStarvationPolicy.shouldCap(starvedSessionStreak)) {
            settings.videoProfileStarvationCap = true
        }
        if (VideoStarvationPolicy.shouldAdvise(starvedSessionStreak)) {
            AppLog.w(
                "CommManager: $starvedSessionStreak sessions in a row ended without a single video " +
                    "frame arriving. The phone is connecting and then giving up on the video stream, " +
                    "which is what a WiFi link too slow to carry it looks like. The next connection " +
                    "is offered at most ${NarrowBandProfilePolicy.CAPPED_RESOLUTION.resName} and " +
                    "${NarrowBandProfilePolicy.CAPPED_FRAME_RATE} fps with AAC audio instead of what " +
                    "Video settings say. Changing the resolution or the frame rate yourself takes " +
                    "that back off; moving the access point to 5 GHz is the other fix."
            )
            ConnectionIssues.raise(context, ConnectionIssue.VIDEO_LINK_TOO_SLOW)
        }
    }

    /**
     * Stores one projection's local faults for the home screen's repeated-problem suggestion.
     *
     * Called before [videoDecoder.stop], which is where the frame counters are cleared. A session
     * that reached the handshake but carried too little video is skipped: that is a link or a
     * short drive, not something a codec or resolution change would fix.
     */
    private fun recordProjectionHealth() {
        if (!sessionReachedHandshake) return
        val decoder = videoDecoder.sessionHealth()
        if (decoder.framesFed < ProjectionHealthPolicy.MIN_FED_FRAMES) return
        if (decoder.framesRendered == 0L) return
        val renderer = ProjectionHealthRecorder.snapshot()
        ProjectionHealthStore(context).append(
            ProjectionSessionSummary(
                endedAtMs = System.currentTimeMillis(),
                codec = decoder.finalCodec.settingsValue,
                viewMode = settings.viewMode,
                resolutionId = settings.resolutionId,
                fpsLimit = settings.fpsLimit,
                codecFallback = decoder.codecFallbackUsed,
                rendererRecoveries = renderer.recoveries,
                forcedSurface = renderer.forcedSurface,
                framesFed = decoder.framesFed,
                framesDropped = decoder.framesDropped,
                framesSkipped = decoder.framesSkipped,
                framesRendered = decoder.framesRendered,
            )
        )
        ProjectionHealthRecorder.reset()
    }

    /**
     * Closes the session because the link carrying it is about to go away, and blocks until the
     * socket is actually closed or [timeoutMs] elapses. Never call from the main thread.
     *
     * Deliberately not [disconnect]. That one means "the user pressed Exit": it marks a user exit,
     * which suppresses the reconnect, and it honours `killOnDisconnect`, which would finish the
     * activities and stop the service. Neither is right for a WiFi toggle the user expects to come
     * back from.
     *
     * Blocking is the whole point. [disconnect] only schedules the teardown, which is fine when
     * something will still be running afterwards to notice; it is not fine when the interface is
     * on its way down and what matters is that the close goes out before it.
     */
    fun disconnectForLinkLoss(timeoutMs: Long) {
        val job = synchronized(transportLifecycleLock) {
            if (disconnectRequested || _connectionState.value is ConnectionState.Disconnected) return
            disconnectRequested = true
            HeadUnitScreenConfig.unlockResolution()
            val cleanup = _scope.launch { doDisconnect(sendByeBye = true) }
            _disconnectJob = cleanup
            _transport?.retirePresentation()
            _connectionState.value = ConnectionState.Disconnected(isClean = false, isUserExit = false, wasLoopbackSession = isLoopbackSession, hadPhysicalConnection = physicalConnectionReached, endedAttempt = connectionAttempt)
            cleanup
        }
        runBlocking { withTimeoutOrNull(timeoutMs) { job.join() } }
    }

    /**
     * Performs a final disconnect and re-arms the internal coroutine scope.
     *
     * Call this when the owning component (e.g. the foreground service) is destroyed. It is not
     * terminal: the next service gets this same instance, and every disconnect path launches on
     * that scope, so leaving it cancelled would silence them all for the life of the process.
     */
    fun destroy() {
        doDisconnect()
        _scope.cancel()
        // Armed again for the next AapService. AppComponent hands out one CommManager per process,
        // so the instance a destroyed service leaves behind is the one the next service uses.
        _scope = newDisconnectScope()
    }

    companion object {
        /**
         * [ConnectionState.Error] message for the one handshake failure that says something
         * specific about the *peer* rather than about us: the TCP connection was accepted, the
         * version request went out, and not one byte ever came back.
         *
         * Still contains "Handshake failed" so the generic handling keeps working.
         */
        const val ERROR_HANDSHAKE_PEER_SILENT = "Handshake failed: the peer never responded"
    }
}
