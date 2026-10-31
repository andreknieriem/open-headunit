package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.ConnectionOperation
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
    private var owner: ConnectionOperation? = null
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

    /** The network's WifiConfiguration.status, or null when the platform will not say. */
    private fun readConfigStatus(wm: WifiManager, networkId: Int): Int? = try {
        @Suppress("DEPRECATION")
        wm.configuredNetworks?.firstOrNull { it.networkId == networkId }?.status
    } catch (e: Exception) {
        null
    }

    private fun verifyLeft(context: Context, wm: WifiManager, networkId: Int, again: Boolean, operation: ConnectionOperation) {
        mainHandler.postDelayed({
            val commManager = App.provide(context).commManager
            if (!commManager.isCurrent(operation) || synchronized(this) { owner !== operation }) return@postDelayed
            val config = StationStandDownReassertPolicy.describeConfigStatus(readConfigStatus(wm, networkId))
            val associated = isStillAssociated(context)
            val accepted = commManager.withAppConnectionLifecycleLock {
                synchronized(this) {
                    if (owner !== operation || !commManager.isCurrent(operation)) false else {
                        if (associated == true) {
                            record(StationStandDownOutcome.STILL_JOINED)
                            if (again) platformWon = true
                        }
                        true
                    }
                }
            }
            if (!accepted) return@postDelayed
            if (associated == true) {
                if (again) ConnectionIssues.raiseOnce(context, ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP)
                AppLog.w("StationStandDown: this unit is still joined to its WiFi network ${VERIFY_DELAY_MS}ms later" +
                    (if (again) " after a re-assertion" else "") + " (config=$config).")
            } else {
                AppLog.i("StationStandDown: this unit has left its WiFi network" + (if (again) " again" else "") + " (config=$config).")
            }
        }, VERIFY_DELAY_MS)
    }

    /**
     * A WiFi join reached the service: if a stand-down is in force and the platform undid it,
     * disconnect again, within [StationStandDownReassertPolicy]'s budget.
     */
    fun onStationJoined(context: Context, wifiLockHeldForMs: Long?, isRecheck: Boolean = false) {
        val operation = synchronized(this) { owner } ?: return
        reassert(context, operation, wifiLockHeldForMs, isRecheck)
    }

    private fun reassert(context: Context, operation: ConnectionOperation, wifiLockHeldForMs: Long?, isRecheck: Boolean) {
        val commManager = App.provide(context).commManager
        if (!commManager.isCurrent(operation)) return
        try {
            val settings = App.provide(context).settings
            val networkId = settings.stationStandDownNetworkId
            val mode = StationStandDownMode.fromSetting(settings.stationStandDownMode)
            val now = SystemClock.elapsedRealtime()
            val associated = isStillAssociated(context)
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = StationStandDownReassertPolicy.describeConfigStatus(readConfigStatus(wm, networkId))
            // Decide against the exact owner while Stop and a replacement stand-down are excluded.
            // Radio operations below are outside both lifecycle and station monitors.
            val (decision, logSuppressed) = commManager.withAppConnectionLifecycleLock {
                synchronized(this) {
                    if (owner !== operation || !commManager.isCurrent(operation)) null else {
                        val next = StationStandDownReassertPolicy.decide(
                            mode, networkId, associated, leftSeen, reassertCount, windowStartMs, now,
                            lastReassertAtMs,
                            StationStandDownReassertPolicy.isContested(settings.stationStandDownContestedFingerprint, Build.FINGERPRINT),
                            isRecheck
                        )
                        val logSuppressed = !contestedLogged
                        when (next) {
                            StationStandDownReassertPolicy.Decision.Reassert -> {
                                if (StationStandDownReassertPolicy.opensWindow(windowStartMs, now)) {
                                    windowStartMs = now
                                    reassertCount = 0
                                }
                                reassertCount++
                                lastReassertAtMs = now
                            }
                            StationStandDownReassertPolicy.Decision.BudgetSpent,
                            StationStandDownReassertPolicy.Decision.Suppressed -> {
                                contestedLogged = true
                                platformWon = true
                            }
                            else -> Unit
                        }
                        next to logSuppressed
                    }
                }
            } ?: return
            when (decision) {
                StationStandDownReassertPolicy.Decision.Reassert -> {
                    @Suppress("DEPRECATION")
                    val disabled = wm.disableNetwork(networkId)
                    @Suppress("DEPRECATION")
                    wm.disconnect()
                    AppLog.i("StationStandDown: leaving this unit's rejoined WiFi network again (config=$config, " +
                        "${StationStandDownReassertPolicy.describeLock(wifiLockHeldForMs)}; disableNetwork returned $disabled).")
                    verifyLeft(context, wm, networkId, again = true, operation = operation)
                }
                is StationStandDownReassertPolicy.Decision.Defer ->
                    scheduleCheck(context, operation, wifiLockHeldForMs, decision.delayMs, isRecheck = false)
                StationStandDownReassertPolicy.Decision.BudgetSpent -> {
                    if (!Build.FINGERPRINT.isNullOrBlank()) settings.stationStandDownContestedFingerprint = Build.FINGERPRINT
                    ConnectionIssues.raiseOnce(context, ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP)
                    AppLog.i("StationStandDown: the platform undid all ${StationStandDownReassertPolicy.MAX_REASSERTS} re-assertions; " +
                        "the stand-down is contested on this ROM and will not be re-asserted again (config=$config).")
                }
                StationStandDownReassertPolicy.Decision.Suppressed -> {
                    ConnectionIssues.raiseOnce(context, ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP)
                    if (logSuppressed) AppLog.i("StationStandDown: the contested stand-down leaves this unit joined until the wireless stack stops (config=$config).")
                }
                is StationStandDownReassertPolicy.Decision.Recheck ->
                    scheduleCheck(context, operation, wifiLockHeldForMs, decision.delayMs, isRecheck = true)
                StationStandDownReassertPolicy.Decision.Ignore -> Unit
            }
        } catch (e: Exception) {
            AppLog.w("StationStandDown: could not re-assert the stand-down: ${e.message}")
        }
    }

    // One pending check at a time: a check already queued reads the station afresh when it runs.
    private fun scheduleCheck(context: Context, operation: ConnectionOperation, wifiLockHeldForMs: Long?, delayMs: Long, isRecheck: Boolean) {
        lateinit var check: Runnable
        check = Runnable {
            val accepted = App.provide(context).commManager.withAppConnectionLifecycleLock {
                synchronized(this) {
                    if (owner !== operation || deferredCheck !== check || !App.provide(context).commManager.isCurrent(operation)) false
                    else { deferredCheck = null; true }
                }
            }
            if (accepted) reassert(context, operation, wifiLockHeldForMs, isRecheck)
        }
        val scheduled = synchronized(this) {
            if (owner !== operation || deferredCheck != null) false else { deferredCheck = check; true }
        }
        if (scheduled) mainHandler.postDelayed(check, delayMs)
    }

    /**
     * The session went live: refill the budget so pre-session churn cannot spend it, and check the
     * station now in case it was left joined when the old budget ran out. The spacing is kept.
     */
    fun onSessionLive(context: Context, wifiLockHeldForMs: Long?) {
        val operation = synchronized(this) { owner } ?: return
        val commManager = App.provide(context).commManager
        val accepted = commManager.withAppConnectionLifecycleLock {
            synchronized(this) {
                if (owner !== operation || !commManager.isCurrent(operation) || standDownAtMs == 0L) false else {
                    sessionLiveSeen = true
                    deferredCheck?.let { mainHandler.removeCallbacks(it) }
                    deferredCheck = null
                    reassertCount = 0
                    windowStartMs = 0L
                    true
                }
            }
        }
        if (accepted) scheduleCheck(context, operation, wifiLockHeldForMs, 0L, isRecheck = true)
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
    fun standDown(context: Context, operation: ConnectionOperation): Boolean {
        val commManager = App.provide(context).commManager
        if (!commManager.isCurrent(operation)) return false
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
            val accepted = commManager.withAppConnectionLifecycleLock {
                synchronized(this) {
                    if (!commManager.isCurrent(operation)) false else {
                        owner = operation
                        clearReassertState()
                        standDownAtMs = SystemClock.elapsedRealtime()
                        settings.stationStandDownNetworkId = networkId
                        true
                    }
                }
            }
            if (!accepted) return false
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

            verifyLeft(context, wm, networkId, again = false, operation = operation)
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
    fun restore(context: Context, operation: ConnectionOperation) = restoreOwned(context, operation)

    /** Crash recovery at service creation may restore a persisted record only when no arm owns it. */
    fun restoreAfterServiceStart(context: Context) = restoreOwned(context, null)

    private fun restoreOwned(context: Context, expectedOwner: ConnectionOperation?) {
        val settings = try { App.provide(context).settings } catch (e: Exception) {
            AppLog.d("StationStandDown: settings unavailable, cannot restore: ${e.message}")
            return
        }
        val retired = synchronized(this) {
            if (owner !== expectedOwner) return
            val networkId = try { settings.stationStandDownNetworkId } catch (_: Exception) { -1 }
            if (!StationStandDownPolicy.shouldRestore(networkId)) { owner = null; return }
            val retiresIssue = StationStandDownReassertPolicy.retiresRejoinIssue(sessionLiveSeen, platformWon)
            owner = null
            clearReassertState()
            // Clear the exact owner's persisted record before releasing it to a later stand-down.
            try { settings.stationStandDownNetworkId = -1 } catch (e: Exception) {
                AppLog.d("StationStandDown: could not clear the record: ${e.message}")
            }
            networkId to retiresIssue
        }
        if (retired.second) ConnectionIssues.clear(context, ConnectionIssue.HOME_WIFI_REJOINED_BESIDE_GROUP)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val enabled = wm.enableNetwork(retired.first, false)
            @Suppress("DEPRECATION")
            wm.reconnect()
            if (enabled) AppLog.i("StationStandDown: this unit's WiFi network is enabled again and should rejoin shortly.")
            else AppLog.w("StationStandDown: the platform refused to re-enable this unit's WiFi network; it may need reconnecting by hand.")
        } catch (e: Exception) {
            AppLog.w("StationStandDown: could not restore this unit's WiFi network: ${e.message}")
        }
    }
}
