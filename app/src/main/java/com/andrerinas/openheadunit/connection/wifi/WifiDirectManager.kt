package com.andrerinas.openheadunit.connection.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.NativeHandoffPolicy
import com.andrerinas.openheadunit.aap.P2pChannelPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket



class WifiDirectManager(private val context: Context) : WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

    private companion object {
        private const val MAX_NATIVE_5GHZ_CREATE_RETRIES = 4
        private const val MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES = 2
        private const val MAX_NATIVE_STANDARD_CREATE_RETRIES = 3
        private const val NATIVE_GROUP_MODE_UNKNOWN = "unknown"
        private const val NATIVE_GROUP_MODE_5GHZ_REQUESTED = "5GHz requested"
        private const val NATIVE_GROUP_MODE_STANDARD_FALLBACK = "standard fallback"
        private const val NATIVE_GROUP_MODE_STANDARD_LEGACY = "standard (no 5GHz API)"
        // Native AA join recovery: if no phone joins the quiet-host group within this window,
        // tear it down and recreate a fresh one (bounded), dropping the forced 5GHz band after
        // a couple of tries. 60s not 30s — a live BT reconnect was observed taking ~35s even
        // when working, so 30s left no margin.
        private const val NATIVE_JOIN_TIMEOUT_MS = 60000L
        private const val MAX_NATIVE_JOIN_RECREATES = 4
        private const val NATIVE_FORCE_STANDARD_AFTER = 2
    }

    @Volatile private var manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false
    private var isConnected = false
    // Fields for checkStuckRetryBurst() below: track how tightly consecutive
    // CONNECTION_CHANGED broadcasts repeat, to detect a stuck retry loop.
    private var lastConnChangedElapsedMs = 0L
    private var tightBurstCount = 0
    private val burstGapMs = 800L
    private val burstTriggerCount = 5
    @Volatile private var isGroupCreatingOrCreated = false
    // Guards against two concurrent checkGroupAndCreate() runs racing on the same teardown
    // (makeVisible() can be invoked twice back to back for one UI action). Cleared by a
    // bounded safety timeout in case a call site misses its own reset.
    @Volatile private var checkGroupAndCreateInFlight = false
    // Whether a phone has actually joined the group (not just that a group exists).
    // discoveryRunnable below re-advertises while this is false, and restarts if a
    // joined client disconnects.
    private var isClientConnected = false
    private val discoveryRunnable = object : Runnable {
        override fun run() {
            if (!isClientConnected) {
                // Skip while a teardown/recreate is in flight (reuse path or the stuck-retry
                // self-heal) — the group is disappearing/reforming underneath us, so a
                // discoverPeers() call here is wasted at best.
                if (!checkGroupAndCreateInFlight) {
                    startDiscovery()
                }
                handler.postDelayed(this, 10000L) // Repeat every 10s to stay visible
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var localDeviceAddress: String? = null
    private var lastKnownBssid: String? = null
    private var isReceiverRegistered = false
    private var discoveredInterface: String? = null
    private var nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
    private var native5GhzBandMismatchRetries = 0

    /**
     * SSID of the last group reported as being on a channel most phones cannot join, so the several
     * [onGroupInfoAvailable] callbacks a single group produces are said once.
     *
     * The SSID and not the interface or the BSSID: Android generates a fresh DIRECT-xy-… per group,
     * it is populated on the very first callback, and it is never privacy-masked. `interface` is
     * null until an IP is up and the BSSID is often 02:00:00:00:00:00 here, so either would both
     * split one group across two identities and merge two groups into one.
     */
    private var lastUnfriendlyChannelSsid: String? = null
    private var lastCoexistenceSsid: String? = null
    private var lastNativeGroupStatusMessage: String? = null

    // Native AA join recovery state. The watchdog fires if the phone never joins our quiet-host
    // group; nativeRecreateCount bounds how many times we recreate before giving up.
    private var nativeRecreateCount = 0
    private val nativeJoinWatchdog = Runnable {
        if (isClientConnected) return@Runnable
        if (isNativeSessionConnected?.invoke() == true) {
            // Native joins are out-of-band over Bluetooth, not P2P invitation, so clientList (and
            // isClientConnected) can stay empty forever even on a fully working session.
            AppLog.i("WifiDirectManager: Native AA join watchdog fired but a session is already connected — cancelling recovery, not tearing down a working connection.")
            cancelNativeJoinWatchdog()
            nativeRecreateCount = 0
            return@Runnable
        }
        if (isNativeHandshakeInFlight?.invoke() == true) {
            // A handshake is exchanging credentials right now, or the phone is still joining on
            // credentials we just handed it; recreating here would hand out a new SSID mid-flight.
            AppLog.i("WifiDirectManager: Native AA join watchdog fired but a Bluetooth handshake or handoff is in flight — deferring recovery.")
            armNativeJoinWatchdog()
            return@Runnable
        }
        recoverNativeGroup("no phone joined within ${NATIVE_JOIN_TIMEOUT_MS / 1000}s")
    }

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null
    // Set by AapService: whether NativeAaHandshakeManager has a live handshake in progress *or*
    // a delivered handoff still settling (the phone associating/doing DHCP after Type 3), so the
    // join watchdog/self-heal never tears the group down mid-exchange or mid-join.
    private var isNativeHandshakeInFlight: (() -> Boolean)? = null
    // Set by AapService: whether a real AA session is connected - isClientConnected can't tell
    // that apart from nobody joining (see nativeJoinWatchdog above).
    private var isNativeSessionConnected: (() -> Boolean)? = null
    // Set by AapService: called right before a native group is torn down, to invalidate any
    // not-yet-captured credentials in NativeAaHandshakeManager.
    private var onNativeGroupInvalidated: (() -> Unit)? = null

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        this.onCredentialsReady = callback
    }

    fun setNativeHandshakeStateProvider(provider: () -> Boolean) {
        this.isNativeHandshakeInFlight = provider
    }

    fun setNativeSessionConnectedProvider(provider: () -> Boolean) {
        this.isNativeSessionConnected = provider
    }

    fun setNativeGroupInvalidatedListener(callback: () -> Unit) {
        this.onNativeGroupInvalidated = callback
    }



    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    AppLog.i("WifiDirectManager: WIFI_P2P_STATE_CHANGED_ACTION state=$state")
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        val appSettings = App.provide(context).settings
                        val commManager = App.provide(context).commManager
                        val isConnectingOrConnected = commManager.isConnected ||
                            commManager.connectionState.value is CommManager.ConnectionState.Connecting

                        if (!isConnected && !isConnectingOrConnected && !isGroupCreatingOrCreated) {
                            if (appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT) {
                                AppLog.i("WifiDirectManager: P2P enabled, auto-starting WiFi Direct visibility")
                                makeVisible()
                            } else if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
                                AppLog.i("WifiDirectManager: P2P enabled, auto-starting Native AA quiet host")
                                startNativeAaQuietHost()
                            }
                        }
                    } else {
                        isGroupCreatingOrCreated = false
                        isConnected = false
                        isClientConnected = false
                        cancelNativeJoinWatchdog()
                        nativeRecreateCount = 0
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let {
                        if (App.provide(context).settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
                            AppLog.i("WifiDirectManager: Local name: ${it.deviceName}, Address: ${it.deviceAddress}")
                        }
                        AapService.wifiDirectName.value = it.deviceName
                        localDeviceAddress = it.deviceAddress
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        AppLog.i("WifiDirectManager: Connected. Requesting info...")
                        checkStuckRetryBurst()
                        // [FIX] Pre-fetch localDeviceAddress here so it's ready before
                        // onGroupInfoAvailable fires — reduces race condition window.
                        WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                            if (localDeviceAddress == null || localDeviceAddress == "00:00:00:00:00:00" || localDeviceAddress == "02:00:00:00:00:00") {
                                AppLog.d("WifiDirectManager: Pre-fetched localDeviceAddress on connect: $address")
                                localDeviceAddress = address
                            }
                        }
                        manager?.requestConnectionInfo(channel, this@WifiDirectManager)
                        AapService.scanningState.value = false
                    } else {
                        isConnected = false
                        isClientConnected = false
                        lastNativeGroupStatusMessage = null
                        isGroupCreatingOrCreated = false
                        cancelNativeJoinWatchdog()
                    }
                }
            }
        }
    }

    init {
        try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
                AppLog.i("WifiDirectManager: Device supports WiFi Direct. Initializing...")
                manager?.let { mgr ->
                    channel = mgr.initialize(context, context.mainLooper, null)

                    WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                        AppLog.i("WifiDirectManager: requestDeviceInfo success: $address")
                        localDeviceAddress = address
                    }

                    registerReceiverIfNeeded()
                } ?: run {
                    AppLog.e("WifiDirectManager: WIFI_P2P_SERVICE manager is NULL!")
                }
            } else {
                AppLog.e("WifiDirectManager: Device does NOT report FEATURE_WIFI_DIRECT!")
            }
        } catch (e: SecurityException) {
            AppLog.w("WifiDirectManager: WiFi Direct unavailable — permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Unexpected error in init", e)
        }
    }

    // Self-heals a stuck PROV-DISC retry loop (no AAP session ever connects, so nothing
    // triggers checkGroupAndCreate()'s own teardown) by detecting how tightly
    // CONNECTION_CHANGED repeats: ~200-300ms apart when stuck vs 1.1-1.9s on a real connection.
    @SuppressLint("MissingPermission")
    private fun checkStuckRetryBurst() {
        val appSettings = App.provide(context).settings
        val isHelperP2p = appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT
        val isNative = appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE
        if ((!isHelperP2p && !isNative) || !isGroupOwner) {
            tightBurstCount = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        val gap = now - lastConnChangedElapsedMs
        lastConnChangedElapsedMs = now

        tightBurstCount = if (gap in 1 until burstGapMs) tightBurstCount + 1 else 1

        if (tightBurstCount >= burstTriggerCount) {
            AppLog.w("WifiDirectManager: Detected $tightBurstCount CONNECTION_CHANGED repeats <${burstGapMs}ms apart — stuck retry loop against an already-consumed group. Self-healing.")
            tightBurstCount = 0
            if (isNative) {
                if (isNativeHandshakeInFlight?.invoke() == true) {
                    AppLog.i("WifiDirectManager: Native AA stuck-retry-burst detected but a Bluetooth handshake is in flight — skipping recovery.")
                    return
                }
                // Native quiet-host has no discovery loop; recreate the group (bounded), the
                // same class of self-heal as the helper path.
                recoverNativeGroup("stuck PROV-DISC retry loop")
                return
            }
            val mgr = manager
            val ch = channel
            if (mgr != null && ch != null) {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { createNewGroup(0) }
                    override fun onFailure(reason: Int) {
                        AppLog.w("WifiDirectManager: removeGroup during stuck-loop self-heal failed: ${getP2pErrorString(reason)}")
                        createNewGroup(0)
                    }
                })
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
            AppLog.d("WifiDirectManager: BroadcastReceiver registered.")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Failed to register receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            isConnected = true
            AapService.scanningState.value = false
            isGroupOwner = info.isGroupOwner

            val goIp = info.groupOwnerAddress?.hostAddress ?: "unknown"
            AppLog.i("WifiDirectManager: Group formed. Owner: $isGroupOwner, GO IP: $goIp")

            if (isGroupOwner) {
                // [FIX] requestDeviceInfo is async — call requestGroupInfo only AFTER the callback
                // fires so that localDeviceAddress is guaranteed to be set before onGroupInfoAvailable
                // runs. This eliminates the race condition that caused empty BSSIDs on Android 12+.
                WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                    AppLog.i("WifiDirectManager: Updated localDeviceAddress via requestDeviceInfo: $address")
                    localDeviceAddress = address
                    manager?.requestGroupInfo(channel, this@WifiDirectManager)
                }
                // Fallback: if requestDeviceInfo is not supported (< API 29), call directly
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    manager?.requestGroupInfo(channel, this)
                }
            } else if (info.groupOwnerAddress != null) {
                Thread {
                    var socket: Socket? = null
                    try {
                        AppLog.i("WifiDirectManager: Pinging Phone (GO) at $goIp to announce tablet...")
                        socket = Socket()
                        socket.connect(InetSocketAddress(info.groupOwnerAddress, 5289), 2000)
                    } catch (e: Exception) {
                        AppLog.w("WifiDirectManager: Ping to GO failed: ${e.message}")
                    } finally {
                        try { socket?.close() } catch (e: Exception) {}
                    }
                }.start()
            }
        } else {
            AppLog.d("WifiDirectManager: onConnectionInfoAvailable: group not formed yet")
            isConnected = false
            isGroupOwner = false
        }
    }

    private var groupInfoRetries = 0

    @SuppressLint("MissingPermission")
    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        val appSettings = App.provide(context).settings
        if (group != null) {
            // [FIX] Check if Location Services (GPS) are enabled.
            // On Android 10+, BSSID is often masked if GPS is OFF.
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                AppLog.i("WifiDirectManager: System Location Check: GPS=$isGpsEnabled, Network=$isNetworkEnabled")
                if (!isGpsEnabled && !isNetworkEnabled) {
                    AppLog.w("WifiDirectManager: WARNING - Location Services are DISABLED. BSSID will likely be masked (00:00...)!")
                }
            } catch (e: Exception) {
                AppLog.w("WifiDirectManager: Failed to check Location Services status: ${e.message}")
            }

            groupInfoRetries = 0
            val ssid = group.networkName
            val psk = group.passphrase ?: ""
            val isOwner = group.isGroupOwner

            // [FIX] Track whether a phone client has actually joined our group.
            // If we are the Group Owner and the client list is empty, no phone has connected yet.
            // If the client list becomes non-empty, a phone joined — stop the discovery loop.
            // If the client list becomes empty again (phone disconnected), restart the loop.
            if (isOwner) {
                val clients = group.clientList
                val hadClient = isClientConnected
                isClientConnected = clients != null && clients.isNotEmpty()
                if (isClientConnected) {
                    // A phone joined — stop the native join watchdog and reset its budget.
                    cancelNativeJoinWatchdog()
                    nativeRecreateCount = 0
                }
                if (hadClient && !isClientConnected) {
                    // [BUG_FIX] Never rediscover on the Native AA path: it is a *quiet* host, the
                    // phone finds us by SSID from the credentials handed over Bluetooth, and
                    // discoverPeers() takes the group owner off-channel every 10 s. Starting that
                    // loop when a phone drops mid-DHCP leaves every retry stuck at "Obtaining IP
                    // address".
                    if (NativeHandoffPolicy.shouldRestartDiscovery(
                            nativeAaMode = isNativeAaMode(),
                            hadClient = hadClient,
                            hasClient = isClientConnected)) {
                        AppLog.i("WifiDirectManager: Client disconnected from P2P group. Restarting discovery loop.")
                        startDiscoveryLoop()
                    } else {
                        AppLog.i("WifiDirectManager: Client left the Native AA group; staying a quiet host instead of rediscovering.")
                    }
                    if (isNativeAaMode()) armNativeJoinWatchdog()
                }
            } else {
                isClientConnected = true
            }

            // [FIX] Robust interface detection. group.interface is often null on Android 11+ (hidden API)
            var iface = group.`interface`
            if (iface.isNullOrEmpty()) {
                iface = getInterfaceByIp("192.168.49.1")
                if (iface != null) {
                    AppLog.i("WifiDirectManager: Discovered interface name by IP 192.168.49.1: $iface")
                }
            }
            discoveredInterface = iface
            val isBssidSet = appSettings.staticBSSID != "0"

            var bssid = if (appSettings.staticBSSID == "0" || appSettings.staticBSSID == null) {
                getWifiDirectMac(iface)
            } else {
                appSettings.staticBSSID
            }
            if (isBssidSet) {
                AppLog.i("WifiDirectManager: Initial BSSID from App settings: $bssid")
            } else {
                AppLog.i("WifiDirectManager: Initial BSSID from scan: $bssid")
            }



            // [FIX] Robust BSSID detection for masked MACs (00:00 or 02:00)
            if (bssid == "00:00:00:00:00:00" || bssid == "02:00:00:00:00:00") {
                AppLog.i("WifiDirectManager: BSSID is masked. Starting fallbacks...")

                // Fallback 1: Use last known valid BSSID
                if (!lastKnownBssid.isNullOrEmpty() && lastKnownBssid != "00:00:00:00:00:00" && lastKnownBssid != "02:00:00:00:00:00") {
                    AppLog.i("WifiDirectManager: Fallback 1 - Using lastKnownBssid: $lastKnownBssid")
                    bssid = lastKnownBssid!!
                }
                // Fallback 2: Use captured localDeviceAddress
                else if (!localDeviceAddress.isNullOrEmpty() && localDeviceAddress != "00:00:00:00:00:00" && localDeviceAddress != "02:00:00:00:00:00") {
                    AppLog.i("WifiDirectManager: Fallback 2 - Using localDeviceAddress: $localDeviceAddress")
                    bssid = localDeviceAddress!!
                }
                // Fallback 3: Use group.owner.deviceAddress
                else {
                    val ownerAddr = group.owner?.deviceAddress
                    AppLog.i("WifiDirectManager: Fallback 3 - group.owner.deviceAddress: $ownerAddr")
                    if (!ownerAddr.isNullOrEmpty() && ownerAddr != "00:00:00:00:00:00" && ownerAddr != "02:00:00:00:00:00") {
                        AppLog.i("WifiDirectManager: Fallback 3 - Selected group.owner.deviceAddress: $ownerAddr")
                        bssid = ownerAddr
                    } else {
                        AppLog.i("WifiDirectManager: Fallback 4 - Attempting shell/sysfs for $iface...")
                        val shellMac = getMacFromShell(iface)
                        if (shellMac != null) {
                            AppLog.i("WifiDirectManager: Fallback 4 - Selected shell/sysfs MAC: $shellMac")
                            bssid = shellMac
                        } else {
                            // Fallback 5: Try Settings.Secure (Samsung/Pixel trick)
                            var resolved = false
                            try {
                                val secureMac = Settings.Secure.getString(context.contentResolver, "wifi_p2p_device_address")
                                if (!secureMac.isNullOrEmpty() && secureMac != "00:00:00:00:00:00" && secureMac != "02:00:00:00:00:00") {
                                    AppLog.i("WifiDirectManager: Fallback 5 - Selected MAC from Settings.Secure: $secureMac")
                                    bssid = secureMac
                                    resolved = true
                                }
                            } catch (e: Exception) {
                                AppLog.w("WifiDirectManager: Fallback 5 failed: ${e.message}")
                            }

                            // Fallback 6: Reflect over WifiP2pGroup/WifiP2pDevice hidden fields for
                            // any unmasked MAC the public getters didn't expose (some OEM privacy
                            // hardening masks NetworkInterface/deviceAddress but leaves other
                            // internal fields populated).
                            if (!resolved) {
                                val reflectedMac = getMacFromReflection(group)
                                if (reflectedMac != null) {
                                    AppLog.i("WifiDirectManager: Fallback 6 - Selected MAC via reflection: $reflectedMac")
                                    bssid = reflectedMac
                                } else {
                                    AppLog.w("WifiDirectManager: All fallbacks failed! BSSID is still zeroed.")
                                }
                            }
                        }
                    }
                }
            }

            if (bssid != "00:00:00:00:00:00" && bssid != "02:00:00:00:00:00") {
                lastKnownBssid = bssid
            }

            // Try to get frequency via reflection (hidden field in WifiP2pGroup)
            var frequency = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10 (API 29) and above, use the official public API via WifiDirectCompat
                frequency = WifiDirectCompat.getGroupFrequency(group)
            } else {
                try {
                    // Try several common field names used by different OEMs
                    val fieldNames = arrayOf("frequency", "mFrequency")
                    for (name in fieldNames) {
                        try {
                            val field = group.javaClass.getDeclaredField(name)
                            field.isAccessible = true
                            frequency = field.getInt(group)
                            if (frequency > 0) break
                        } catch (e: Exception) {
                        }
                    }
                } catch (e: Exception) {
                }
            }

            val band = if (frequency > 4000) "5GHz" else if (frequency > 0) "2.4GHz" else "unknown"
            val channelLabel = if (P2pChannelPolicy.is24GHz(frequency)) ", ${P2pChannelPolicy.describe(frequency)}" else ""
            AppLog.i("WifiDirectManager: onGroupInfoAvailable: SSID: $ssid, BSSID: $bssid, GO: $isOwner, IFACE: ${iface ?: "null"}, Freq: $frequency MHz ($band$channelLabel)")

            // Runs before the channel report below, which is the order the Native AA branch used to
            // impose from inside itself: a group that is about to be torn down and remade on 5GHz
            // must not first tell the user to restart their WiFi. The retry regenerates the SSID
            // every time, so the report's per-SSID dedupe would not have suppressed the repeats.
            if (isNativeAaMode() && isOwner) {
                if (frequency > 4000) {
                    native5GhzBandMismatchRetries = 0
                } else if (shouldRetryNativeGroupFor5Ghz(frequency)) {
                    native5GhzBandMismatchRetries++
                    AppLog.w("WifiDirectManager: Native AA group was requested as 5GHz but came up on $frequency MHz ($band). Recreating 5GHz group (mismatch retry $native5GhzBandMismatchRetries/$MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES).")
                    showToast("Native AA WiFi Direct started on $band. Retrying 5GHz...")
                    removeGroupAndRetryNative5Ghz()
                    return
                }
            }

            if (isOwner) {
                logStationCoexistence(ssid, frequency)

                // A group above channel 11 is up and beaconing and still invisible to most phones:
                // a client in the FCC domain associates on channels 1-11 only and will not even
                // list the SSID in a scan, so the phone reports nothing worse than "can't find the
                // network". Report it and carry on — recreating the group does not help, because
                // the channel is picked when the WiFi radio comes up, not when the group is made:
                // measured on a unit where this failed as six recreates, 2467 MHz every time. Only
                // a WiFi restart, or a country code the driver will honour, moves it.
                //
                // Said once per group, not once per callback: requestGroupInfo() is issued from
                // several places, so this runs three or four times for one group.
                if (P2pChannelPolicy.isClientUnfriendly(frequency)) {
                    if (ssid != lastUnfriendlyChannelSsid) {
                        lastUnfriendlyChannelSsid = ssid
                        AppLog.e("WifiDirectManager: WiFi Direct group came up on ${P2pChannelPolicy.describe(frequency)} ($frequency MHz). Carrying on, but a phone limited to channels 1-11 will not find this network: it will scan and never see the SSID. Restarting this unit's WiFi, or giving it a WiFi country code, is what moves the group off channel 12/13.")
                        showToast("WiFi Direct is on ${P2pChannelPolicy.describe(frequency)}, which most phones cannot join. Restart WiFi and try again.")
                    }
                } else if (frequency > 0) {
                    lastUnfriendlyChannelSsid = null
                }
            }

            if (isNativeAaMode() && isOwner) {
                notifyNativeGroupStarted(ssid, frequency, band)
                // The group is up. If no phone joins within the window, recover (recreate fresh).
                armNativeJoinWatchdog()
            }

            if (ssid.isNotEmpty()) {
                // Wait for the IP address to be assigned to the interface
                Thread {
                    try {
                        var ip = getWifiDirectIp(iface)
                        var retries = 0
                        while (ip == null && retries < 15) {
                            AppLog.d("WifiDirectManager: Waiting for IP on interface ${iface ?: "any p2p"} (Attempt ${retries + 1}/15)...")
                            Thread.sleep(1000)
                            ip = getWifiDirectIp(iface)
                            retries++
                        }

                        // For Native AA, we almost always expect 192.168.49.1 if we are GO
                        val finalIp = ip ?: (if (isOwner) "192.168.49.1" else null)
                        if (finalIp != null && bssid != null) {
                            AppLog.i("WifiDirectManager: SUCCESS - Providing credentials to listener. SSID=$ssid, IP=$finalIp, BSSID=$bssid")
                            onCredentialsReady?.invoke(ssid, psk, finalIp, bssid)
                        } else {
                            AppLog.e("WifiDirectManager: FAILED to get valid IP for credentials delivery.")
                        }
                    } catch (e: Exception) {
                        AppLog.e("WifiDirectManager: Error in credential delivery thread", e)
                    }
                }.start()
            }
        } else {
            if (groupInfoRetries < 20) {
                groupInfoRetries++
                AppLog.w("WifiDirectManager: Group info was null! Retrying in 1s (Attempt $groupInfoRetries/20)...")
                handler.postDelayed({
                    channel?.let { ch ->
                        manager?.requestGroupInfo(ch, this)
                    }
                }, 1000L)
            } else {
                AppLog.e("WifiDirectManager: FATAL: Group info remained null after 20 retries.")
                groupInfoRetries = 0
            }
        }
    }

    /**
     * Reports whether this unit is also joined to an ordinary WiFi network while hosting the group.
     *
     * One radio serving a station link and a group owner at once has to divide its time between
     * them, and on a single-channel chipset that shows up as the projected video and audio going
     * dead together for a few hundred milliseconds at a time, over and over, with nothing wrong
     * anywhere in the app. It is invisible from a log otherwise, and it is not rare: a dashcam or
     * a phone hotspot the unit reconnects to on its own is enough.
     *
     * Frequencies make the diagnosis exact when both are known: the same channel is shared airtime,
     * different channels means the radio is also retuning between them. Several head units report
     * the group frequency as 0, so say what is known and do not withhold the warning over it.
     *
     * Logged once per group rather than once per callback, because requestGroupInfo() is issued
     * several places, so this runs three or four times for one group.
     */
    private fun logStationCoexistence(ssid: String, groupFrequency: Int) {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo ?: return
            // supplicantState, not networkId or SSID. Both of those are redacted to -1 and
            // "<unknown ssid>" whenever the caller cannot satisfy the location gate, which on a
            // head unit is routine (the service runs without the projection activity in front),
            // so keying on either would silently report "not associated" on the newer Android
            // versions where this diagnosis is worth having. supplicantState survives redaction.
            if (info.supplicantState != SupplicantState.COMPLETED) {
                lastCoexistenceSsid = null
                return
            }
            if (ssid == lastCoexistenceSsid) return
            lastCoexistenceSsid = ssid

            val staFrequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.frequency
            } else 0
            val relation = when {
                staFrequency <= 0 || groupFrequency <= 0 -> "one of the two frequencies is unavailable"
                staFrequency == groupFrequency -> "same channel, the two networks share airtime"
                else -> "different channels, the radio has to switch between them"
            }
            AppLog.w("WifiDirectManager: This unit is connected to another WiFi network while hosting the WiFi Direct group (station $staFrequency MHz, group $groupFrequency MHz: $relation). One radio serving both can stall projected video and audio together for a few hundred milliseconds at a time. Disconnecting the other network, or using the head unit hotspot instead, removes the contention.")
        } catch (e: Exception) {
            AppLog.d("WifiDirectManager: Could not read station state for coexistence check: ${e.message}")
        }
    }

    private fun getInterfaceByIp(targetIp: String): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress == targetIp) return iface.name
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun getWifiDirectMac(ifaceName: String?): String {
        AppLog.d("WifiDirectManager: getWifiDirectMac for interface: ${ifaceName ?: "any"}")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val mac = iface.hardwareAddress
                val macStr = if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    sb.toString()
                } else "null"

                AppLog.i("WifiDirectManager: Found interface: ${iface.name}, MAC: $macStr")

                // If we have a name, it must match.
                if (ifaceName != null && iface.name != ifaceName) continue

                // If we don't have a name, look for common P2P interface patterns
                if (ifaceName == null) {
                    val name = iface.name.lowercase()
                    if (!name.contains("p2p") && !name.contains("wlan") && !name.contains("ap")) continue
                }

                if (macStr != "null" && macStr != "00:00:00:00:00:00" && macStr != "02:00:00:00:00:00") {
                    AppLog.d("WifiDirectManager: Selected MAC for ${iface.name}: $macStr")
                    return macStr
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error scanning network interfaces", e)
        }
        AppLog.w("WifiDirectManager: No valid MAC found in NetworkInterface scan for ${ifaceName ?: "any"}")
        return "00:00:00:00:00:00"
    }

    private fun getWifiDirectIp(ifaceName: String?): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        // Prioritize explicitly requested interface
                        if (ifaceName != null && iface.name == ifaceName) return addr.hostAddress

                        // Fallback: search for 192.168.49.1 (Standard P2P GO IP)
                        if (addr.hostAddress == "192.168.49.1") return addr.hostAddress

                        // Fallback: search for any interface with "p2p" in name
                        if (ifaceName == null && iface.name.lowercase().contains("p2p")) return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error getting local IP", e)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        registerReceiverIfNeeded()
        val mgr = manager ?: return
        val ch = channel ?: return

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.w("WifiDirectManager: WiFi is disabled. Cannot start P2P discovery.")
            ToastUtils.showToast(context, context.getString(R.string.wifi_disabled_info), Toast.LENGTH_LONG)
            isGroupCreatingOrCreated = false
            return
        }

        isGroupCreatingOrCreated = true

        // Reflection Hack to set name
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "OpenHU", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Name set to OpenHU") }
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}

        // 1. Stop any ongoing discovery
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { checkGroupAndCreate() }
            override fun onFailure(reason: Int) { checkGroupAndCreate() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun checkGroupAndCreate() {
        if (checkGroupAndCreateInFlight) {
            AppLog.d("WifiDirectManager: checkGroupAndCreate already in flight, skipping duplicate call")
            return
        }
        checkGroupAndCreateInFlight = true
        handler.postDelayed({ checkGroupAndCreateInFlight = false }, 8000L)

        isGroupOwner = false
        isConnected = false

        manager?.requestGroupInfo(channel) { group ->
            if (group == null) {
                AppLog.i("No existing P2P group, creating new one")
                checkGroupAndCreateInFlight = false
                createNewGroup(0)
                return@requestGroupInfo
            }

            // Reusing an existing group desyncs the peer's WPS/PBC registrar into a tight
            // PROV-DISC retry storm (confirmed via live-device bisection against `ebab63a8`,
            // whose only change was skipping this teardown on reuse) — tear down and recreate
            // on every reuse rather than trying to detect which ones are broken.
            // NOTE: this used to also call deletePersistentGroup() to purge the profile (so a
            // plain createGroup() wouldn't reuse the same SSID/netId) — confirmed on-device that
            // call is rejected outright for every netId, including the profile's own real one.
            // Dropped; likely a permission this app doesn't hold.
            AppLog.i("WifiDirectManager: Existing P2P group found — removing and recreating fresh for a clean WPS/PBC registrar")
            // The next createGroup() call generates a brand-new GO interface with a new random
            // MAC — a cached BSSID from the group we're tearing down is now stale and must never
            // be delivered for the new one.
            lastKnownBssid = null
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { checkGroupAndCreateInFlight = false; createNewGroup(0) }
                override fun onFailure(reason: Int) {
                    AppLog.w("WifiDirectManager: removeGroup before recreate failed: ${getP2pErrorString(reason)}")
                    checkGroupAndCreateInFlight = false
                    createNewGroup(0)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return

        if (isConnected || isGroupOwner) {
            AppLog.d("WifiDirectManager: Group already active/created (isConnected=$isConnected, isGroupOwner=$isGroupOwner). Skipping createGroup retry.")
            return
        }

        lastKnownBssid = null

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: P2P Group created (fresh this session).")
                isGroupOwner = true
                tightBurstCount = 0
                lastConnChangedElapsedMs = 0L
                startDiscoveryLoop()
            }
            override fun onFailure(reason: Int) {
                if (reason == 2 && retryCount < 3) { // 2 = BUSY
                    AppLog.w("WifiDirectManager: Chip is BUSY, retrying in 2s...")
                    handler.postDelayed({ createNewGroup(retryCount + 1) }, 2000L)
                } else {
                    AppLog.e("WifiDirectManager: createGroup failed: $reason")
                    isGroupCreatingOrCreated = false
                }
            }
        })
    }

    private fun startDiscoveryLoop() {
        handler.removeCallbacks(discoveryRunnable)
        handler.post(discoveryRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel
        if (ch != null) {
            val appSettings = App.provide(context).settings
            if (appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT) {
                AapService.scanningState.value = true
            }
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    AppLog.d("WifiDirectManager: Discovery active")
                    if (appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT) {
                        handler.postDelayed({
                            if (!isClientConnected) {
                                AapService.scanningState.value = false
                            }
                        }, 2500L)
                    }
                }
                override fun onFailure(reason: Int) {
                    AppLog.w("WifiDirectManager: Discovery failed: $reason")
                    AapService.scanningState.value = false
                }
            })
        }
    }

    /**
     * Boomerang Hack: Briefly triggers system WiFi settings to wake up the radio.
     * Currently not used by default but kept in code for future use.
     */
    private fun triggerWifiSettings() {
        try {
            val intent = Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$WifiP2pSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }

        handler.postDelayed({
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            } catch (e: Exception) {}
        }, 800L)
    }

    @SuppressLint("MissingPermission")
    fun startNativeAaQuietHost() {
        registerReceiverIfNeeded()
        isGroupCreatingOrCreated = true
        var mgr = manager
        var ch = channel

        if (mgr == null || ch == null) {
            AppLog.w("WifiDirectManager: manager ($mgr) or channel ($ch) is null. Attempting re-init...")
            try {
                val newManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                val newChannel = newManager?.initialize(context, context.mainLooper, null)
                if (newManager != null && newChannel != null) {
                    manager = newManager
                    channel = newChannel
                    mgr = newManager
                    ch = newChannel
                    AppLog.i("WifiDirectManager: Re-init successful and fields updated.")
                    registerReceiverIfNeeded()
                } else {
                    AppLog.e("WifiDirectManager: Re-init failed. Cannot start Quiet Host.")
                    isGroupCreatingOrCreated = false
                    return
                }
            } catch (e: Exception) {
                AppLog.e("WifiDirectManager: Exception during re-init", e)
                isGroupCreatingOrCreated = false
                return
            }
        }

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.i("WifiDirectManager: WiFi is disabled but needed for Native AA. Attempting to enable...")
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            } else {
                showToast("Native AA requires Wi-Fi. Please turn it on.")
                // We return for now, the user must turn it on. In the future we could open settings.
                isGroupCreatingOrCreated = false
                return
            }
            // Wait a bit for WiFi to wake up
            handler.postDelayed({
                if (isGroupCreatingOrCreated) {
                    isGroupCreatingOrCreated = false
                    startNativeAaQuietHost()
                }
            }, 2000L)
            return
        }

        AppLog.i("WifiDirectManager: startNativeAaQuietHost() requested. Removing old group if any...")
        nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
        lastNativeGroupStatusMessage = null
        native5GhzBandMismatchRetries = 0
        lastUnfriendlyChannelSsid = null
        lastCoexistenceSsid = null
        nativeRecreateCount = 0
        cancelNativeJoinWatchdog()
        recreateNativeGroup(forceStandard = false)
    }

    private fun delayedCreateQuietGroup(retryCount: Int) {
        handler.postDelayed({ createQuietGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createQuietGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return

        AppLog.i("WifiDirectManager: Attempting createGroup for Native AA (Attempt $retryCount)...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Builder.build() requires networkName+passphrase (or a peer address) or it
                // throws IllegalStateException — onGroupInfoAvailable() reads the real values
                // back afterwards, same as the standard-fallback path.
                val config = WifiP2pConfig.Builder()
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    .setNetworkName(generateP2pNetworkName())
                    .setPassphrase(generateP2pPassphrase())
                    .build()

                AppLog.i("WifiDirectManager: Requesting Native AA P2P group on 5GHz band.")
                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        AppLog.i("WifiDirectManager: 5GHz createGroup SUCCESS!")
                        nativeGroupCreationMode = NATIVE_GROUP_MODE_5GHZ_REQUESTED
                        isGroupOwner = true
                        handler.postDelayed({
                            mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                            mgr.requestGroupInfo(ch, this@WifiDirectManager)
                        }, 1000L)
                    }
                    override fun onFailure(reason: Int) {
                        val reasonStr = getP2pErrorString(reason)
                        if (retryCount < MAX_NATIVE_5GHZ_CREATE_RETRIES) {
                            AppLog.w("WifiDirectManager: 5GHz createGroup failed ($reasonStr), removing group and retrying 5GHz in 2s (retry ${retryCount + 1}/$MAX_NATIVE_5GHZ_CREATE_RETRIES)...")
                            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() { handler.postDelayed({ createQuietGroup(retryCount + 1) }, 2000L) }
                                override fun onFailure(r: Int) { handler.postDelayed({ createQuietGroup(retryCount + 1) }, 2000L) }
                            })
                        } else {
                            AppLog.w("WifiDirectManager: 5GHz createGroup retries exhausted ($reasonStr). Falling back to standard createGroup.")
                            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
                        }
                    }
                })
                return
            } catch (t: Throwable) {
                if (retryCount < MAX_NATIVE_5GHZ_CREATE_RETRIES) {
                    AppLog.e("WifiDirectManager: 5GHz createGroup crashed before async result. Retrying 5GHz.", t)
                    handler.postDelayed({ createQuietGroup(retryCount + 1) }, 2000L)
                    return
                }
                AppLog.e("WifiDirectManager: 5GHz createGroup crashed and retries are exhausted. Falling back to standard.", t)
                standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
                return
            }
        }

        AppLog.i("WifiDirectManager: 5GHz P2P group request requires Android 10+. Using standard createGroup.")
        standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
    }

    private fun generateP2pNetworkName(): String {
        val suffix = AapService.wifiDirectName.value
            ?.filter { it.isLetterOrDigit() }
            ?.take(20)
            ?.ifEmpty { null } ?: "HeadUnit"
        val code = (('A'..'Z') + ('0'..'9')).let { pool -> "${pool.random()}${pool.random()}" }
        return "DIRECT-$code-$suffix"
    }

    private fun generateP2pPassphrase(): String {
        val pool = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..12).map { pool.random() }.joinToString("")
    }

    private fun getP2pErrorString(reason: Int): String {
        return when(reason) {
            0 -> "ERROR (Internal Error)"
            1 -> "P2P_UNSUPPORTED"
            2 -> "BUSY (System is busy, retry needed)"
            else -> "UNKNOWN ($reason)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun standardCreateGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, retryCount: Int, groupMode: String) {
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: Standard createGroup SUCCESS!")
                nativeGroupCreationMode = groupMode
                isGroupOwner = true
                handler.postDelayed({
                    mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                    mgr.requestGroupInfo(ch, this@WifiDirectManager)
                }, 1000L)
            }
            override fun onFailure(reason: Int) {
                val reasonStr = getP2pErrorString(reason)
                if (reason == 2 && retryCount < MAX_NATIVE_STANDARD_CREATE_RETRIES) {
                    AppLog.w("WifiDirectManager: standard createGroup failed ($reasonStr), removing group and retrying standard in 2s...")
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                        override fun onFailure(r: Int) { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                    })
                } else {
                    AppLog.e("WifiDirectManager: createQuietGroup failed completely! Reason: $reasonStr")
                    isGroupCreatingOrCreated = false
                }
            }
        })
    }

    private fun isNativeAaMode(): Boolean {
        return App.provide(context).settings.wifiConnectionMode == WifiLauncherMode.NATIVE
    }

    private fun shouldRetryNativeGroupFor5Ghz(frequency: Int): Boolean {
        return nativeGroupCreationMode == NATIVE_GROUP_MODE_5GHZ_REQUESTED &&
            frequency in 1..4000 &&
            native5GhzBandMismatchRetries < MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRetryNative5Ghz() {
        val mgr = manager ?: return
        val ch = channel ?: return
        // The next createGroup() call generates a brand-new GO interface with a new random
        // MAC — a cached BSSID from the group we're tearing down is now stale and must never
        // be delivered for the new one.
        lastKnownBssid = null
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { delayedCreateQuietGroup(0) }
            override fun onFailure(reason: Int) {
                AppLog.w("WifiDirectManager: removeGroup before 5GHz band-mismatch retry failed: ${getP2pErrorString(reason)}")
                delayedCreateQuietGroup(0)
            }
        })
    }

    /**
     * Tear down any current P2P group and create a fresh native quiet-host group.
     * [forceStandard] skips the 5GHz attempt, for phones that can't join a 5GHz group owner.
     *
     * Used to also call deletePersistentGroup() here, as the Helper mode path does, but on-device
     * that is rejected for every netId — likely a permission this app lacks. Dropped.
     */
    @SuppressLint("MissingPermission")
    private fun recreateNativeGroup(forceStandard: Boolean) {
        val mgr = manager ?: return
        val ch = channel ?: return
        lastKnownBssid = null
        // Let an in-progress credential wait pick up the fresh group's creds, not stale ones.
        onNativeGroupInvalidated?.invoke()
        val createFresh = {
            if (forceStandard) standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            else delayedCreateQuietGroup(0)
        }
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createFresh() }
            override fun onFailure(reason: Int) {
                AppLog.d("WifiDirectManager: Native AA removeGroup before recreate failed (reason=${getP2pErrorString(reason)}); expected if no group existed")
                createFresh()
            }
        })
    }

    /** Recover a native quiet-host group when the phone never joins: recreate a fresh one, and
     *  after a couple of tries drop the forced 5GHz band. Bounded by [MAX_NATIVE_JOIN_RECREATES]. */
    private fun recoverNativeGroup(reason: String) {
        cancelNativeJoinWatchdog()
        if (isClientConnected) return
        if (isNativeSessionConnected?.invoke() == true) {
            AppLog.i("WifiDirectManager: recoverNativeGroup() called but a session is already connected — not tearing down a working connection.")
            nativeRecreateCount = 0
            return
        }
        if (nativeRecreateCount >= MAX_NATIVE_JOIN_RECREATES) {
            AppLog.w("WifiDirectManager: Native AA — phone still not connected after $nativeRecreateCount recreations ($reason); giving up until the next start.")
            return
        }
        nativeRecreateCount++
        val forceStandard = nativeRecreateCount >= NATIVE_FORCE_STANDARD_AFTER
        AppLog.w("WifiDirectManager: Native AA recovery ($reason): recreate attempt $nativeRecreateCount/$MAX_NATIVE_JOIN_RECREATES${if (forceStandard) ", forcing 2.4GHz" else ""}.")
        recreateNativeGroup(forceStandard)
    }

    /** (Re)arm the native join watchdog; no-op unless we are a native-mode group owner with no
     *  client yet. */
    private fun armNativeJoinWatchdog() {
        handler.removeCallbacks(nativeJoinWatchdog)
        if (isNativeAaMode() && isGroupOwner && !isClientConnected && isNativeSessionConnected?.invoke() != true) {
            handler.postDelayed(nativeJoinWatchdog, NATIVE_JOIN_TIMEOUT_MS)
        }
    }

    private fun cancelNativeJoinWatchdog() {
        handler.removeCallbacks(nativeJoinWatchdog)
    }

    private fun notifyNativeGroupStarted(ssid: String, frequency: Int, band: String) {
        val frequencyText = if (frequency > 0) "$frequency MHz" else "frequency unknown"
        val message = "Native AA WiFi Direct: $band ($frequencyText), $nativeGroupCreationMode"
        if (message == lastNativeGroupStatusMessage) return

        lastNativeGroupStatusMessage = message
        AppLog.i("WifiDirectManager: $message, SSID=$ssid")
        showToast(message)
    }

    private fun showToast(message: String) {
        handler.post {
            ToastUtils.showToast(context, message, Toast.LENGTH_LONG)
        }
    }

    private val macRegex = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val maskedMacs = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /**
     * Last-resort BSSID lookup: reflect over every declared field (including inherited ones) of
     * the WifiP2pGroup and its owner WifiP2pDevice, looking for any String that looks like a MAC
     * and isn't one of the known privacy-masked placeholders. Some OEM builds mask the public
     * NetworkInterface/deviceAddress getters but leave other internal fields populated with the
     * real value.
     */
    private fun getMacFromReflection(group: WifiP2pGroup): String? {
        val candidates = listOfNotNull(group, group.owner)
        for (obj in candidates) {
            var klass: Class<*>? = obj.javaClass
            while (klass != null) {
                for (field in klass.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(obj) as? String ?: continue
                        if (macRegex.matches(value) && value !in maskedMacs) {
                            AppLog.d("WifiDirectManager: getMacFromReflection found candidate in ${klass.simpleName}.${field.name}: $value")
                            return value
                        }
                    } catch (e: Exception) {
                        // Ignore inaccessible/incompatible fields and keep scanning.
                    }
                }
                klass = klass.superclass
            }
        }
        return null
    }

    private fun getMacFromShell(iface: String?): String? {
        // Fallback: If iface is null, try to find a p2p interface name
        val targetIface = iface ?: getInterfaceByIp("192.168.49.1") ?: discoveredInterface

        if (targetIface != null) {
            // Try reading directly from sysfs
            try {
                val file = File("/sys/class/net/$targetIface/address")
                if (file.exists()) {
                    val mac = file.readText().trim().lowercase()
                    if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                        AppLog.i("WifiDirectManager: MAC retrieved via sysfs ($targetIface): $mac")
                        return mac
                    }
                }
            } catch (e: Exception) {}

            // Try ip link
            try {
                val process = Runtime.getRuntime().exec("ip link show $targetIface")
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val match = Regex("link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})").find(line ?: "")
                    if (match != null) {
                        val mac = match.groupValues[1].lowercase()
                        if (mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") return mac
                    }
                }
            } catch (e: Exception) {}
        }

        // LAST RESORT: Scan ALL interfaces in sysfs for anything that looks like P2P
        AppLog.i("WifiDirectManager: getMacFromShell: Target failed, scanning ALL interfaces in sysfs...")
        try {
            val netDir = File("/sys/class/net")
            val interfaces = netDir.listFiles()
            if (interfaces != null) {
                for (dir in interfaces) {
                    val name = dir.name.lowercase()
                    if (name.contains("p2p") || name.contains("wlan") || name.contains("ap")) {
                        val addrFile = File(dir, "address")
                        if (addrFile.exists()) {
                            val mac = addrFile.readText().trim().lowercase()
                            if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                                AppLog.i("WifiDirectManager: Last resort MAC found on ${dir.name}: $mac")
                                return mac
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return null
    }

    fun stop() {
        AppLog.i("WifiDirectManager: Stopping and cleaning up...")
        isGroupCreatingOrCreated = false
        handler.removeCallbacksAndMessages(null)
        isClientConnected = false
        nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
        native5GhzBandMismatchRetries = 0
        lastUnfriendlyChannelSsid = null
        lastCoexistenceSsid = null
        nativeRecreateCount = 0
        lastNativeGroupStatusMessage = null
        AapService.scanningState.value = false
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}

        if (isGroupOwner) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.d("WifiDirectManager: Final group removal success") }
                override fun onFailure(reason: Int) { AppLog.d("WifiDirectManager: Final group removal failed: $reason") }
            })
        }

        isGroupOwner = false
        isConnected = false
    }
}
