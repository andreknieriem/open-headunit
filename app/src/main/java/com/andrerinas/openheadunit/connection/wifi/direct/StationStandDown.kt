package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues

/**
 * Drops and restores this unit's own WiFi association around a Native AA WiFi Direct bring-up.
 *
 * The rules are [StationStandDownPolicy]'s; this only does the I/O and reports what the platform
 * actually did. Nothing here trusts a return value: `disconnect()` sits behind a void AIDL on API 27
 * and answers true whatever happened, so the supplicant state is read back instead.
 */
object StationStandDown {

    /**
     * How long to give the supplicant before reading back whether it left.
     *
     * This runs on the service's main thread on the way to creating the group, so it cannot sleep.
     * The caller delays the group by this much instead when [standDown] says it acted: a group
     * asked for while the station is still tearing down forms on the channel the stand-down was
     * meant to free, and a credential refresh reads a group again rather than remaking it, so
     * nothing later would move it.
     */
    const val VERIFY_DELAY_MS = 1_500L

    /**
     * What the last bring-up did to the station, for readers that run after the deciding lines have
     * rotated out of the buffer. Process-lifetime, so a fresh process reads [StationStandDownOutcome.UNKNOWN] rather than
     * guessing.
     */
    @Volatile
    var lastOutcome: StationStandDownOutcome = StationStandDownOutcome.UNKNOWN
        private set

    private fun record(outcome: StationStandDownOutcome) {
        lastOutcome = outcome
    }

    // Per stand-down: reset in standDown, cleared in restore.
    private var reassertCount = 0
    private var lastReassertAtMs = 0L
    private var windowStartMs = 0L
    private var contestedLogged = false
    private var standDownAtMs = 0L
    private var leftSeen = false
    private var sessionLiveSeen = false
    private var platformWon = false
    private var deferredCheck: Runnable? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun clearReassertState() {
        deferredCheck?.let { mainHandler.removeCallbacks(it) }
        deferredCheck = null
        reassertCount = 0
        lastReassertAtMs = 0L
        windowStartMs = 0L
        contestedLogged = false
        standDownAtMs = 0L
        leftSeen = false
        sessionLiveSeen = false
        platformWon = false
    }

    // The station is left joined beside the group, so the banner tells the user how to stop it.
    private fun notePlatformWon(context: Context) {
        platformWon = true
        ConnectionIssues.raiseOnce(context, ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP)
    }

    /** The network's WifiConfiguration.status, or null when the platform will not say. */
    private fun readConfigStatus(wm: WifiManager, networkId: Int): Int? = try {
        @Suppress("DEPRECATION")
        wm.configuredNetworks?.firstOrNull { it.networkId == networkId }?.status
    } catch (e: Exception) {
        null
    }

    private fun verifyLeft(context: Context, wm: WifiManager, networkId: Int, again: Boolean) {
        mainHandler.postDelayed({
            val config = StationStandDownReassertPolicy.describeConfigStatus(readConfigStatus(wm, networkId))
            val associated = isStillAssociated(context)
            if (associated == true) {
                record(StationStandDownOutcome.STILL_JOINED)
                if (again) synchronized(this) { notePlatformWon(context) }
                AppLog.w(
                    "StationStandDown: this unit is still joined to its WiFi network " +
                        "${VERIFY_DELAY_MS}ms later" + (if (again) " after a re-assertion" else "") +
                        " (config=$config)" + (if (again) "." else ", so the group will have to share that network's channel.")
                )
            } else {
                AppLog.i(
                    "StationStandDown: this unit has left its WiFi network" +
                        (if (again) " again" else "") + " (config=$config)."
                )
            }
        }, VERIFY_DELAY_MS)
    }

