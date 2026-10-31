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
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.CommManager
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
    private val commManager: CommManager,
    /** The tunnel's hand-over to AAP admission, with the ticket and, for Save's request, its Save. */
    private val onSocketReady: suspend (Socket, ConnectionAdmission, Long, CommManager.ConnectionState.Disconnected?) -> Unit
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
    // The Save that reserved [settingsRestartPeer] and the Stop ticket read when its end was
    // received. Save's own request to that peer carries both, and a cancelled Save sends nothing.
    private var settingsRestartSave: CommManager.ConnectionState.Disconnected? = null
    private var settingsRestartTicket = 0L
    // The Stop ticket of the input the running discovery serves; its automatic connects carry it.
    private var discoveryOperation: ConnectionOperation? = null
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

    /** [ticket]: the Stop ticket of the input asking for discovery; see [start]. */
    fun resumeDiscoveryIfIdle(operation: ConnectionOperation) {
        // Do not hold the attempt monitor across SDK discovery calls: they may call back inline.
        val idle = attempts.locked {
            !isConnecting && activeEndpointId == null && activeNearbySocket == null
        }
        if (idle) start(operation)
    }

    /**
     * Starts discovery for [operation]. A peer it finds later and connects
     * to automatically continues that input, carrying its original ticket and Save rather than reading one in
     * the SDK's callback; a newer input asking while discovery runs takes it over.
     */
    fun start(operation: ConnectionOperation) {
        if (!hasRequiredPermissions()) {
            AppLog.w("NearbyManager: Missing required location/bluetooth permissions. Skipping start.")
            return
        }
        var replaceDiscovery = false
        val generation = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) return@withAppConnectionLifecycleLock null
            attempts.locked {
                val running = discoveryOperation
                if (isRunning && running?.ticket == operation.ticket && running.save === operation.save) {
                    AppLog.i("NearbyManager: Already running discovery.")
                    return@locked null
                }
                replaceDiscovery = isRunning
                AppLog.i("NearbyManager: Starting Nearby (Discoverer only)...")
                ConnectionStageTracker.report(ConnectionStage.SEARCHING)
                isRunning = true
                discoveryOperation = operation
                _discoveredEndpoints.value = emptyList()
                Any().also { discoveryGeneration = it }
            }
        } ?: return
        if (replaceDiscovery) connectionsClient.stopDiscovery()
        startDiscovery(generation, operation)
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

    /**
     * [save] grants one retry of the same peer even when automatic connection is disabled. [ticket]
     * is the Stop ticket captured when Save's end was received. The peer found later continues
     * exactly that Save, carrying both, and a Save cancelled meanwhile (a user exit, Stop, a manual
     * start) reserves nothing; the deadline's fallback, ordinary admission, carries the ticket.
     */
    fun restartForSettings(save: CommManager.ConnectionState.Disconnected, ticket: Long) {
        val operation = ConnectionOperation(ticket, save)
        if (!commManager.isCurrent(operation)) return
        val peer = settings.lastNearbyDeviceName.takeIf { it.isNotEmpty() } ?: return
        val retired = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) null else attempts.locked {
                settingsRestartTimeoutJob?.cancel()
                settingsRestartTimeoutJob = null
                isRunning = false
                discoveryGeneration = null
                discoveryOperation = null
                _discoveredEndpoints.value = emptyList()
                val previous = detachAttempt()
                settingsRestartPeer = peer
                settingsRestartSave = save
                settingsRestartTicket = ticket
                previous
            }
        } ?: return
        try { connectionsClient.stopDiscovery() } finally { releaseAttempt(retired) }
        start(operation)
        val generation = attempts.locked { discoveryGeneration }
        settingsRestartTimeoutJob = scope.launch {
            delay((save.settingsRestartUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            val endpoint = attempts.locked {
                if (discoveryGeneration !== generation || settingsRestartSave !== save) return@locked null
                settingsRestartPeer = null
                settingsRestartSave = null
                // Save's end of the wait resumes ordinary connection, unless Save was cancelled.
                if (!save.isSettingsRestartCancelled && isRunning && settings.autoConnectLastSession &&
                    !isConnecting && activeEndpointId == null) {
                    _discoveredEndpoints.value.firstOrNull { it.name == settings.lastNearbyDeviceName }?.id
                } else null
            }
            if (endpoint != null) connectToEndpoint(endpoint, operation)
        }
    }

    fun stop() {
        val retired = attempts.locked {
            settingsRestartTimeoutJob?.cancel()
            settingsRestartTimeoutJob = null
            settingsRestartPeer = null
            settingsRestartSave = null
            AppLog.i("NearbyManager: Stopping discovery and disconnecting from any active endpoint...")
            isRunning = false
            discoveryGeneration = null
            discoveryOperation = null
            _discoveredEndpoints.value = emptyList()
            detachAttempt()
        }
        try { connectionsClient.stopDiscovery() } finally { releaseAttempt(retired) }
    }

    /** Detached under the attempt monitor; cleanup never reads a replacement's fields. */
    private class RetiredAttempt(
        val endpoint: String?,
        /** This retirement opened [endpoint]'s SDK call queue and must drain it. */
        val drainsEndpoint: Boolean,
        val socket: NearbySocket?,
        val stream: java.io.InputStream?,
        val pipes: Array<ParcelFileDescriptor>?,
        val tunnel: Job?,
        val upgrade: Job?,
    )

    private fun detachAttempt(): RetiredAttempt {
        var endpoint = activeEndpointId
        attempts.retire { if (endpoint == null) endpoint = it?.endpoint }
        val retiredEndpoint = endpoint
        // Reserved now, under the monitor, so any later request for this endpoint queues behind it.
        val drains = retiredEndpoint != null &&
            NearbyEndpointCalls.reserve(retiredEndpoint) { connectionsClient.disconnectFromEndpoint(retiredEndpoint) }
        val retired = RetiredAttempt(retiredEndpoint, drains, activeNearbySocket, pendingInboundStream,
            activePipes, tunnelJob, upgradeTimeoutJob)
        isConnecting = false
        tunnelJob = null
        upgradeTimeoutJob = null
        activeEndpointId = null
        activeNearbySocket = null
        pendingInboundStream = null
        activePipes = null
        lastQuality.clear()
        networkAtConnect = null
        return retired
    }

    private fun releaseAttempt(retired: RetiredAttempt) {
        retired.tunnel?.cancel()
        retired.upgrade?.cancel()
        try {
            if (retired.drainsEndpoint) retired.endpoint?.let { NearbyEndpointCalls.drain(it) }
        } finally {
            retired.socket?.close()
            retired.stream?.let { try { it.close() } catch (_: Exception) {} }
            retired.pipes?.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    /** Retire only this request; SDK callbacks and IO happen after releasing both monitors. */
    private fun failAttempt(attempt: NearbyAttemptGuard.Attempt) {
        val retired = attempts.locked {
            if (attempts.current() === attempt) detachAttempt() else null
        }
        if (retired != null) releaseAttempt(retired)
    }

    /**
     * The same order as CommManager's admission: lifecycle first, then attempt publication.
     * Actions only publish owned state; SDK calls and resource cleanup follow outside both locks.
     * With [requireSave], a Save's request also needs that Save to still own the connection state:
     * the Nearby-side steps before the tunnel is handed over (sending the request, accepting the
     * phone, publishing the tunnel). From the hand-over on, CommManager checks the Save itself.
     */
    private fun runAcceptedAttempt(
        attempt: NearbyAttemptGuard.Attempt,
        endpoint: String = attempt.endpoint,
        requireSave: Boolean = false,
        action: () -> Unit,
    ): Boolean = commManager.withAppConnectionLifecycleLock {
        if (!commManager.acceptsAppConnectionTicket(attempt.ticket)) false
        else if (requireSave && attempt.save?.acceptsSettingsRestart(commManager.connectionState.value) == false) false
        else attempts.run(attempt, endpoint, action)
    }

    internal fun connectionAdmission(attempt: NearbyAttemptGuard.Attempt): ConnectionAdmission =
        ConnectionAdmission { action -> runAcceptedAttempt(attempt, action = action) }

    /**
     * Manually initiate a connection to a specific discovered endpoint.
     * Called from HomeFragment when user taps a device in the list. [save] names the Save this
     * request continues, when it does; it is carried through to the final AAP admission.
     */
    fun connectToEndpoint(
        endpointId: String,
        operation: ConnectionOperation,
    ) {
        val ticket = operation.ticket
        val save = operation.save
        var retired: RetiredAttempt? = null
        var drainsRequest = false
        commManager.withAppConnectionLifecycleLock {
            attempts.locked {
                if (!commManager.acceptsAppConnectionTicket(ticket)) return@locked
                if (save != null && !save.acceptsSettingsRestart(commManager.connectionState.value)) return@locked
                if (attempts.current()?.ticket?.let { it != ticket } == true) retired = detachAttempt()
                if (isConnecting) {
                    AppLog.w("NearbyManager: Already connecting, ignoring request for $endpointId")
                    return@locked
                }
                if (activeEndpointId == endpointId) {
                    AppLog.i("NearbyManager: Already connected to $endpointId, ignoring duplicate request")
                    return@locked
                }
                settingsRestartPeer = null
                settingsRestartSave = null
                if (activeEndpointId != null) retired = detachAttempt()
                AppLog.i("NearbyManager: Requesting connection to endpoint: $endpointId")
                lastQuality.remove(endpointId)
                isConnecting = true
                val attempt = attempts.begin(endpointId, ticket, save)
                // Behind any disconnect a retirement of this endpoint reserved before us, in this
                // manager or in the one this manager replaced.
                drainsRequest = NearbyEndpointCalls.reserve(endpointId) { requestConnection(attempt) }
            }
        }
        try {
            retired?.let { releaseAttempt(it) }
        } finally {
            if (drainsRequest) NearbyEndpointCalls.drain(endpointId)
        }
    }

    /**
     * May run on the thread draining earlier cleanup, after Stop, an exit or a newer request: the
     * queued request is sent only while its own ticket, identity and Save still hold, checked
     * together. A revoked one retires only itself. The SDK call itself runs outside both monitors.
     */
    private fun requestConnection(attempt: NearbyAttemptGuard.Attempt) {
        if (!runAcceptedAttempt(attempt, requireSave = true) {}) {
            failAttempt(attempt)
            return
        }
        connectionsClient.requestConnection(Build.MODEL, attempt.endpoint, connectionLifecycleCallback(attempt, attempt.ticket))
            .addOnFailureListener { e ->
                failAttempt(attempt)
                AppLog.e("NearbyManager: Failed to request connection: ${e.message}")
            }
    }

    private fun startDiscovery(generation: Any, operation: ConnectionOperation) {
        val accepted = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) false
            else attempts.locked { isRunning && discoveryGeneration === generation && discoveryOperation === operation }
        }
        if (!accepted) return
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        AppLog.i("NearbyManager: Requesting Discovery with SERVICE_ID: $SERVICE_ID (Strategy: P2P_POINT_TO_POINT)")
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback(generation, operation), discoveryOptions)
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

    private fun endpointDiscoveryCallback(generation: Any, operation: ConnectionOperation) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // A peer Save asked for continues that Save, under the Save and ticket it captured;
            // any other automatic connect continues the discovery's own input, under its ticket.
            var restart: Pair<CommManager.ConnectionState.Disconnected, Long>? = null
            val connect = commManager.withAppConnectionLifecycleLock {
                if (!commManager.isCurrent(operation)) return@withAppConnectionLifecycleLock false
                attempts.locked {
                    if (discoveryGeneration !== generation || !isRunning) return@locked false
                    AppLog.i("NearbyManager: Endpoint FOUND: ${info.endpointName} ($endpointId)")
                    ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                    val current = _discoveredEndpoints.value.toMutableList()
                    if (current.none { it.id == endpointId }) {
                        current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                        _discoveredEndpoints.value = current
                    }
                    val save = settingsRestartSave
                    // An expired or cancelled Save holds no reservation any more.
                    if (save == null || SystemClock.elapsedRealtime() >= save.settingsRestartUntilMs ||
                        !save.acceptsSettingsRestart(commManager.connectionState.value)) {
                        settingsRestartPeer = null
                        settingsRestartSave = null
                    }
                    val restartPeer = settingsRestartPeer
                    if (restartPeer != null && save != null) {
                        restart = save to settingsRestartTicket
                        restartPeer == info.endpointName && !isConnecting && activeEndpointId == null
                    } else {
                        settings.autoConnectLastSession && !isConnecting && activeEndpointId == null &&
                            settings.lastNearbyDeviceName.isNotEmpty() && settings.lastNearbyDeviceName == info.endpointName
                    }
                }
            }
            if (!connect) return
            val saved = restart
            if (saved != null) connectToEndpoint(endpointId, ConnectionOperation(saved.second, saved.first))
            else connectToEndpoint(endpointId, operation)
        }

        override fun onEndpointLost(endpointId: String) = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) return@withAppConnectionLifecycleLock
            attempts.locked {
                if (discoveryGeneration !== generation || !isRunning) return@locked
                AppLog.i("NearbyManager: Endpoint LOST: $endpointId")
                val current = _discoveredEndpoints.value.toMutableList()
                current.removeAll { it.id == endpointId }
                _discoveredEndpoints.value = current
            }
        }
    }

    private fun connectionLifecycleCallback(attempt: NearbyAttemptGuard.Attempt, ticket: Long) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val accepted = runAcceptedAttempt(attempt, endpointId, requireSave = true) {
                AppLog.i("NearbyManager: Connection INITIATED with $endpointId (${info.endpointName}). Token: ${info.authenticationToken}")
                AppLog.i("NearbyManager: Automatically ACCEPTING connection...")
                isRunning = false
                discoveryGeneration = null
            }
            if (!accepted) { failAttempt(attempt); return }
            settings.lastNearbyDeviceName = info.endpointName
            connectionsClient.stopDiscovery()
            connectionsClient.acceptConnection(endpointId, payloadCallback(attempt))
                .addOnFailureListener { e ->
                    failAttempt(attempt)
                    AppLog.e("NearbyManager: Failed to accept connection: ${e.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status
            if (status.statusCode != ConnectionsStatusCodes.STATUS_OK) { failAttempt(attempt); return }
            val network = currentNetworkHandle()
            val accepted = runAcceptedAttempt(attempt, endpointId) {
                isConnecting = false
                activeEndpointId = endpointId
                networkAtConnect = network
                AppLog.i("NearbyManager: Connected successfully!")
                ConnectionStageTracker.report(ConnectionStage.PHONE_JOINING)
                maybeBuildTunnel(attempt, endpointId, ticket)
                if (activeNearbySocket == null) {
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        delay(10_000)
                        val timedOut = attempts.locked {
                            attempts.current() === attempt && activeNearbySocket == null
                        }
                        if (timedOut) {
                            AppLog.e("NearbyManager: Bandwidth upgrade timed out after 10s. Disconnecting to prevent Bluetooth fallback.")
                            describeTunnelFailure()?.let { AppLog.e("NearbyManager: $it") }
                            scope.launch(Dispatchers.Main) {
                                ToastUtils.showToast(context,
                                    "Google Nearby connection failed: Wi-Fi bandwidth upgrade timed out. Please check Wi-Fi & Bluetooth settings.",
                                    Toast.LENGTH_LONG)
                            }
                            failAttempt(attempt)
                        }
                    }
                }
            }
            if (!accepted) failAttempt(attempt)
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            val accepted = runAcceptedAttempt(attempt, endpointId) {
                AppLog.i("NearbyManager: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality} (${qualityName(bandwidthInfo.quality)})")
                lastQuality[endpointId] = maxOf(lastQuality[endpointId] ?: Int.MIN_VALUE, bandwidthInfo.quality)
                maybeBuildTunnel(attempt, endpointId, ticket)
            }
            if (!accepted) failAttempt(attempt)
        }

        override fun onDisconnected(endpointId: String) {
            if (attempt.endpoint == endpointId) failAttempt(attempt)
        }
    }

    /**
     * Builds the stream tunnel once both preconditions hold, whichever callback satisfies the last
     * one. Called from [ConnectionLifecycleCallback.onConnectionResult] and
     * [ConnectionLifecycleCallback.onBandwidthChanged] under lifecycle then attempt ownership.
     * Pipe creation, stream attachment and SDK calls run outside those critical sections; their
     * state publication and final AAP admission recheck the same attempt and ticket.
     *
     * Splitting this out of the bandwidth callback removes an ordering assumption: HIGH reported
     * before the connection result had recorded the endpoint used to be dropped on the floor, and
     * Nearby does not report it again.
     */
    private fun maybeBuildTunnel(attempt: NearbyAttemptGuard.Attempt, endpointId: String, ticket: Long) {
        if (activeEndpointId != endpointId) return
        if (activeNearbySocket != null) return
        if (lastQuality[endpointId] != BandwidthInfo.Quality.HIGH) return

        AppLog.i("NearbyManager: Wi-Fi Bandwidth Upgrade successful (Quality: HIGH). Initiating stream tunnel...")
        ConnectionStageTracker.report(ConnectionStage.PHONE_JOINING)

        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null

        val socket = NearbySocket()
        activeNearbySocket = socket

        tunnelJob = scope.launch(Dispatchers.IO) {
            // The socket built just above, not whatever happens to be current when this coroutine
            // gets to run. A stop() and a fresh upgrade in between would otherwise have this body
            // attach its pipe to a socket belonging to the next session; if that has happened, this
            // tunnel is obsolete and there is nothing here worth finishing.
            if (activeNearbySocket !== socket) return@launch
            val sock = socket
            val inbound = attempts.locked {
                if (attempts.current() === attempt && activeNearbySocket === sock) {
                    pendingInboundStream.also { pendingInboundStream = null }
                } else null
            }
            if (inbound != null) sock.inputStreamWrapper = inbound

            // Give the phone a moment to register its payload handler before we send. This is no
            // longer load-bearing -- both sides now hold an early stream instead of discarding it --
            // but arriving in the expected order still saves a round trip through that path.
            AppLog.i("NearbyManager: Waiting 800ms for phone state synchronization...")
            kotlinx.coroutines.delay(800)

            try {
                val pipes = android.os.ParcelFileDescriptor.createPipe()
                val outputStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                var published = false
                runAcceptedAttempt(attempt, endpointId, requireSave = true) {
                    if (activeNearbySocket === sock && !sock.isClosed) {
                        activePipes = pipes
                        published = true
                    }
                }
                if (!published) {
                    pipes.forEach { try { it.close() } catch (_: Exception) {} }
                    return@launch
                }
                sock.outputStreamWrapper = outputStream
                val payload = Payload.fromStream(pipes[0])
                connectionsClient.sendPayload(endpointId, payload)
                    .addOnSuccessListener { AppLog.i("NearbyManager: [OK] Tablet->Phone stream payload registered.") }
                    .addOnFailureListener { e ->
                        failAttempt(attempt)
                        AppLog.e("NearbyManager: [ERROR] Failed to send stream: ${e.message}")
                    }
            } catch (e: Exception) {
                AppLog.e("NearbyManager: Failed to build stream tunnel", e)
                failAttempt(attempt)
            }
            if (!sock.isClosed) {
                currentCoroutineContext().ensureActive()
                val admission = connectionAdmission(attempt)
                // Keep the connect coroutine a child of this attempt, including its suspension
                // in CommManager while the previous connection is being retired.
                handOverTunnel(attempt, sock, admission, ticket)
            }
        }
    }

    // Await the consumer's final ownership decision, not just socket creation. Rejection and
    // other failures retire only this attempt; a later attempt's endpoint and pipes stay intact.
    internal suspend fun handOverTunnel(
        attempt: NearbyAttemptGuard.Attempt, sock: Socket, admission: ConnectionAdmission,
        ticket: Long,
    ) {
        if (!commManager.acceptsAppConnectionTicket(ticket)) {
            // Close only this tunnel. Retiring an obsolete attempt cannot touch its replacement.
            try { sock.close() } catch (_: Exception) {}
            failAttempt(attempt)
            return
        }
        try {
            onSocketReady(sock, admission, ticket, attempt.save)
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
