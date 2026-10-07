package com.andrerinas.openheadunit.connection.wifi.modes.helper

import com.andrerinas.openheadunit.connection.SettingsRestartRecovery
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.ConnectionAdmissionRejectedException
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.Socket

/**
 * Manages Google Nearby Connections on the Headunit (Tablet).
 * The Tablet acts as a DISCOVERER only.
 */
class NearbyManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSocketReady: suspend (Socket, ConnectionAdmission) -> Unit
) {

    data class DiscoveredEndpoint(val id: String, val name: String)

    companion object {
        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    // A callback belongs to the request that created it, even if Nearby reuses its endpoint ID.
    // The same monitor covers delayed tunnel publication and retirement.
    private val attempts = NearbyAttemptGuard()

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.andrerinas.openhu"
    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private var isRunning = false
    private var tunnelJob: Job? = null
    private var discoveryGeneration: Any? = null
    private var isConnecting = false
    private var settingsRestartPeer: String? = null
    private var settingsRestartUntilMs = 0L
    private var settingsRestartTimeoutJob: Job? = null

    // Written on the Nearby callback thread, read from the upgrade-timeout and tunnel coroutines.
    @Volatile
    private var activeNearbySocket: NearbySocket? = null

    @Volatile
    private var activeEndpointId: String? = null

    // Written on the IO coroutine that builds the tunnel, read by stop() on whichever thread tears
    // the session down. Volatile for the same reason as activeNearbySocket above: without it a
    // stop() can read null and leave the pipes open.
    @Volatile
    private var activePipes: Array<ParcelFileDescriptor>? = null
    private var upgradeTimeoutJob: Job? = null

    /** The phone's stream, when it arrived before [activeNearbySocket] existed to hold it. */
    @Volatile
    private var pendingInboundStream: java.io.InputStream? = null

    /**
     * Highest bandwidth quality Nearby has reported per endpoint, so the tunnel decision does not
     * depend on which of the two callbacks that inform it happens to arrive first.
     *
     * Concurrent because the Nearby callback thread writes it while the upgrade-timeout coroutine
     * reads it to say what quality it did see.
     */
    private val lastQuality: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /**
     * The Wi-Fi network this device was on when the Nearby connection was accepted.
     *
     * Compared again when a tunnel fails, because the two failure modes look identical from here
     * and need opposite responses. If the network is still the same one, the peer simply never
     * answered. If it has been replaced, our own Wi-Fi went down and came back while Nearby was
     * negotiating its upgrade -- the radio could not hold the access point link while forming the
     * peer-to-peer group -- and no amount of retrying against that phone will help.
     */
    @Volatile
    private var networkAtConnect: Long? = null
    private val settings = Settings(context)

    fun resumeDiscoveryIfIdle(): Unit = attempts.locked {
        // Discovery can be stopped while Nearby waits for a Wi-Fi bandwidth upgrade.
        // Restarting it then would clear endpoints and overwrite the connecting stage.
        if (isConnecting || activeEndpointId != null || activeNearbySocket != null) return@locked
        start()
    }

    fun start(): Unit = attempts.locked {
        if (!hasRequiredPermissions()) {
            AppLog.w("NearbyManager: Missing required location/bluetooth permissions. Skipping start.")
            return@locked
        }
        if (isRunning) {
            AppLog.i("NearbyManager: Already running discovery.")
            return@locked
        }
        AppLog.i("NearbyManager: Starting Nearby (Discoverer only)...")
        ConnectionStageTracker.report(ConnectionStage.SEARCHING)
        isRunning = true
        _discoveredEndpoints.value = emptyList()
        startDiscovery()
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasAdvertise || !hasScan || !hasConnect) return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) return false
        }

        return true
    }

    /** Save grants one retry of the same peer even when automatic connection is disabled. */
    fun restartForSettings(untilMs: Long = SystemClock.elapsedRealtime() +
        SettingsRestartRecovery.WINDOW_MS): Unit = attempts.locked {
        val peer = settings.lastNearbyDeviceName.takeIf { it.isNotEmpty() } ?: return@locked
        stop()
        settingsRestartPeer = peer
        settingsRestartUntilMs = untilMs
        start()
        val generation = discoveryGeneration
        settingsRestartTimeoutJob = scope.launch {
            delay((untilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            attempts.locked {
                if (discoveryGeneration !== generation || settingsRestartUntilMs != untilMs) return@locked
                settingsRestartPeer = null
                // An already discovered phone need not produce a second FOUND callback.
                // Reconsider the list under ordinary preferences, without granting another retry.
                if (isRunning && settings.autoConnectLastSession && !isConnecting && activeEndpointId == null) {
                    _discoveredEndpoints.value.firstOrNull { it.name == settings.lastNearbyDeviceName }
                        ?.let { connectToEndpoint(it.id) }
                }
            }
        }
    }

    fun stop(): Unit = attempts.locked {
        settingsRestartTimeoutJob?.cancel()
        settingsRestartTimeoutJob = null
        settingsRestartPeer = null
        AppLog.i("NearbyManager: Stopping discovery and disconnecting from any active endpoint...")
        isRunning = false
        discoveryGeneration = null
        connectionsClient.stopDiscovery()
        retireAttempt()
        _discoveredEndpoints.value = emptyList()
    }

    /** Retire this attempt without stopping an ongoing discovery or starting a new discovery run. */
    private fun failAttempt(attempt: NearbyAttemptGuard.Attempt) {
        attempts.run(attempt) {
            retireAttempt()
        }
    }

    private fun retireAttempt(): Unit = attempts.retire { attempt ->
        isConnecting = false
        tunnelJob?.cancel()
        tunnelJob = null
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        (activeEndpointId ?: attempt?.endpoint)?.let { connectionsClient.disconnectFromEndpoint(it) }
        activeEndpointId = null
        activeNearbySocket?.close()
        activeNearbySocket = null
        pendingInboundStream?.let { try { it.close() } catch (_: Exception) {} }
        pendingInboundStream = null
        activePipes?.forEach { try { it.close() } catch (_: Exception) {} }
        activePipes = null
        lastQuality.clear()
        networkAtConnect = null
    }

    /**
     * Manually initiate a connection to a specific discovered endpoint.
     * Called from HomeFragment when user taps a device in the list.
     */
    fun connectToEndpoint(endpointId: String): Unit = attempts.locked {
        if (isConnecting) {
            AppLog.w("NearbyManager: Already connecting, ignoring request for $endpointId")
            return@locked
        }
        // Auto-connect fires from onEndpointFound and the user can tap the same device in the list a
        // moment later. Once the first attempt has *succeeded*, isConnecting is already back to
        // false, so that guard alone let the second request through to be rejected with
        // STATUS_ALREADY_CONNECTED_TO_ENDPOINT -- an error line for what is simply a duplicate.
        if (activeEndpointId == endpointId) {
            AppLog.i("NearbyManager: Already connected to $endpointId, ignoring duplicate request")
            return@locked
        }
        settingsRestartPeer = null
        if (activeEndpointId != null) retireAttempt()
        AppLog.i("NearbyManager: Requesting connection to endpoint: $endpointId")
        // Nothing has been reported about this attempt yet, so nothing may be carried into it.
        lastQuality.remove(endpointId)
        isConnecting = true

        val attempt = attempts.begin(endpointId)
        connectionsClient.requestConnection(Build.MODEL, endpointId, connectionLifecycleCallback(attempt))
            .addOnFailureListener { e ->
                failAttempt(attempt)
                AppLog.e("NearbyManager: Failed to request connection: ${e.message}")
            }
    }

    private fun startDiscovery() {
        isRunning = true
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        AppLog.i("NearbyManager: Requesting Discovery with SERVICE_ID: $SERVICE_ID (Strategy: P2P_POINT_TO_POINT)")
        val generation = Any()
        discoveryGeneration = generation
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback(generation), discoveryOptions)
            .addOnSuccessListener { AppLog.d("NearbyManager: [OK] Discovery started.") }
            .addOnFailureListener { e ->
                AppLog.e("NearbyManager: [ERROR] Discovery failed: ${e.message}")
                attempts.locked {
                    if (discoveryGeneration === generation) {
                        isRunning = false
                        discoveryGeneration = null
                    }
                }
            }
    }

    private fun endpointDiscoveryCallback(generation: Any) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) = attempts.locked {
            if (discoveryGeneration !== generation || !isRunning) return@locked
            AppLog.i("NearbyManager: Endpoint FOUND: ${info.endpointName} ($endpointId)")
            ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId }) {
                current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                _discoveredEndpoints.value = current
            }

            // A settings retry follows only the peer whose tunnel was just retired. Discovery
            // supplies its current endpoint ID; the previous ID need not survive disconnect.
            // Check the clock too: a queued timeout must not extend Save's permission.
            if (SystemClock.elapsedRealtime() >= settingsRestartUntilMs) settingsRestartPeer = null
            val restartPeer = settingsRestartPeer
            if (restartPeer != null) {
                if (restartPeer == info.endpointName && !isConnecting && activeEndpointId == null) {
                    connectToEndpoint(endpointId)
                }
                return@locked
            }

            // Auto-connect logic
            val autoConnectMode = settings.autoConnectLastSession
            AppLog.i("NearbyManager: Auto-connect check: Enabled=$autoConnectMode, isConnecting=$isConnecting, activeEndpointId=$activeEndpointId")

            if (autoConnectMode && !isConnecting && activeEndpointId == null) {
                val lastDevice = settings.lastNearbyDeviceName
                AppLog.i("NearbyManager: Comparing found '${info.endpointName}' with last known '$lastDevice'")
                if (lastDevice.isNotEmpty() && lastDevice == info.endpointName) {
                    AppLog.i("NearbyManager: MATCH! Auto-connecting to known device '$lastDevice'...")
                    connectToEndpoint(endpointId)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) = attempts.locked {
            if (discoveryGeneration !== generation || !isRunning) return@locked
            AppLog.i("NearbyManager: Endpoint LOST: $endpointId")
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private fun connectionLifecycleCallback(attempt: NearbyAttemptGuard.Attempt) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            attempts.run(attempt, endpointId) {
                AppLog.i("NearbyManager: Connection INITIATED with $endpointId (${info.endpointName}). Token: ${info.authenticationToken}")
                AppLog.i("NearbyManager: Automatically ACCEPTING connection...")

                // Save last connected device name for auto-reconnect
                AppLog.i("NearbyManager: Saving '${info.endpointName}' as last connected device candidate.")
                settings.lastNearbyDeviceName = info.endpointName

                // Stop discovery as soon as it finds the target.
                isRunning = false
                discoveryGeneration = null
                connectionsClient.stopDiscovery()

                connectionsClient.acceptConnection(endpointId, payloadCallback(attempt))
                    .addOnFailureListener { e ->
                        failAttempt(attempt)
                        AppLog.e("NearbyManager: Failed to accept connection: ${e.message}")
                    }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            attempts.run(attempt, endpointId) {
                val status = result.status
                AppLog.i("NearbyManager: Connection RESULT for $endpointId: StatusCode=${status.statusCode} (${status.statusMessage})")
                if (status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                    failAttempt(attempt)
                    return@run
                }
                isConnecting = false
                activeEndpointId = endpointId
                networkAtConnect = currentNetworkHandle()
                AppLog.i("NearbyManager: Connected successfully!")
                ConnectionStageTracker.report(ConnectionStage.PHONE_JOINING)
                // HIGH can arrive before the result; either callback may complete the pair.
                maybeBuildTunnel(attempt, endpointId)
                if (activeNearbySocket == null) {
                    AppLog.i("NearbyManager: Waiting up to 10s for bandwidth upgrade to HIGH quality (Wi-Fi)...")
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        delay(10_000)
                        attempts.run(attempt) {
                            if (activeNearbySocket == null) {
                                AppLog.e("NearbyManager: Bandwidth upgrade timed out after 10s (best quality seen: ${qualityName(lastQuality[endpointId])}). Disconnecting to prevent Bluetooth fallback.")
                                describeTunnelFailure()?.let { AppLog.e("NearbyManager: $it") }
                                scope.launch(Dispatchers.Main) {
                                    ToastUtils.showToast(
                                        context,
                                        "Google Nearby connection failed: Wi-Fi bandwidth upgrade timed out. Please check Wi-Fi & Bluetooth settings.",
                                        Toast.LENGTH_LONG
                                    )
                                }
                                failAttempt(attempt)
                            }
                        }
                    }
                }
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            attempts.run(attempt, endpointId) {
                AppLog.i("NearbyManager: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality} (${qualityName(bandwidthInfo.quality)})")
                lastQuality[endpointId] = maxOf(lastQuality[endpointId] ?: Int.MIN_VALUE, bandwidthInfo.quality)
                maybeBuildTunnel(attempt, endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            attempts.run(attempt, endpointId) {
                AppLog.i("NearbyManager: DISCONNECTED from $endpointId")
                // Retire the tunnel as well as the endpoint. A new connection needs fresh streams.
                failAttempt(attempt)
            }
        }
    }

    /**
     * Builds the stream tunnel once both preconditions hold, whichever callback satisfies the last
     * one. Called from [ConnectionLifecycleCallback.onConnectionResult] and
     * [ConnectionLifecycleCallback.onBandwidthChanged] under the attempt monitor. Delayed work
     * holds the same monitor while publishing its pipes and handing the socket to AAP.
     *
     * Splitting this out of the bandwidth callback removes an ordering assumption: HIGH reported
     * before the connection result had recorded the endpoint used to be dropped on the floor, and
     * Nearby does not report it again.
     */
    private fun maybeBuildTunnel(attempt: NearbyAttemptGuard.Attempt, endpointId: String) {
        if (activeEndpointId != endpointId) return
        if (activeNearbySocket != null) return
        if (lastQuality[endpointId] != BandwidthInfo.Quality.HIGH) return

        AppLog.i("NearbyManager: Wi-Fi Bandwidth Upgrade successful (Quality: HIGH). Initiating stream tunnel...")
        ConnectionStageTracker.report(ConnectionStage.PHONE_JOINING)

        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null

        val socket = NearbySocket()
        activeNearbySocket = socket

        // The phone may already have sent its half while we were still setting up.
        pendingInboundStream?.let {
            AppLog.i("NearbyManager: Attaching the inbound STREAM that arrived before the socket existed.")
            socket.inputStreamWrapper = it
            pendingInboundStream = null
        }

        tunnelJob = scope.launch(Dispatchers.IO) {
            // The socket built just above, not whatever happens to be current when this coroutine
            // gets to run. A stop() and a fresh upgrade in between would otherwise have this body
            // attach its pipe to a socket belonging to the next session; if that has happened, this
            // tunnel is obsolete and there is nothing here worth finishing.
            if (activeNearbySocket !== socket) return@launch
            val sock = socket

            // Give the phone a moment to register its payload handler before we send. This is no
            // longer load-bearing -- both sides now hold an early stream instead of discarding it --
            // but arriving in the expected order still saves a round trip through that path.
            AppLog.i("NearbyManager: Waiting 800ms for phone state synchronization...")
            kotlinx.coroutines.delay(800)

            val ready = attempts.run(attempt, endpointId) {
                if (activeNearbySocket !== sock || sock.isClosed) return@run
                try {
                    // 1. Create outgoing pipe (Tablet -> Phone)
                    val pipes = android.os.ParcelFileDescriptor.createPipe()
                    activePipes = pipes
                    val outputStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                    sock.outputStreamWrapper = outputStream

                    // 2. Initiate stream tunnel
                    AppLog.i("NearbyManager: Initiating stream tunnel to $endpointId...")
                    val tabletToPhonePayload = Payload.fromStream(pipes[0])
                    AppLog.i("NearbyManager: Sending STREAM payload (ID: ${tabletToPhonePayload.id})")

                    connectionsClient.sendPayload(endpointId, tabletToPhonePayload)
                        .addOnSuccessListener {
                            AppLog.i("NearbyManager: [OK] Tablet->Phone stream payload registered.")
                        }
                        .addOnFailureListener { e ->
                            failAttempt(attempt)
                            AppLog.e("NearbyManager: [ERROR] Failed to send stream: ${e.message}")
                        }

                    // [CRITICAL] Start AA handshake immediately.
                    // NearbySocket.read() will block internally until Phone stream arrives.
                    AppLog.i("NearbyManager: Starting AA handshake now. Input will block until stream arrives.")
                } catch (e: Exception) {
                    AppLog.e("NearbyManager: Failed to build stream tunnel", e)
                    failAttempt(attempt)
                }
            }
            if (ready && !sock.isClosed) {
                currentCoroutineContext().ensureActive()
                val admission = ConnectionAdmission { action -> attempts.run(attempt, action = action) }
                // Keep the connect coroutine a child of this attempt, including its suspension
                // in CommManager while the previous connection is being retired.
                handOverTunnel(attempt, sock, admission)
            }
        }
    }

    // Await the consumer's final ownership decision, not just socket creation. Rejection and
    // other failures retire only this attempt; a later attempt's endpoint and pipes stay intact.
    internal suspend fun handOverTunnel(
        attempt: NearbyAttemptGuard.Attempt, sock: Socket, admission: ConnectionAdmission,
    ) {
        try {
            onSocketReady(sock, admission)
        } catch (e: ConnectionAdmissionRejectedException) {
            failAttempt(attempt)
            AppLog.i("NearbyManager: tunnel handoff superseded; retired its attempt")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("NearbyManager: Failed to hand over stream tunnel", e)
            failAttempt(attempt)
        }
    }

    /**
     * Identifier of the currently active network, or null below API 23 where it cannot be asked.
     * A new identifier for the same access point still counts as a change -- that is precisely the
     * event worth catching, since it means the link was torn down and rebuilt.
     */
    private fun currentNetworkHandle(): Long? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            cm?.activeNetwork?.networkHandle
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Says which of the two indistinguishable tunnel failures this was, so the log carries a
     * conclusion rather than a symptom. Returns null when the question cannot be answered.
     */
    private fun describeTunnelFailure(): String? {
        val before = networkAtConnect ?: return null
        val now = currentNetworkHandle() ?: return null
        return if (before == now) {
            "This head unit's Wi-Fi stayed up throughout, so the link was healthy on our side and " +
                    "the phone simply never registered its stream payload."
        } else {
            "This head unit's Wi-Fi was torn down and rebuilt while Nearby was negotiating its " +
                    "bandwidth upgrade. The radio cannot hold the access point connection and form " +
                    "the peer-to-peer group this phone asked for at the same time, so the upgraded " +
                    "channel never carried data. Putting both devices on the same Wi-Fi band, or " +
                    "using the Common WiFi / Headunit Server strategy (which runs over the existing " +
                    "network and never reconfigures the radio), avoids this entirely."
        }
    }

    private fun qualityName(quality: Int?): String = when (quality) {
        null -> "none reported"
        BandwidthInfo.Quality.LOW -> "LOW"
        BandwidthInfo.Quality.MEDIUM -> "MEDIUM"
        BandwidthInfo.Quality.HIGH -> "HIGH"
        // A documented member of the enum, so naming it beats reporting it as unrecognised.
        BandwidthInfo.Quality.UNKNOWN -> "UNKNOWN"
        else -> "unknown($quality)"
    }

    private fun payloadCallback(attempt: NearbyAttemptGuard.Attempt) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            AppLog.i("NearbyManager: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                AppLog.i("NearbyManager: Received incoming STREAM payload. Completing bidirectional tunnel.")
                val inbound = payload.asStream()?.asInputStream()
                val accepted = attempts.run(attempt, endpointId) {
                    val socket = activeNearbySocket
                    if (inbound == null) {
                        // A STREAM payload with nothing readable behind it. Nothing can be done with it,
                        // and storing it was worse than dropping it: the log said the stream was held
                        // while a null went into the slot, so the wait that follows timed out against a
                        // message claiming the opposite. Say what happened and leave the slot alone.
                        AppLog.e(
                            "NearbyManager: Inbound STREAM payload carried no readable stream. The tunnel " +
                                    "cannot be completed from this payload; waiting for another."
                        )
                    } else if (pendingInboundStream != null || socket?.inputStreamWrapper != null || socket?.isClosed == true) {
                        // A tunnel has one inbound stream. Replacing it would splice two byte streams
                        // into the same TLS session; retain the first and close the duplicate.
                        if (inbound !== pendingInboundStream && inbound !== socket?.inputStreamWrapper) {
                            try { inbound.close() } catch (_: Exception) {}
                        }
                    } else if (socket != null) {
                        socket.inputStreamWrapper = inbound
                        AppLog.i("NearbyManager: InputStream assigned to socket. Handshake should continue.")
                    } else {
                        // Arriving before our own socket exists is legal -- the two sides register their
                        // payloads independently and nothing orders them. Dropping it here (which a
                        // null-safe assignment did, silently) cost us the only inbound stream the phone
                        // will ever send: it does not retry, so the tunnel stayed half-open until Nearby
                        // gave up minutes later with no record of the cause.
                        AppLog.w("NearbyManager: Inbound STREAM arrived before the socket existed; holding it until the tunnel is built.")
                        pendingInboundStream = inbound
                    }
                }
                if (!accepted) try { inbound?.close() } catch (_: Exception) {}
            } else if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes() ?: byteArrayOf())
                AppLog.i("NearbyManager: Received BYTES payload: $msg")
                if (msg == "PING") {
                    AppLog.i("NearbyManager: Received PING from Phone. Connections are alive.")
                }
            }
        }


        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            attempts.run(attempt, endpointId) {
                if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                    AppLog.d("NearbyManager: Payload transfer SUCCESS for endpoint $endpointId")
                } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                    AppLog.e("NearbyManager: Payload transfer FAILURE for endpoint $endpointId")
                    // Only worth explaining while the tunnel was still being built. A failure after the
                    // session has run is just the stream ending with it.
                    //
                    // The test is whether the inbound half ever attached, which is what "built" means
                    // here. Asking instead whether the socket exists and nothing is pending inverted it:
                    // in the canonical failure -- socket built, phone never registered its stream -- both
                    // of those are false, so the one case this explanation was written for was the one
                    // case it stayed silent for. Asking whether anything is *pending* cannot work either,
                    // because a completed session has nothing pending too.
                    val tunnelIncomplete = activeNearbySocket?.inputStreamWrapper == null
                    if (tunnelIncomplete) {
                        describeTunnelFailure()?.let { AppLog.e("NearbyManager: $it") }
                    }
                }
            }
        }
    }
}
