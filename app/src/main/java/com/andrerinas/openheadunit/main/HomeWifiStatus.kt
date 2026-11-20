package com.andrerinas.openheadunit.main

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotManager

/**
 * The WiFi facts behind the home's WiFi line, kept current while the home is on screen; the main
 * button's wait for the phone's hotspot; and the scans the home makes by itself when the user
 * turned them on. [start] in onStart, [stop] in onStop, which ends the wait and the automatic
 * scans too; [onChanged] and every callback run on the main thread. Rules: [HomeWifiStatusPolicy].
 */
class HomeWifiStatus(context: Context, private val onChanged: () -> Unit) {

    enum class ScanRequest { STARTED, REFUSED, WIFI_OFF }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = this.context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val cm = this.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var scanning = false

    /** A scan from the button is out and its results have not come back (at most 10 s). */
    val isScanning: Boolean get() = scanning

    /** A scan whose results never arrive must not leave "Scanning…" on the card. */
    private val scanTimeout = Runnable {
        if (scanning) {
            scanning = false
            onChanged()
        }
    }

    private fun changed() = handler.post {
        // The wait ends here rather than in the fragment, so a join heard while nothing redraws
        // still ends it.
        if (waitJoined != null && HomeWifiStatusPolicy.waitJoined(facts())) {
            val joined = waitJoined
            clearWait()
            joined?.invoke()
        }
        onChanged()
    }

    private var waitJoined: (() -> Unit)? = null
    private var waitTimedOut: (() -> Unit)? = null

    /** The main button is waiting for this unit to join the phone's hotspot. */
    val isWaitingForHotspot: Boolean get() = waitJoined != null

    private val waitRescan = object : Runnable {
        override fun run() {
            if (waitJoined == null) return
            requestScan()
            handler.postDelayed(this, HomeWifiStatusPolicy.RESCAN_MS)
        }
    }

    private val waitTimeout = Runnable {
        val timedOut = waitTimedOut
        clearWait()
        timedOut?.invoke()
        onChanged()
    }

    /**
     * Waits up to [HomeWifiStatusPolicy.JOIN_WAIT_MS] for this unit to join a WiFi network, scanning
     * now when [scanNow] and again every [HomeWifiStatusPolicy.RESCAN_MS]. Exactly one of the two
     * callbacks runs, unless [cancelWait] or [stop] comes first.
     */
    fun waitForHotspot(scanNow: Boolean, onJoined: () -> Unit, onTimedOut: () -> Unit) {
        clearWait()
        waitJoined = onJoined
        waitTimedOut = onTimedOut
        if (scanNow) requestScan()
        handler.postDelayed(waitRescan, HomeWifiStatusPolicy.RESCAN_MS)
        handler.postDelayed(waitTimeout, HomeWifiStatusPolicy.JOIN_WAIT_MS)
        // WiFi may have come up between the tap and here.
        changed()
    }

    fun cancelWait() {
        if (waitJoined == null) return
        clearWait()
        onChanged()
    }

    private fun clearWait() {
        waitJoined = null
        waitTimedOut = null
        handler.removeCallbacks(waitRescan)
        handler.removeCallbacks(waitTimeout)
    }

    private var autoScanAllowed: (() -> Boolean)? = null
    private var autoScanSince = 0L

    private val autoScanTick = object : Runnable {
        override fun run() {
            val allowed = autoScanAllowed ?: return
            val now = SystemClock.elapsedRealtime()
            // A join ends one stretch without WiFi; the next one starts brisk again.
            if (facts().connected) autoScanSince = now
            // The wait scans on its own pace; two paces would spend Android's allowance twice.
            if (waitJoined == null && allowed()) requestScan()
            handler.postDelayed(this, HomeWifiStatusPolicy.autoScanDelayMs(now - autoScanSince))
        }
    }

