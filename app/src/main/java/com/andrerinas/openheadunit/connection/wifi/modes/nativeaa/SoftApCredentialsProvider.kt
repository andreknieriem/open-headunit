package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.app.AccPowerState
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import com.andrerinas.openheadunit.utils.HotspotConfigReader
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import com.andrerinas.openheadunit.utils.NetworkAddresses
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SoftApStateReader
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.connection.wifi.direct.WifiDirectManager
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
 * as an alternative to [WifiDirectManager]'s P2P group.
 *
 * A teardown of the OEM ZLink app showed it doing wireless Android Auto over an ordinary WPA2 soft
 * AP — same Bluetooth handshake, same UUID, no WiFi Direct — so the phone accepts a plain access
 * point, and that route sidesteps the P2P failure modes behind most Native AA reports: group
 * churn, the self-wake loop, phones stuck on "Obtaining IP address".
 *
 * It does not copy ZLink's way of *starting* the AP; that needs TETHER_PRIVILEGED and root
 * daemons. Reading a hotspot the user configured is the load-bearing path, switching one on is
 * best effort. Same contract as [WifiDirectManager] so a launcher can treat both transports alike.
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

        /**
         * How often to repeat that no access point could be found.
         *
         * Said once per run it was reliably lost: a unit logging hard enough drops old lines, and
         * the one capture that needed this had every trace of it evicted before the user exported
         * the log. Repeating puts it inside any window long enough to be worth reading.
         */
        private const val NO_AP_REPORT_INTERVAL_MS = 60_000L

        /** Not in any public SDK constant: the soft AP state broadcast and its disabled state. */
        private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_DISABLED = 11
    }

    /**
     * The handover itself, rather than a bare callback field.
     *
     * [beginResolve] runs on IO and can reach [publish] before the service that owns the listener has
     * finished starting, and a set of credentials dropped there is never resolved again. See
     * [CredentialsHandoff].
     */
    private val credentialsHandoff = CredentialsHandoff()
    private var onInvalidated: (() -> Unit)? = null

    private var resolveJob: Job? = null
    @Volatile private var isRunning = false
    @Volatile private var isReceiverRegistered = false
    /** Whether *we* turned the hotspot on, and so may turn it back on if it drops. */
    @Volatile private var autoEnabled = false

    /**
     * Auto-enable attempts since [start], kept off [beginResolve] because [refresh] restarts that
     * loop every ~10 s and used to re-arm a start on top of one still running. SoftApAutoEnablePolicy
     * spaces the second one out, and only after the first failed.
     */
    @Volatile private var autoEnableAttempts = 0
    /** When the last start returned: the retry is spaced from a failure, not from the ask. */
    @Volatile private var lastAutoEnableAtMs = 0L
    /** A start blocks for up to ~20 s and survives [refresh] cancelling its loop; never ask over it. */
    @Volatile private var autoEnableInFlight = false
    /** The ACC wake already counted, so each time the car comes back gets its own attempts. */
    @Volatile private var seenWokeAtMs = 0L
    /** When the access point last went down, so no enable is asked for while the radio settles. */
    @Volatile private var apDownAtMs = 0L
    /** The ACC-off action already reported, so a car left off says so once. */
    @Volatile private var reportedAccOff: String? = null

    /** So the once-per-second poll reports "nothing here" once, not thirty times. */
    @Volatile private var reportedNoInterface = false

    /**
     * When [start] was called, so the budget below measures the run rather than one look.
     *
     * [beginResolve] used to stamp its own deadline, and [refresh] restarts it — which the handshake
     * calls every ~10 s while it waits. A budget of 30 s restarted every 10 s never expires, so the
     * line that tells the user no access point could be found was unreachable in exactly the case it
     * was written for. Measured: an access point down for 3.5 minutes, the resolve polling the whole
     * time, and nothing said.
     */
    @Volatile private var runStartedAt = 0L

    /** When the budget was last reported, so [refresh] cannot restart the count. */
    @Volatile private var lastNoApReportAtMs = 0L

    /**
     * Same idea for the unreadable-configuration dead end, but latched for the whole run rather
     * than per resolve: the handshake calls [refresh] every time it has been waiting too long, and
     * the phone redials every few seconds, so this would otherwise be said once per attempt for as
     * long as the user keeps trying. It is one instruction and it does not change.
     */
    @Volatile private var reportedConfigUnreadable = false

    private val apStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1) != WIFI_AP_STATE_DISABLED) return
            AppLog.w("SoftApCredentials: The hotspot went down — the credentials the phone was given are no longer valid.")
            onInvalidated?.invoke()
            apDownAtMs = System.currentTimeMillis()
            if (autoEnabled && isRunning && !AccPowerState.isOff) {
                // An enable at the moment of the drop fails and used to spend the only one; ours gets
                // a fresh budget, which the resolve loop spaces from the drop.
                AppLog.i("SoftApCredentials: the hotspot we started went down; asking for it again in ${SoftApAutoEnablePolicy.RETRY_AFTER_MS / 1000}s at the earliest.")
                autoEnabled = false
                autoEnableAttempts = 0
            }
            refresh()
        }
    }

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        credentialsHandoff.setListener { callback(it.ssid, it.psk, it.ip, it.bssid) }
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
        runStartedAt = System.currentTimeMillis()
        autoEnableAttempts = 0
        lastAutoEnableAtMs = 0L
        apDownAtMs = 0L
        seenWokeAtMs = AccPowerState.wokeAtMs
        reportedConfigUnreadable = false
        lastNoApReportAtMs = 0L
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
        // The network these describe is going away with this run; the next one resolves its own.
        credentialsHandoff.clear()
        autoEnabled = false
        autoEnableAttempts = 0
        lastAutoEnableAtMs = 0L
        reportedConfigUnreadable = false
        lastNoApReportAtMs = 0L
        if (isReceiverRegistered) {
            // Reset even if unregister throws: a flag set in only one direction is how a
            // long-lived manager ends up unable to re-arm.
            try { context.unregisterReceiver(apStateReceiver) } catch (e: Exception) {}
            isReceiverRegistered = false
        }
    }

    /** A dark screen on a head unit is a unit asleep or going to sleep, and an enable fights that. */
    private fun screenIsOff(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        !(if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn)
    } catch (e: Exception) {
        false
    }

    /** Callers set [autoEnableInFlight] first, so a loop restarted meanwhile sees it. */
    private fun enableHotspot(): Boolean = try {
        HotspotManager.setHotspotEnabled(context, true)
    } finally {
        lastAutoEnableAtMs = System.currentTimeMillis()
        autoEnableInFlight = false
    }

    private fun beginResolve() {
        resolveJob?.cancel()
        reportedNoInterface = false
        resolveJob = scope.launch(Dispatchers.IO + CoroutineName("SoftApCredentials-Resolve")) {
            val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
            var budgetReported = false

            while (isActive && isRunning) {
                // Past the budget, say so once, but keep looking while an enable is still owed:
                // a loop that ended here left the retry to a refresh that might never come.
                if (System.currentTimeMillis() >= deadline) {
                    if (!budgetReported) {
                        budgetReported = true
                        reportNoAccessPoint(System.currentTimeMillis() - runStartedAt, force = true)
                        onInvalidated?.invoke()
                    }
                    if (!SoftApAutoEnablePolicy.attemptOwed(
                            settings.autoEnableHotspot, autoEnableAttempts, autoEnabled, autoEnableInFlight)) break
                }
                val chosen = pickApInterface()
                val apName = chosen?.iface?.name ?: "the access point"
                when (if (chosen == null) SoftApCredentialsAttempt.NO_AP_YET else publish(chosen)) {
                    SoftApCredentialsAttempt.PUBLISHED -> return@launch

                    SoftApCredentialsAttempt.CONFIG_UNREADABLE -> {
                        // Waiting cannot help and the budget would be spent in silence, so stop and
                        // say the one thing that does. Observed on a unit whose hotspot was up and
                        // found the whole time: the resolve loop read "could not read its name" as
                        // "there is no hotspot", switched on an access point that was already on,
                        // and repeated the same line once a second until it gave up.
                        if (!reportedConfigUnreadable) {
                            reportedConfigUnreadable = true
                            AppLog.e(
                                "SoftApCredentials: The access point on $apName is up, but this " +
                                    "device will not let apps read its name, so there is nothing to hand the " +
                                    "phone. Waiting will not change that. Set 'Hotspot name (manual)' and " +
                                    "'Hotspot password (manual)' in Settings to this device's own hotspot name " +
                                    "and password, then connect again."
                            )
                            ConnectionIssues.raise(context, ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
                        }
                        onInvalidated?.invoke()
                        return@launch
                    }

                    SoftApCredentialsAttempt.NO_AP_YET -> {
                        // Nothing on air yet, which is exactly what auto-enable is for. Reached only
                        // here, so an access point that is up but unreadable never triggers it.
                        val waited = System.currentTimeMillis() - runStartedAt
                        val accOff = AccPowerState.offAction
                        if (accOff != null) {
                            if (reportedAccOff != accOff) {
                                reportedAccOff = accOff
                                AppLog.i("SoftApCredentials: the car is off ($accOff), not switching the hotspot on.")
                            }
                        } else {
                            reportedAccOff = null
                            reportNoAccessPoint(waited)
                        }
                        val now = System.currentTimeMillis()
                        val wokeAt = AccPowerState.wokeAtMs
                        if (wokeAt != seenWokeAtMs) {
                            seenWokeAtMs = wokeAt
                            autoEnableAttempts = 0
                        }
                        if (SoftApAutoEnablePolicy.shouldAttempt(
                                enabledInSettings = settings.autoEnableHotspot,
                                attempts = autoEnableAttempts,
                                waitedMs = waited,
                                lastAttemptEndedAtMs = lastAutoEnableAtMs,
                                lastAttemptSucceeded = autoEnabled,
                                nowMs = now,
                                accOff = accOff != null,
                                attemptInFlight = autoEnableInFlight,
                                sinceWakeMs = if (wokeAt > runStartedAt) now - wokeAt else null,
                                sinceApDownMs = apDownAtMs.takeIf { it > 0L }?.let { now - it },
                                screenOff = screenIsOff())) {
                            val attempt = ++autoEnableAttempts
                            autoEnableInFlight = true
                            AppLog.i("SoftApCredentials: No access point after ${waited / 1000}s — trying to switch this device's hotspot on (attempt $attempt of ${SoftApAutoEnablePolicy.MAX_ATTEMPTS}).")
                            // Best effort; most unrooted units lack the permission. Keep polling
                            // anyway, since the user may switch it on by hand.
                            autoEnabled = enableHotspot()
                            if (!autoEnabled) AppLog.w("SoftApCredentials: the hotspot did not come up on attempt $attempt.")
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Says that this has been going on too long to be a hotspot still coming up.
     *
     * Reported rather than acted on: the polling continues, because the user switching the hotspot
     * on by hand is a real recovery and the only one left on a device where auto-enable cannot.
     * Repeated on a cooldown rather than said once, so a capture taken minutes into the attempt
     * still carries it, and recorded as well as logged, so something says it after the log rolls.
     */
    private fun reportNoAccessPoint(waited: Long, force: Boolean = false) {
        if (!force && waited < RESOLVE_BUDGET_MS) return
        val now = System.currentTimeMillis()
        if (lastNoApReportAtMs != 0L && now - lastNoApReportAtMs < NO_AP_REPORT_INTERVAL_MS) return
        lastNoApReportAtMs = now
        AppLog.e(
            "SoftApCredentials: No usable access point after ${waited / 1000}s. " +
                "Turn this device's hotspot on before connecting — 5 GHz is strongly " +
                "recommended, Android Auto video is poor over 2.4 GHz — or switch the " +
                "Android Auto network transport back to WiFi Direct." +
                (if (settings.autoEnableHotspot) "" else " 'Auto-Enable Hotspot' is off, so the app did not try to switch it on.")
        )
        // The durable half. The line above is the instruction; this is what still says it after the
        // log has rolled and the user is back in front of the app.
        ConnectionIssues.raise(context, ConnectionIssue.HOTSPOT_NOT_RUNNING)
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

    /** Resolves the rest of the credentials for [iface] and hands them over. */
    private fun publish(chosen: ChosenInterface): SoftApCredentialsAttempt {
        val iface = chosen.iface
        val ip = iface.siteLocalIpv4 ?: return SoftApCredentialsAttempt.NO_AP_YET

        // User's override first, then the system's own configuration: getSoftApConfiguration() is
        // reflection over a non-public API and can simply refuse on a locked-down device. What the
        // two of them add up to is SoftApCredentialsPolicy's question, not this method's — the
        // device it matters on is not one we can test against, so the rule lives where a test can
        // reach it.
        val manualSsid = settings.hotspotSsid
        // Read once. decide(), resolve() and the record rule below have to judge the same pair, and
        // every one of these properties is a fresh SharedPreferences read that the settings screen
        // can change underneath us between calls.
        val manualPassphrase = settings.hotspotPassword
        val systemConfig = if (manualSsid.isEmpty()) {
            HotspotConfigReader.getSystemHotspotConfig(context)?.let { SoftApCredentials(it.first, it.second) }
        } else null

        val attempt = SoftApCredentialsPolicy.decide(manualSsid, manualPassphrase, systemConfig, ip)
        if (attempt != SoftApCredentialsAttempt.PUBLISHED) return attempt
        val (ssid, psk) = SoftApCredentialsPolicy.resolve(manualSsid, manualPassphrase, systemConfig)

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
            return SoftApCredentialsAttempt.NO_AP_YET
        }
        if (apState == SoftApState.UNKNOWN) {
            AppLog.i("SoftApCredentials: This device does not let apps read the hotspot state; proceeding without confirming the access point is up.")
        } else if (apState == SoftApState.NOT_ENABLED) {
            // Reachable only because naming the interface by hand is the escape hatch for a vendor
            // that starts hostapd outside the framework. Said out loud, because otherwise a
            // hotspot that is simply off looks identical to one the framework cannot see.
            AppLog.w(
                "SoftApCredentials: the system reports no access point running, but ${iface.name} " +
                    "was named by hand, so its credentials are being handed over on that claim. " +
                    "If the phone never joins, check the hotspot is actually on."
            )
        }

        // The IPv6 rung sits last: on a unit that runs a real access point sysfs answers, and the
        // derived address is the same interface's, so it is worth no more than the direct read.
        val detected = listOf(
            InterfaceMacReader.read(iface.name),
            hardwareAddressOf(iface.name),
            InterfaceMacReader.fromIpv6LinkLocal(iface.name)
        )
        // The hand-typed address answers only where none of those did: one that disagrees with the
        // interface can only be wrong, and the phone joins on the address as well as the name.
        val bssid = SoftApBssidPolicy.chooseDetectedFirst(detected, settings.staticBSSID)
        if (SoftApBssidPolicy.overrideAnswered(detected, settings.staticBSSID)) {
            AppLog.i("SoftApCredentials: nothing on ${iface.name} reported an address, so the static BSSID setting ($bssid) is being used.")
        }
        if (bssid.isEmpty()) {
            // Not fatal on this route — see NativeCredentialsPolicy. The handshake decides.
            AppLog.w("SoftApCredentials: no source named an address for ${iface.name} - not sysfs, not the interface itself, and not its IPv6 link-local; the credentials will go out without one.")
        }

        AppLog.i("SoftApCredentials: SUCCESS - Providing credentials from ${iface.name}: SSID=$ssid, IP=$ip, BSSID=${bssid.ifEmpty { "<none>" }}")
        // Our own listener, not the phone — same reasoning as WifiDirectManager's delivery.
        ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
        // The record is what this hardware did, and only the device naming its own access point
        // disproves it. Retiring it on a manual override wiped the one durable instruction left to
        // the user who had typed the name and not the password - and decide() can never raise it
        // again once a name is set, so it was gone for good. Measured with 'hotspot-ssid' set and
        // 'hotspot-password' blank: the run that showed the banner also deleted it.
        if (SoftApCredentialsPolicy.disprovesConfigUnreadable(manualSsid, manualPassphrase, systemConfig)) {
            AppLog.i(
                "SoftApCredentials: the access point was named by this device rather than by the " +
                    "manual override, so the hotspot-configuration record is retired."
            )
            ConnectionIssues.clear(context, ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
        } else if (!SoftApCredentialsPolicy.isJoinable(ssid, psk)) {
            // Raised, not merely kept. Dismissal is per occurrence, so a record whose stamp never
            // moves is hidden for good after one dismissal - and on this branch the raise site in
            // beginResolve() is unreachable, because a name that is set is a name that resolves.
            // This is the only place that can say the credentials just sent were not joinable, and
            // it also covers a device that names an access point with no passphrase at all, which
            // had no signal of any kind before.
            AppLog.w(
                "SoftApCredentials: these credentials carry no passphrase, so the phone will refuse " +
                    "them and the hotspot-configuration record stays up. Set 'Hotspot password " +
                    "(manual)' as well as the name."
            )
            ConnectionIssues.raise(context, ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
        } else {
            AppLog.i(
                "SoftApCredentials: these credentials come from the manual override, which is a way " +
                    "round this device not naming its own access point rather than proof that it " +
                    "can, so the hotspot-configuration record stays as it is."
            )
        }
        // An access point we could name and hand over is proof there is one, whatever the last run
        // concluded. Retired here rather than on the interface being found, so a device that shows
        // an interface but never yields joinable credentials keeps the record it earned.
        ConnectionIssues.clear(context, ConnectionIssue.HOTSPOT_NOT_RUNNING)
        if (!credentialsHandoff.publish(NativeNetworkCredentials(ssid, psk, ip, bssid))) {
            // Held rather than lost, so the connection still happens, but say so, because until
            // this line existed the log of a unit that never woke its phone was identical to the
            // log of one idling with everything healthy, and there is nothing further to grep for.
            AppLog.w(
                "SoftApCredentials: the access point resolved before anything was listening for it; " +
                    "holding the credentials until it is."
            )
        }
        return SoftApCredentialsAttempt.PUBLISHED
    }

    private fun hardwareAddressOf(name: String): String? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
            ?.joinToString(":") { String.format("%02x", it) }
    } catch (e: Exception) {
        null
    }
}