    /**
     * A WiFi join reached the service: if a stand-down is in force and the platform undid it,
     * disconnect again, within [StationStandDownReassertPolicy]'s budget.
     */
    fun onStationJoined(context: Context, wifiLockHeldForMs: Long?, isRecheck: Boolean = false) {
        synchronized(this) {
            try {
                val settings = App.provide(context).settings
                val networkId = settings.stationStandDownNetworkId
                val mode = StationStandDownMode.fromSetting(settings.stationStandDownMode)
                val now = SystemClock.elapsedRealtime()
                val associated = isStillAssociated(context)
                val decision = StationStandDownReassertPolicy.decide(
                    mode, networkId, associated, leftSeen, reassertCount, windowStartMs, now,
                    lastReassertAtMs,
                    StationStandDownReassertPolicy.isContested(
                        settings.stationStandDownContestedFingerprint, Build.FINGERPRINT
                    ),
                    isRecheck
                )
                if (decision == StationStandDownReassertPolicy.Decision.Ignore) return
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val config = StationStandDownReassertPolicy.describeConfigStatus(readConfigStatus(wm, networkId))
                when (decision) {
                    StationStandDownReassertPolicy.Decision.Reassert -> {
                        if (StationStandDownReassertPolicy.opensWindow(windowStartMs, now)) {
                            windowStartMs = now
                            reassertCount = 0
                        }
                        reassertCount++
                        lastReassertAtMs = now
                        @Suppress("DEPRECATION")
                        val disabled = wm.disableNetwork(networkId)
                        @Suppress("DEPRECATION")
                        wm.disconnect()
                        AppLog.i(
                            "StationStandDown: the platform rejoined this unit's WiFi network " +
                                "${(now - standDownAtMs) / 1000}s into the stand-down (config=$config, " +
                                "${StationStandDownReassertPolicy.describeLock(wifiLockHeldForMs)}); " +
                                "leaving it again (re-assertion $reassertCount/" +
                                "${StationStandDownReassertPolicy.MAX_REASSERTS}, disableNetwork returned $disabled)."
                        )
                        verifyLeft(context, wm, networkId, again = true)
                    }
                    is StationStandDownReassertPolicy.Decision.Defer -> {
                        AppLog.i(
                            "StationStandDown: the platform rejoined this unit's WiFi network " +
                                "${now - lastReassertAtMs}ms after the last re-assertion; checking again in " +
                                "${decision.delayMs}ms."
                        )
                        scheduleCheck(context, wifiLockHeldForMs, decision.delayMs, isRecheck = false)
                    }
                    StationStandDownReassertPolicy.Decision.BudgetSpent -> {
                        // Three undone re-assertions describe the ROM, so remember it per fingerprint.
                        if (!Build.FINGERPRINT.isNullOrBlank()) {
                            settings.stationStandDownContestedFingerprint = Build.FINGERPRINT
                        }
                        contestedLogged = true
                        notePlatformWon(context)
                        AppLog.i(
                            "StationStandDown: the platform undid all " +
                                "${StationStandDownReassertPolicy.MAX_REASSERTS} re-assertions in one window, " +
                                "so this unit's stand-down is marked contested on this ROM and is not " +
                                "re-asserted again (config=$config). Changing \"Leave this unit's WiFi " +
                                "network\" clears it."
                        )
                    }
                    StationStandDownReassertPolicy.Decision.Suppressed -> {
                        notePlatformWon(context)
                        if (!contestedLogged) {
                            contestedLogged = true
                            AppLog.i(
                                "StationStandDown: the platform rejoined this unit's WiFi network and the " +
                                    "stand-down is contested on this ROM, so it is left joined until the " +
                                    "wireless stack stops (config=$config)."
                            )
                        }
                    }
                    is StationStandDownReassertPolicy.Decision.Recheck ->
                        scheduleCheck(context, wifiLockHeldForMs, decision.delayMs, isRecheck = true)
                    StationStandDownReassertPolicy.Decision.Ignore -> Unit
                }
            } catch (e: Exception) {
                AppLog.w("StationStandDown: could not re-assert the stand-down: ${e.message}")
            }
        }
    }