    /**
     * Scans by itself until [stop]: at once, then at [HomeWifiStatusPolicy.autoScanDelayMs]'s pace,
     * each time only if [allowed] says so.
     */
    fun startAutoScan(allowed: () -> Boolean) {
        stopAutoScan()
        autoScanAllowed = allowed
        autoScanSince = SystemClock.elapsedRealtime()
        handler.post(autoScanTick)
    }

    private fun stopAutoScan() {
        autoScanAllowed = null
        handler.removeCallbacks(autoScanTick)
    }

    /** Whether this unit hosts an access point of its own right now; see [HotspotManager.isApUp]. */
    fun unitHotspotUp(): Boolean = try {
        HotspotManager.isApUp(context)
    } catch (e: Exception) {
        false
    }

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && scanning) {
                    scanning = false
                    handler.removeCallbacks(scanTimeout)
                }
                changed()
            }
        }
        // EXPORTED, as for the service's system WiFi broadcasts: NOT_EXPORTED would not hear them.
        ContextCompat.registerReceiver(
            context, r,
            IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                @Suppress("DEPRECATION")
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        receiver = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && cm != null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changed() }
                override fun onLost(network: Network) { changed() }
                // Signal strength changes arrive here too.
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { changed() }
            }
            try {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
                )
                callback = cb
            } catch (e: Exception) {
                AppLog.w("HomeWifiStatus: no network callback, broadcasts only: ${e.message}")
            }
        }
    }

    fun stop() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        handler.removeCallbacks(scanTimeout)
        scanning = false
        clearWait()
        stopAutoScan()
    }

    @SuppressLint("MissingPermission")
    fun facts(): HomeWifiStatusPolicy.Facts {
        val enabled = try { wifi?.isWifiEnabled == true } catch (_: Exception) { false }
        val connected = isWifiConnected()
        @Suppress("DEPRECATION")
        val info = if (connected) try { wifi?.connectionInfo } catch (_: Exception) { null } else null
        return HomeWifiStatusPolicy.Facts(
            wifiEnabled = enabled,
            connected = connected,
            rawSsid = info?.ssid,
            frequencyMhz = info?.frequency?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP },
            rssiDbm = info?.rssi,
        )
    }

    @SuppressLint("MissingPermission")
    private fun isWifiConnected(): Boolean {
        val cm = cm ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            } else {
                @Suppress("DEPRECATION")
                cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * One scan. Android picks and joins the network itself; this only gets it the results sooner.
     * From Android 9 a foreground app gets four scans in two minutes, and the platform refuses the
     * rest; a refusal changes nothing, since Android's own scans carry on.
     */
    @SuppressLint("MissingPermission")
    fun requestScan(): ScanRequest {
        val w = wifi ?: return ScanRequest.REFUSED
        if (!w.isWifiEnabled) return ScanRequest.WIFI_OFF
        val accepted = try {
            @Suppress("DEPRECATION")
            w.startScan()
        } catch (e: Exception) {
            AppLog.w("HomeWifiStatus: scan refused: ${e.message}")
            false
        }
        AppLog.i("HomeWifiStatus: WiFi scan ${if (accepted) "requested" else "refused (throttled)"} from the home")
        if (accepted) {
            scanning = true
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            changed()
        }
        return if (accepted) ScanRequest.STARTED else ScanRequest.REFUSED
    }

    /** WiFi off: below Android 10 the app may switch it on; above it only the WiFi panel can ask. */
    @SuppressLint("MissingPermission")
    fun askToTurnWifiOn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val on = try { wifi?.setWifiEnabled(true) == true } catch (_: Exception) { false }
            if (on) return
        }
        openWifiScreen(panel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    private fun openWifiScreen(panel: Boolean) {
        val action = if (panel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SystemSettings.Panel.ACTION_WIFI
            else SystemSettings.ACTION_WIFI_SETTINGS
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("HomeWifiStatus: cannot open the WiFi screen ($action): ${e.message}")
            if (panel) openWifiScreen(panel = false)
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}
