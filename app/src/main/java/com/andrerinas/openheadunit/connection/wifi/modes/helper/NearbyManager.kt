package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
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
    private var activeNearbySocket: NearbySocket? = null
    private var activeEndpointId: String? = null
    private var activePipes: Array<ParcelFileDescriptor>? = null
    private var upgradeTimeoutJob: Job? = null
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
        activePipes?.forEach { try { it.close() } catch (e: Exception) {} }
        activePipes = null
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
        AppLog.i("NearbyManager: Requesting connection to endpoint: $endpointId")
        isConnecting = true

        connectionsClient.requestConnection(Build.MODEL, endpointId, connectionLifecycleCallback)
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
                    AppLog.i("NearbyManager: Connected successfully! Waiting up to 10s for bandwidth upgrade to HIGH quality (Wi-Fi)...")

                    // Start a 10-second timeout for the Wi-Fi bandwidth upgrade
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        delay(10_000)
                        if (activeNearbySocket == null && activeEndpointId == endpointId) {
                            AppLog.e("NearbyManager: Bandwidth upgrade timed out after 10s. Disconnecting to prevent Bluetooth fallback.")
                            scope.launch(Dispatchers.Main) {
                                ToastUtils.showToast(
                                    context,
                                    "Google Nearby connection failed: Wi-Fi bandwidth upgrade timed out. Please check Wi-Fi & Bluetooth settings.",
                                    Toast.LENGTH_LONG
                                )
                            }
                            stop()
                        }
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> AppLog.w("NearbyManager: Connection REJECTED by $endpointId")
                ConnectionsStatusCodes.STATUS_ERROR -> AppLog.e("NearbyManager: Connection ERROR with $endpointId")
                else -> AppLog.w("NearbyManager: Unknown connection result code: ${status.statusCode}")
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            AppLog.i("NearbyManager: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality}")
            if (bandwidthInfo.quality == BandwidthInfo.Quality.HIGH) {
                if (activeEndpointId == endpointId && activeNearbySocket == null) {
                    AppLog.i("NearbyManager: Wi-Fi Bandwidth Upgrade successful (Quality: HIGH). Initiating stream tunnel...")

                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = null

                    val socket = NearbySocket()
                    activeNearbySocket = socket

                    scope.launch(Dispatchers.IO) {
                        val sock = activeNearbySocket ?: return@launch

                        // [CRITICAL] Wait a bit before sending the payload.
                        // The phone (WirelessHelper) has a ~500ms delay in its connection logic.
                        // If we send too early, the phone won't have its 'activeNearbySocket'
                        // set yet, and our incoming stream will be dropped/ignored by the phone.
                        AppLog.i("NearbyManager: Waiting 800ms for phone state synchronization...")
                        delay(800)

                        // 1. Create outgoing pipe (Tablet -> Phone)
                        val pipes = ParcelFileDescriptor.createPipe()
                        activePipes = pipes
                        val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
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
            }
        }

        override fun onDisconnected(endpointId: String) {
            AppLog.i("NearbyManager: DISCONNECTED from $endpointId")
            if (activeEndpointId == endpointId) {
                activeEndpointId = null
                isConnecting = false
                upgradeTimeoutJob?.cancel()
                upgradeTimeoutJob = null
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            AppLog.i("NearbyManager: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                AppLog.i("NearbyManager: Received incoming STREAM payload. Completing bidirectional tunnel.")
                activeNearbySocket?.let { socket ->
                    socket.inputStreamWrapper = payload.asStream()?.asInputStream()
                    AppLog.i("NearbyManager: InputStream assigned to socket. Handshake should continue.")
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
            }
        }
    }
}
