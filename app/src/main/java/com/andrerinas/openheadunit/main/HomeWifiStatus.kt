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
import android.provider.Settings as SystemSettings
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The WiFi facts behind the home's WiFi line, kept current while the home is on screen, and the
 * one-off scan the Scan WiFi button asks for. [start] in onStart, [stop] in onStop; [onChanged] runs on the
 * main thread.
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

    private fun changed() = handler.post(onChanged)

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
     * One scan, for the Scan WiFi button. Android picks and joins the network itself; this
     * only gets it the results sooner. From Android 9 a foreground app gets four scans in two
     * minutes, and the platform refuses the rest.
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

    /** Android's WiFi settings, for the long press: pick a network or type its password. */
    fun openWifiSettings() = openWifiScreen(panel = false)

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