    // One pending check at a time: a check already queued reads the station afresh when it runs.
    private fun scheduleCheck(context: Context, wifiLockHeldForMs: Long?, delayMs: Long, isRecheck: Boolean) {
        if (deferredCheck != null) return
        val check = Runnable {
            synchronized(this) { deferredCheck = null }
            onStationJoined(context, wifiLockHeldForMs, isRecheck)
        }
        deferredCheck = check
        mainHandler.postDelayed(check, delayMs)
    }

    /**
     * The session went live: refill the budget so pre-session churn cannot spend it, and check the
     * station now in case it was left joined when the old budget ran out. The spacing is kept.
     */
    fun onSessionLive(context: Context, wifiLockHeldForMs: Long?) {
        synchronized(this) {
            if (standDownAtMs == 0L) return
            sessionLiveSeen = true
            deferredCheck?.let { mainHandler.removeCallbacks(it) }
            deferredCheck = null
            reassertCount = 0
            windowStartMs = 0L
            scheduleCheck(context, wifiLockHeldForMs, 0L, isRecheck = true)
        }
    }

    /** A disconnect event reached the service: read the station so a quick rejoin still counts. */
    fun onStationLeft(context: Context) {
        isStillAssociated(context)
    }

    /**
     * Whether this unit is still joined to its own network, or null when that cannot be read.
     * Null is not "still there", so an unreadable station never holds the group up. A read of the
     * station gone during a stand-down latches it, so every caller arms the rejoin check.
     */
    fun isStillAssociated(context: Context): Boolean? {
        val associated = try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.supplicantState?.let { it == SupplicantState.COMPLETED }
        } catch (e: Exception) {
            AppLog.d("StationStandDown: could not read the station back: ${e.message}")
            null
        }
        synchronized(this) {
            if (StationStandDownReassertPolicy.latchesLeft(standDownAtMs != 0L, associated)) leftSeen = true
        }
        return associated
    }

    /**
     * Leave the current network, recording it first so it can always be put back.
     *
     * The record is written before the call, not after: a crash between the two would otherwise
     * leave the unit unable to rejoin the owner's home network with nothing anywhere saying why.
     *
     * @return true when the unit was asked to leave, so the caller can give it [VERIFY_DELAY_MS].
     */
    fun standDown(context: Context): Boolean {
        val settings = try {
            App.provide(context).settings
        } catch (e: Exception) {
            // Without somewhere to record the network id there is no way back to it, and a
            // stand-down we cannot undo is worse than one that never happened.
            AppLog.d("StationStandDown: no store for the restore record, not standing down: ${e.message}")
            return false
        }

        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            // supplicantState rather than the SSID or the network id alone, for the reason
            // logStationCoexistence gives: both of those are redacted whenever the location gate is
            // unsatisfied, which on a head unit is routine.
            val associated = info?.supplicantState == SupplicantState.COMPLETED
            val networkId = info?.networkId ?: -1
            val overlay = AppPermissions.isOverlayGranted(context)
            val mode = StationStandDownMode.fromSetting(settings.stationStandDownMode)
            val supports5Ghz = WifiBandCapability.supports5Ghz(context)
            val groupBand = P2pBandPreference.fromSetting(settings.wifiDirectBand)
            val stationFrequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info?.frequency ?: 0
            } else 0

            if (!StationStandDownPolicy.shouldStandDown(
                    mode = mode,
                    sdkInt = Build.VERSION.SDK_INT,
                    canDrawOverlays = overlay,
                    associated = associated,
                    networkId = networkId,
                    supports5Ghz = supports5Ghz,
                    stationFrequencyMhz = stationFrequency,
                    groupBand = groupBand,
                )
            ) {
                val why = StationStandDownPolicy.describeUnavailable(Build.VERSION.SDK_INT, overlay)
                when {
                    why != null -> {
                        record(StationStandDownOutcome.UNAVAILABLE)
                        AppLog.i("StationStandDown: $why")
                    }
                    !associated -> {
                        record(StationStandDownOutcome.NOT_JOINED)
                        AppLog.i(
                            "StationStandDown: this unit is not joined to another WiFi network, so " +
                                "there is nothing to stand down before creating the group."
                        )
                    }
                    networkId < 0 -> {
                        record(StationStandDownOutcome.JOINED)
                        AppLog.w(
                            "StationStandDown: this unit is joined to a network the app is not allowed " +
                                "to name, so it will not be disabled. Turning Location on usually makes " +
                                "it readable."
                        )
                    }
                    else -> {
                        record(StationStandDownOutcome.JOINED)
                        AppLog.i(
                            "StationStandDown: " + StationStandDownPolicy.describeSkipped(
                                mode, supports5Ghz, stationFrequency, groupBand
                            )
                        )
                    }
                }
                return false
            }

            ConnectionStageTracker.report(ConnectionStage.PREPARING_NETWORK)
            record(StationStandDownOutcome.STOOD_DOWN)
            synchronized(this) {
                clearReassertState()
                standDownAtMs = SystemClock.elapsedRealtime()
            }
            settings.stationStandDownNetworkId = networkId
            @Suppress("DEPRECATION")
            val disabled = wm.disableNetwork(networkId)
            @Suppress("DEPRECATION")
            wm.disconnect()
            AppLog.i(
                "StationStandDown: asked this unit to leave its WiFi network so the group can have " +
                    "the radio to itself (mode=$mode, station on ${stationFrequency}MHz, " +
                    "5GHz=$supports5Ghz, group asking for $groupBand, disableNetwork returned " +
                    "$disabled). It is rejoined when the wireless stack stops."
            )

            verifyLeft(context, wm, networkId, again = false)
            return true
        } catch (e: Exception) {
            record(StationStandDownOutcome.FAILED)
            AppLog.w("StationStandDown: could not stand the station down: ${e.message}")
            return false
        }
    }

    /**
     * Rejoin whatever [standDown] left disabled.
     *
     * Safe to call when nothing is standing, and deliberately called from more places than there are
     * stand-downs: a force-stop or a crash runs no teardown, so the next service start restores too.
     */
    fun restore(context: Context) = synchronized(this) {
        restoreLocked(context)
    }

    private fun restoreLocked(context: Context) {
        val settings = try {
            App.provide(context).settings
        } catch (e: Exception) {
            AppLog.d("StationStandDown: settings unavailable, cannot restore: ${e.message}")
            return
        }

        val networkId = try {
            settings.stationStandDownNetworkId
        } catch (e: Exception) {
            -1
        }
        if (!StationStandDownPolicy.shouldRestore(networkId)) return

        if (StationStandDownReassertPolicy.retiresRejoinIssue(sessionLiveSeen, platformWon)) {
            ConnectionIssues.clear(context, ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP)
        }
        clearReassertState()
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val enabled = wm.enableNetwork(networkId, false)
            @Suppress("DEPRECATION")
            wm.reconnect()
            if (enabled) {
                AppLog.i(
                    "StationStandDown: this unit's WiFi network is enabled again and should rejoin " +
                        "in a few seconds."
                )
            } else {
                AppLog.w(
                    "StationStandDown: the platform refused to re-enable this unit's WiFi network. " +
                        "It may have to be reconnected by hand."
                )
            }
        } catch (e: Exception) {
            AppLog.w("StationStandDown: could not restore this unit's WiFi network: ${e.message}")
        } finally {
            // Cleared whatever happened. A record we cannot act on would make every later start try
            // again against a network id that no longer means anything.
            try {
                settings.stationStandDownNetworkId = -1
            } catch (e: Exception) {
                AppLog.d("StationStandDown: could not clear the record: ${e.message}")
            }
        }
    }
}
