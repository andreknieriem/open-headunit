package com.andrerinas.openheadunit.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val onSocketReady: (Socket) -> Unit
) {

    data class DiscoveredEndpoint(val id: String, val name: String)

    companion object {
        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.andrerinas.openhu"
    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private var isRunning = false
    private var isConnecting = false

    // Written on the Nearby callback thread, read from the upgrade-timeout and tunnel coroutines.
    @Volatile
    private var activeNearbySocket: NearbySocket? = null

    @Volatile
    private var activeEndpointId: String? = null
    // Written on the IO coroutine that builds the tunnel, read by stop() on whichever thread tears
    // the session down. Volatile for the same reason as activeNearbySocket above: without it a
    // stop() can read null and leave the pipes open.
    @Volatile
    private var activePipes: Array<android.os.ParcelFileDescriptor>? = null
    private var upgradeTimeoutJob: kotlinx.coroutines.Job? = null

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

    fun start() {
        if (!hasRequiredPermissions()) {
            AppLog.w("NearbyManager: Missing required location/bluetooth permissions. Skipping start.")
            return
        }
        if (isRunning) {
            AppLog.i("NearbyManager: Already running discovery.")
            return
        }
        AppLog.i("NearbyManager: Starting Nearby (Discoverer only)...")
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

    fun stop() {
        AppLog.i("NearbyManager: Stopping discovery and disconnecting from any active endpoint...")
        isRunning = false
        isConnecting = false
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        connectionsClient.stopDiscovery()
        activeEndpointId?.let {
            connectionsClient.disconnectFromEndpoint(it)
            activeEndpointId = null
        }
        activeNearbySocket?.close()
        activeNearbySocket = null
        pendingInboundStream?.let { try { it.close() } catch (e: Exception) {} }
        pendingInboundStream = null
        activePipes?.forEach { try { it.close() } catch (e: Exception) {} }
        activePipes = null
        lastQuality.clear()
        networkAtConnect = null
        _discoveredEndpoints.value = emptyList()
    }

    /**
     * Manually initiate a connection to a specific discovered endpoint.
     * Called from HomeFragment when user taps a device in the list.
     */
    fun connectToEndpoint(endpointId: String) {
        if (isConnecting) {
            AppLog.w("NearbyManager: Already connecting, ignoring request for $endpointId")
            return
        }
        // Auto-connect fires from onEndpointFound and the user can tap the same device in the list a
        // moment later. Once the first attempt has *succeeded*, isConnecting is already back to
        // false, so that guard alone let the second request through to be rejected with
        // STATUS_ALREADY_CONNECTED_TO_ENDPOINT -- an error line for what is simply a duplicate.
        if (activeEndpointId == endpointId) {
            AppLog.i("NearbyManager: Already connected to $endpointId, ignoring duplicate request")
            return
        }
        AppLog.i("NearbyManager: Requesting connection to endpoint: $endpointId")
        // Nothing has been reported about this attempt yet, so nothing may be carried into it.
        lastQuality.remove(endpointId)
        isConnecting = true
        
        connectionsClient.requestConnection(android.os.Build.MODEL, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e -> 
                AppLog.e("NearbyManager: Failed to request connection: ${e.message}") 
                isConnecting = false
            }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        AppLog.i("NearbyManager: Requesting Discovery with SERVICE_ID: $SERVICE_ID (Strategy: P2P_POINT_TO_POINT)")
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { AppLog.d("NearbyManager: [OK] Discovery started.") }
            .addOnFailureListener { e -> 
                AppLog.e("NearbyManager: [ERROR] Discovery failed: ${e.message}") 
                isRunning = false
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            AppLog.i("NearbyManager: Endpoint FOUND: ${info.endpointName} ($endpointId)")
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId }) {
                current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                _discoveredEndpoints.value = current
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

        override fun onEndpointLost(endpointId: String) {
            AppLog.i("NearbyManager: Endpoint LOST: $endpointId")
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AppLog.i("NearbyManager: Connection INITIATED with $endpointId (${info.endpointName}). Token: ${info.authenticationToken}")
            AppLog.i("NearbyManager: Automatically ACCEPTING connection...")
            
            // Save last connected device name for auto-reconnect
            AppLog.i("NearbyManager: Saving '${info.endpointName}' as last connected device candidate.")
            settings.lastNearbyDeviceName = info.endpointName

            // Stop discovery as soon as it finds the target.
            isRunning = false
            connectionsClient.stopDiscovery()
            
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> AppLog.e("NearbyManager: Failed to accept connection: ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status
            AppLog.i("NearbyManager: Connection RESULT for $endpointId: StatusCode=${status.statusCode} (${status.statusMessage})")
            
            if (status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                isConnecting = false
            }

            when (status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    isConnecting = false
                    activeEndpointId = endpointId
                    networkAtConnect = currentNetworkHandle()
                    AppLog.i("NearbyManager: Connected successfully!")

                    // The upgrade may already have been reported while this callback was in flight.
                    maybeBuildTunnel(endpointId)

                    // Only wait if that did not already build the tunnel. Announcing a wait first
                    // and arming the timeout unconditionally described the slow path in the logs of
                    // a session that took the fast one, and left a job running with nothing to do.
                    if (activeNearbySocket == null) {
                        AppLog.i("NearbyManager: Waiting up to 10s for bandwidth upgrade to HIGH quality (Wi-Fi)...")
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        kotlinx.coroutines.delay(10_000)
                        if (activeNearbySocket == null && activeEndpointId == endpointId) {
                            AppLog.e("NearbyManager: Bandwidth upgrade timed out after 10s (best quality seen: ${qualityName(lastQuality[endpointId])}). Disconnecting to prevent Bluetooth fallback.")
                            describeTunnelFailure()?.let { AppLog.e("NearbyManager: $it") }
                            scope.launch(Dispatchers.Main) {
                                ToastUtils.showToast(
                                    context, 
                                    "Google Nearby connection failed: Wi-Fi bandwidth upgrade timed out. Please check Wi-Fi & Bluetooth settings.", 
                                    android.widget.Toast.LENGTH_LONG
                                )
                            }
                            stop()
                        }
                    }
                    }
                }
                // Each of these forgets the endpoint's quality. Nearby reports bandwidth per
                // endpoint id and reuses the id across attempts, so a HIGH left over from a
                // connection that then failed would satisfy maybeBuildTunnel on the retry and
                // tunnel over whatever medium Nearby actually held -- skipping the upgrade wait
                // that exists to stop exactly that.
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    AppLog.w("NearbyManager: Connection REJECTED by $endpointId")
                    lastQuality.remove(endpointId)
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    AppLog.e("NearbyManager: Connection ERROR with $endpointId")
                    lastQuality.remove(endpointId)
                }
                else -> {
                    AppLog.w("NearbyManager: Unknown connection result code: ${status.statusCode}")
                    lastQuality.remove(endpointId)
                }
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            AppLog.i("NearbyManager: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality} (${qualityName(bandwidthInfo.quality)})")
            lastQuality[endpointId] = maxOf(lastQuality[endpointId] ?: Int.MIN_VALUE, bandwidthInfo.quality)
            maybeBuildTunnel(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            AppLog.i("NearbyManager: DISCONNECTED from $endpointId")
            if (activeEndpointId == endpointId) {
                activeEndpointId = null
                isConnecting = false
                upgradeTimeoutJob?.cancel()
                upgradeTimeoutJob = null
            }
            lastQuality.remove(endpointId)
        }
    }

    /**
     * Builds the stream tunnel once both preconditions hold, whichever callback satisfies the last
     * one. Called from [ConnectionLifecycleCallback.onConnectionResult] and
     * [ConnectionLifecycleCallback.onBandwidthChanged]; both run on the Nearby callback thread, so
     * the check-then-set on [activeNearbySocket] is not racing itself.
     *
     * Splitting this out of the bandwidth callback removes an ordering assumption: HIGH reported
     * before the connection result had recorded the endpoint used to be dropped on the floor, and
     * Nearby does not report it again.
     */
    private fun maybeBuildTunnel(endpointId: String) {
        if (activeEndpointId != endpointId) return
        if (activeNearbySocket != null) return
        if (lastQuality[endpointId] != BandwidthInfo.Quality.HIGH) return

        AppLog.i("NearbyManager: Wi-Fi Bandwidth Upgrade successful (Quality: HIGH). Initiating stream tunnel...")

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

        scope.launch(Dispatchers.IO) {
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
                    AppLog.e("NearbyManager: [ERROR] Failed to send stream: ${e.message}")
                }

            // [CRITICAL] Start AA handshake immediately.
            // NearbySocket.read() will block internally until Phone stream arrives.
            AppLog.i("NearbyManager: Starting AA handshake now. Input will block until stream arrives.")
            onSocketReady(sock)
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

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            AppLog.i("NearbyManager: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                AppLog.i("NearbyManager: Received incoming STREAM payload. Completing bidirectional tunnel.")
                val inbound = payload.asStream()?.asInputStream()
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
            } else if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes() ?: byteArrayOf())
                AppLog.i("NearbyManager: Received BYTES payload: $msg")
                if (msg == "PING") {
                    AppLog.i("NearbyManager: Received PING from Phone. Connections are alive.")
                }
            }
        }


        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
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
