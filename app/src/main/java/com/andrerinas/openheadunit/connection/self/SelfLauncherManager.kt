package com.andrerinas.openheadunit.connection.self

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.DummyVpnPolicy
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherBTDiscovery
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherBroadcast
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherLegacy
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherV17_4
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.VpnControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SelfLauncherManager(
    private val service: AapService,
    private val wifiLauncherManager: WifiLauncherManager
) {

    var isActive: Boolean = false

    /**
     * True from the moment a launch is accepted until its launchers have finished running.
     *
     * Cleared by [clearLaunchInFlight] as well as by the launch itself, because this manager is a
     * long-lived singleton that gets stopped and re-armed within one process, and a flag set in one
     * direction only would strand Self Mode after any stop that landed mid-launch.
     */
    @Volatile
    private var launchInFlight: Boolean = false

    /**
     * Takes down a Self Mode VPN whose phone never arrived.
     *
     * [stopWirelessServer] used to do this by accident. Without it a user who starts Self Mode and
     * walks away leaves a tun that routes 0.0.0.0/0 into a descriptor nobody reads, and the unit
     * has no IPv4 until the service dies.
     */
    private var selfModeVpnWatchdog: Job? = null

    /**
     * "Self Mode" connects the device to itself over the loopback interface.
     *
     * Starts [com.andrerinas.openheadunit.connection.wifi.server.WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    private fun isAaVersion174OrHigher(): Boolean {
        return try {
            val pInfo = service.packageManager.getPackageInfo(AA_PACKAGE, 0)
            val vName = pInfo.versionName ?: ""
            val parts = vName.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            AppLog.i("SelfMode: Installed AA version: $vName (major=$major, minor=$minor)")
            major > 17 || (major == 17 && minor >= 4)
        } catch (e: Exception) {
            AppLog.w("SelfMode: Failed to query AA version: ${e.message}")
            false
        }
    }

    fun openAaSettings() {
        val intent = Intent().apply {
            setClassName(
                AA_PACKAGE,
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            service.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                    data = Uri.parse("package:com.google.android.projection.gearhead")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                AppLog.e("SelfMode: Failed to open AA settings: ${e2.message}")
            }
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun start() {
        val commManager = App.provide(service).commManager

        // auto-start-self-mode and an explicit ACTION_START_SELF_MODE both land here, and running
        // two launches at once costs a session rather than a retry. See SelfLaunchCoalescePolicy.
        if (!SelfLaunchCoalescePolicy.shouldStart(launchInFlight, commManager.isConnected)) {
            AppLog.i(
                "SelfMode: a launch is already " +
                    (if (commManager.isConnected) "connected" else "in flight") +
                    "; ignoring this request rather than starting a second one"
            )
            return
        }

        isActive = true
        launchInFlight = true
        adoptDummyVpn()

        service.serviceScope.launch(Dispatchers.Main) {
            // prepare launchers
            val services = SelfLauncherServices(service, wifiLauncherManager)
            val launchers: Array<SelfLauncher>

            val path: SelfLaunchPath

            if (isAaVersion174OrHigher()) {
                AppLog.i("SelfMode: AA 17.4+ detected. Connecting directly to Headunit Server on 127.0.0.1:5277...")
                path = SelfLaunchPath.HEADUNIT_SERVER
                launchers = arrayOf(
                    SelfLauncherV17_4(this@SelfLauncherManager, services)
                )

            } else {
                AppLog.i("SelfMode: AA < 17.4 detected. Starting WirelessServer on 5288 and running legacy triggers...")
                path = SelfLaunchPath.LEGACY
                launchers = arrayOf(
                    SelfLauncherLegacy(this@SelfLauncherManager, services),
                    SelfLauncherBroadcast(this@SelfLauncherManager, services), // fallback #1
                    SelfLauncherBTDiscovery(this@SelfLauncherManager, services), // fallback #2
                )
            }

            // run them
            var anySucceeded = false

            try {
                for (launcher in launchers) {
                    try {
                        if (!launcher.run())
                            AppLog.w("SelfMode: Launch of '${launcher.name}' failed")
                        else {
                            AppLog.w("SelfMode: Launch of '${launcher.name}' had no issues")
                            anySucceeded = true
                            break
                        }
                    } catch (e: Exception) {
                        AppLog.w("SelfMode: Launch of '${launcher.name}' had caused an error", e)
                    }
                }
            } finally {
                // The launchers have had their turn; what follows is waiting for the phone, which
                // another request is entitled to retry.
                launchInFlight = false
            }

            // all failed :(
            if (!anySucceeded) {
                AppLog.e("SelfMode: All launchers failed")
                if (SelfLaunchCoalescePolicy.mayReportAllLaunchersFailed(commManager.isConnected)) {
                    // reportError, not emitError: disconnect() defaults to isUserExit, so a launch
                    // that never had a session latched userExitedAA and suppressed the Native poke
                    // for the rest of the group. Reporting still hides the "connecting" overlay.
                    commManager.reportError("No launch method succeeded")
                    // The report is not a disconnect, so nothing else clears this - and the
                    // watchdog below is only armed once a launcher has succeeded.
                    isActive = false
                } else {
                    // emitError disconnects, and the session that is up is not this attempt's to
                    // end. Measured on the 17.4+ route, where a duplicate launch's failure killed
                    // the socket the other one had just connected.
                    AppLog.w("SelfMode: launchers failed but a session is connected; leaving it alone")
                }
                return@launch
            }

            // Report a launch that has not connected yet, without taking anything down: the
            // wireless server and the dummy VPN are what the phone still has to arrive on. See
            // SelfLaunchTimeoutPolicy.
            val deadlineMs = SelfLaunchTimeoutPolicy.deadlineMs(path)
            service.serviceScope.launch {
                delay(deadlineMs)

                if (!commManager.isConnected && isActive) {
                    AppLog.e("SelfMode: nothing connected within ${deadlineMs}ms of the launch")
                    if (SelfLaunchTimeoutPolicy.mayDisconnect(path)) {
                        commManager.emitError("No launch method succeeded (timeout)")
                    } else {
                        commManager.reportError("No launch method succeeded (timeout)")
                    }

                    // The report is deliberately not a disconnect, so no Disconnected transition
                    // arrives to clear this. Left true, it poisons the next session in this
                    // process: ServiceDiscoveryResponse drops the media and speech audio sinks.
                    isActive = false
                    handleNeverConnect()
                }
            }
        }
    }

    /**
     * Records that a Self Mode VPN - started by `HomeFragment`, which owns the consent dialog - is
     * ours to clean up, and arms the watchdog that does it if no phone ever arrives.
     */
    private fun adoptDummyVpn() {
        val commManager = App.provide(service).commManager

        // Nothing to adopt where the flavor has no VPN - see VpnControl.
        if (!VpnControl.isVpnAvailable()) return
        if (service.dummyVpnOwner == null) service.dummyVpnOwner = DummyVpnPolicy.Owner.SELF_MODE
        selfModeVpnWatchdog?.cancel()
        selfModeVpnWatchdog = service.serviceScope.launch {
            delay(VPN_TIMEOUT_MS)
            if (!commManager.isConnected) {
                AppLog.w(
                    "SelfMode",
                    "AapService: Self Mode brought the dummy VPN up ${VPN_TIMEOUT_MS}ms " +
                        "ago and no phone arrived. Taking it down so this unit gets its network back."
                )
                service.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_NEVER_CONNECTED)
            }
        }
    }

    /**
     * Lets go of a launch this manager will never finish, so a later request is not refused by a
     * flag left set by a stop that landed mid-launch.
     */
    fun clearLaunchInFlight() {
        launchInFlight = false
    }

    /** Whether the launchers are still running, for a disconnect deciding what it is looking at. */
    fun isLaunchInFlight(): Boolean = launchInFlight

    fun stopDummyVpnWatchdog() {
        selfModeVpnWatchdog?.cancel()
        selfModeVpnWatchdog = null
    }

    fun handleNeverConnect() {
        AppLog.w("SelfMode: Failed, timed out!")

        SelfLaunchResolveHelper(service).run()
    }

    /**
     * Tears down Self Mode flags and cleans up any leftover resources.
     *
     * Call this when the user cancels Self Mode explicitly, or when the service is destroyed
     * while Self Mode is still marked active, so stale `isActive` and watchdog state cannot
     * block a later restart.
     *
     * @param wasConnected when `true` a projection session did reach the handshake, so the
     *        dummy VPN is treated as an ordinary session teardown; when `false` no phone ever
     *        arrived and the VPN is taken down via the Self-Mode-never-connected path.
     */
    fun stop(wasConnected: Boolean = false) {
        if (!isActive && !launchInFlight && selfModeVpnWatchdog == null) return

        AppLog.i("SelfMode: stopping Self Mode (wasConnected=$wasConnected, wasActive=$isActive, launchInFlight=$launchInFlight)")
        isActive = false
        launchInFlight = false
        stopDummyVpnWatchdog()

        // Stop the VPN only when we own it and the session did not already release it.
        // SESSION_ENDED always releases anything we own, so it is the safe choice when a
        // connection already happened; SELF_MODE_NEVER_CONNECTED is the precise reason when
        // we are tearing down a Self Mode that never reached the handshake.
        if (!wasConnected) {
            service.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_NEVER_CONNECTED)
        }
    }


    companion object {
        /**
         * How long a Self Mode dummy VPN may stay up with no phone before it is taken down.
         *
         * stopWirelessServer() used to do this cleanup by accident, on the next mode change.
         */
        private const val VPN_TIMEOUT_MS = 120_000L

        const val AA_PACKAGE = "com.google.android.projection.gearhead"
    }
}
