package com.andrerinas.openheadunit.connection.wifi.modes.native

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotConfigReader
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import com.andrerinas.openheadunit.utils.NetworkAddresses
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SoftApStateReader
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Supplies the Native AA handshake with the credentials of *this head unit's own access point*,
 * as an alternative to [com.andrerinas.openheadunit.connection.wifi.WifiDirectManager]'s P2P group.
 *
 * A teardown of the OEM ZLink app showed it doing wireless Android Auto over an ordinary WPA2 soft
 * AP — same Bluetooth handshake, same UUID, no WiFi Direct — so the phone accepts a plain access
 * point, and that route sidesteps the P2P failure modes behind most Native AA reports: group
 * churn, the self-wake loop, phones stuck on "Obtaining IP address".
 *
 * It does not copy ZLink's way of *starting* the AP; that needs TETHER_PRIVILEGED and root
 * daemons. Reading a hotspot the user configured is the load-bearing path, switching one on is
 * best effort. Same contract as [com.andrerinas.openheadunit.connection.wifi.WifiDirectManager] so a launcher can treat both transports alike.
 */
class SoftApCredentialsProvider(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings
) {

    companion object {
        /** How often to look for the access point while waiting for it to come up. */
        private const val POLL_INTERVAL_MS = 1_000L

        /** How long to keep looking before giving up and saying so. */
        private const val RESOLVE_BUDGET_MS = 30_000L

        /** How long to wait for a user-configured AP before trying to switch one on ourselves. */
        private const val AUTO_ENABLE_AFTER_MS = 5_000L

        /** Not in any public SDK constant: the soft AP state broadcast and its disabled state. */
        private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_DISABLED = 11
    }

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null
    private var onInvalidated: (() -> Unit)? = null

    private var resolveJob: Job? = null
    @Volatile private var isRunning = false
    @Volatile private var isReceiverRegistered = false
    /** Whether *we* turned the hotspot on, and so may turn it back on if it drops. */
    @Volatile private var autoEnabled = false

    /** So the once-per-second poll reports "nothing here" once, not thirty times. */
    @Volatile private var reportedNoInterface = false

    private val apStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1) != WIFI_AP_STATE_DISABLED) return
            AppLog.w("SoftApCredentials: The hotspot went down — the credentials the phone was given are no longer valid.")
            onInvalidated?.invoke()
            if (autoEnabled && isRunning) {
                AppLog.i("SoftApCredentials: Re-enabling the hotspot we started, once.")
                autoEnabled = false
                // Off the main thread: setHotspotEnabled waits for the access point to actually
                // come up, and onReceive() has an ANR budget measured in seconds.
                scope.launch(Dispatchers.IO + CoroutineName("SoftApCredentials-Reenable")) {
                    HotspotManager.setHotspotEnabled(context, true)
                    refresh()
                }
            }
        }
    }

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        this.onCredentialsReady = callback
    }

    fun setInvalidatedListener(callback: () -> Unit) {
        this.onInvalidated = callback
    }

    fun start() {
        if (isRunning) {
            refresh()
            return
        }
        isRunning = true
        if (!isReceiverRegistered) {
            try {
                // A system broadcast, so EXPORTED: NOT_EXPORTED silently never fires on API 34+.
                ContextCompat.registerReceiver(
                    context, apStateReceiver, IntentFilter(WIFI_AP_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )
                isReceiverRegistered = true
            } catch (e: Exception) {
                AppLog.w("SoftApCredentials: Could not watch the hotspot state: ${e.message}")
            }
        }
        beginResolve()
    }

    /** Look again, from the top. Called when the handshake has been waiting too long. */
    fun refresh() {
        if (!isRunning) return
        beginResolve()
    }

    fun stop() {
        isRunning = false
        resolveJob?.cancel()
        resolveJob = null
        autoEnabled = false
        if (isReceiverRegistered) {
            // Reset even if unregister throws: a flag set in only one direction is how a
            // long-lived manager ends up unable to re-arm.
            try { context.unregisterReceiver(apStateReceiver) } catch (e: Exception) {}
            isReceiverRegistered = false
        }
    }

    private fun beginResolve() {
        resolveJob?.cancel()
        reportedNoInterface = false
        resolveJob = scope.launch(Dispatchers.IO + CoroutineName("SoftApCredentials-Resolve")) {
            val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
            var triedAutoEnable = false
            val startedAt = System.currentTimeMillis()

            while (isActive && isRunning && System.currentTimeMillis() < deadline) {
                val chosen = pickApInterface()
                if (chosen != null && publish(chosen)) return@launch

                // Reaching here means nothing was published, whether because no interface looked
                // like an access point or because the one that did turned out not to be running.
                // Both are "there is no hotspot yet", which is what auto-enable is for.
                val waited = System.currentTimeMillis() - startedAt
                if (!triedAutoEnable && waited >= AUTO_ENABLE_AFTER_MS && settings.autoEnableHotspot) {
                    triedAutoEnable = true
                    AppLog.i("SoftApCredentials: No access point after ${waited / 1000}s — trying to switch this device's hotspot on.")
                    // Best effort; most unrooted units lack the permission. Keep polling anyway,
                    // since the user may switch it on by hand.
                    autoEnabled = HotspotManager.setHotspotEnabled(context, true)
                }
                delay(POLL_INTERVAL_MS)
            }

            if (isActive && isRunning) {
                AppLog.e(
                    "SoftApCredentials: No usable access point after ${RESOLVE_BUDGET_MS / 1000}s. " +
                        "Turn this device's hotspot on before connecting — 5 GHz is strongly " +
                        "recommended, Android Auto video is poor over 2.4 GHz — or switch the " +
                        "Android Auto network transport back to WiFi Direct."
                )
                onInvalidated?.invoke()
            }
        }
    }

    /** The interface we settled on, and whether the user named it rather than us guessing. */
    private data class ChosenInterface(val iface: ApInterfaceCandidate, val namedByUser: Boolean)

    /**
     * The interface our access point is running on, if it is up.
     *
     * Every reference implementation of this protocol — aa-proxy-rs, the Raspberry Pi dongles, the
     * OEM ZLink app — either creates the access point itself or reads the interface name from
     * configuration; none of them infer it. We are the only one reading an access point we did not
     * create, on hardware we do not control, which is why [Settings.hotspotInterface] comes first
     * and the heuristic is the fallback.
     */
    private fun pickApInterface(): ChosenInterface? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList().map { nif ->
                ApInterfaceCandidate(
                    name = nif.name,
                    isLoopback = try { nif.isLoopback } catch (e: Exception) { false },
                    isUp = try { nif.isUp } catch (e: Exception) { false },
                    siteLocalIpv4 = nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress }
                        ?.hostAddress
                )
            }
        } catch (e: Exception) {
            AppLog.w("SoftApCredentials: Could not enumerate network interfaces: ${e.message}")
            return null
        }

        val named = settings.hotspotInterface.trim()
        if (named.isNotEmpty()) {
            val match = candidates.firstOrNull { it.name.equals(named, ignoreCase = true) }
            when {
                match == null ->
                    AppLog.w("SoftApCredentials: No interface named '$named'. Present: ${candidates.joinToString { it.name }}. Falling back to automatic selection.")
                match.siteLocalIpv4 == null || !match.isUp ->
                    AppLog.w("SoftApCredentials: Interface '$named' is ${if (match.isUp) "up but has no address" else "down"}. Falling back to automatic selection.")
                else -> {
                    AppLog.i("SoftApCredentials: Using '${match.name}' (${match.siteLocalIpv4}) as named in settings.")
                    return ChosenInterface(match, namedByUser = true)
                }
            }
        }

        val stationIpv4 = NetworkAddresses.stationIpv4(context)
        val eligible = SoftApNetworkPolicy.eligible(candidates, stationIpv4)
        val picked = SoftApNetworkPolicy.pickApInterface(candidates, stationIpv4) ?: run {
            // Rejecting everything is the correct answer when no hotspot is up, but saying nothing
            // for the whole 30s budget reads as a hang. Name what was there so the reader can pick
            // one for the override if the access point is on an interface we did not recognise.
            if (!reportedNoInterface) {
                reportedNoInterface = true
                AppLog.i(
                    "SoftApCredentials: No interface looks like an access point; waiting for one. " +
                        "Present: " + candidates.filter { it.isUp && !it.isLoopback }
                        .joinToString { "${it.name} (${it.siteLocalIpv4 ?: "no private address"})" }
                )
            }
            return null
        }
        if (eligible.size > 1) {
            // More than one survivor means the name decided it, which is a guess. Nothing else
            // reports having guessed, and picking wrong hands the phone an unreachable address.
            AppLog.w(
                "SoftApCredentials: More than one interface could be the access point — " +
                    eligible.joinToString { "${it.name} (${it.siteLocalIpv4})" } +
                    " — choosing ${picked.name} by name. Set the hotspot interface by hand if that is wrong."
            )
        }
        return ChosenInterface(picked, namedByUser = false)
    }

    /** Resolves the rest of the credentials for [iface] and hands them over. True if it worked. */
    private fun publish(chosen: ChosenInterface): Boolean {
        val iface = chosen.iface
        val ip = iface.siteLocalIpv4 ?: return false

        // User's override first, then the system's own configuration: getSoftApConfiguration() is
        // reflection over a non-public API and can simply refuse on a locked-down device.
        val manualSsid = settings.hotspotSsid
        val systemConfig = if (manualSsid.isEmpty()) HotspotConfigReader.getSystemHotspotConfig(context) else null
        val ssid = manualSsid.ifEmpty { systemConfig?.first.orEmpty() }
        val psk = settings.hotspotPassword.ifEmpty { systemConfig?.second.orEmpty() }

        if (ssid.isEmpty()) {
            AppLog.w(
                "SoftApCredentials: Found an access point on ${iface.name} ($ip) but could not read " +
                    "its name. This device does not let apps read the hotspot configuration — set " +
                    "the name and password by hand in the Android Auto settings."
            )
            return false
        }
        if (psk.isEmpty()) {
            AppLog.w("SoftApCredentials: No passphrase for '$ssid'. An open network will be refused by the phone; set one by hand if this fails.")
        }

        val apState = SoftApStateReader.read(context)
        if (!NativeCredentialsPolicy.shouldPublishCredentials(apState, chosen.namedByUser)) {
            AppLog.w(
                "SoftApCredentials: the system reports no access point running, so ${iface.name} " +
                    "($ip) is some other network — a modem bridge or a wired link will look just " +
                    "like this. Not handing the phone a network that is not on air. Switch the " +
                    "hotspot on, or name the interface by hand if you know it is up."
            )
            return false
        }
        if (apState == SoftApState.UNKNOWN) {
            AppLog.i("SoftApCredentials: This device does not let apps read the hotspot state; proceeding without confirming the access point is up.")
        }

        val bssid = SoftApBssidPolicy.choose(
            staticOverride = settings.staticBSSID,
            shellMac = InterfaceMacReader.read(iface.name),
            hardwareAddress = hardwareAddressOf(iface.name)
        )
        if (bssid.isEmpty()) {
            // Not fatal on this route — see NativeCredentialsPolicy. The handshake decides.
            AppLog.w("SoftApCredentials: Could not resolve a real BSSID for ${iface.name}; the credentials will go out without one.")
        }

        AppLog.i("SoftApCredentials: SUCCESS - Providing credentials from ${iface.name}: SSID=$ssid, IP=$ip, BSSID=${bssid.ifEmpty { "<none>" }}")
        onCredentialsReady?.invoke(ssid, psk, ip, bssid)
        return true
    }

    private fun hardwareAddressOf(name: String): String? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
            ?.joinToString(":") { String.format("%02x", it) }
    } catch (e: Exception) {
        null
    }
}
