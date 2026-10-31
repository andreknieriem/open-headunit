package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.wifi.direct.P2pStackWedgePolicy
import com.andrerinas.openheadunit.utils.adb.AdbManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Restarts WiFi to free a wedged WiFi Direct stack ([P2pStackWedgePolicy]). Android 10+ refuses
 * `setWifiEnabled` to apps, so this uses [OemAppManager]'s routes: root, or this unit's own ADB.
 * With neither, [wifiPanelIntent] is the one-tap fallback.
 */
object WifiStackRecovery {

    /** Which route a restart would take right now, without prompting for anything. */
    suspend fun availableRoute(): OemAppManager.ExecutionBackend = OemAppManager.checkAvailableBackend()

    /**
     * Ask for root once, so the prompt appears while the user is setting this up rather than on
     * the road. Returns true if root is now granted. Safe to call when it already is.
     */
    suspend fun requestRoot(): Boolean = OemAppManager.requestRoot()

    /**
     * Run one harmless command over local ADB, so an unpaired key raises "Allow USB debugging?" now,
     * at the settings screen, and not at the first automatic restart. True if the command ran.
     */
    suspend fun verifyAdb(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val (code, out) = AdbManager.exec(context, "echo ok")
            code == 0 && out.contains("ok")
        } catch (e: Exception) {
            AppLog.w("WifiStackRecovery: ADB check failed: ${e.message}")
            false
        }
    }

    /**
     * Switch WiFi off and on. Returns whether the command ran, and what it printed.
     * The WiFi Direct manager rebuilds its group by itself once the stack reports enabled again.
     * [automatic] is true when no user is looking: then only a route already proven at setup runs.
     */
    suspend fun restartWifi(context: Context, reason: String, automatic: Boolean = false): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var route = availableRoute()
        // A fresh process has not opened a root shell yet, so libsu cannot say root is granted
        // until it asks - and asking raises the superuser prompt when no grant is saved. So only
        // ask when the user already granted it from the settings switch; the superuser app then
        // answers from its saved policy without showing anything.
        val rootGrantedBefore = try { App.provide(context).settings.wifiRestartRootGranted } catch (e: Exception) { false }
        if (route != OemAppManager.ExecutionBackend.ROOT && rootGrantedBefore && Shell.isAppGrantedRoot() == null) {
            if (requestRoot()) {
                route = OemAppManager.ExecutionBackend.ROOT
            } else if (automatic) {
                // The grant was revoked or expired. Forget it, so no later restart opens a shell and
                // puts the superuser prompt in front of a driver again.
                try { App.provide(context).settings.wifiRestartRootGranted = false } catch (e: Exception) { }
                AppLog.w("WifiStackRecovery: root is no longer granted; automatic restarts will not ask for it again.")
            }
        }
        if (route == OemAppManager.ExecutionBackend.NONE) {
            AppLog.w("WifiStackRecovery: cannot restart WiFi ($reason): no root and no local ADB.")
            return@withContext false to "no root and no local ADB"
        }
        val adbVerified = try { App.provide(context).settings.wifiRestartAdbVerified } catch (e: Exception) { false }
        if (automatic && !P2pStackWedgePolicy.mayRestartAutomatically(
                viaRoot = route == OemAppManager.ExecutionBackend.ROOT,
                viaAdb = route == OemAppManager.ExecutionBackend.ADB,
                adbVerified = adbVerified,
            )
        ) {
            AppLog.w("WifiStackRecovery: not restarting WiFi ($reason): the $route route was not proven when the switch was turned on.")
            return@withContext false to "route not proven at setup"
        }
        AppLog.w("WifiStackRecovery: restarting WiFi via $route ($reason).")
        val (code, out) = try {
            OemAppManager.executeCommand(context, P2pStackWedgePolicy.RESTART_COMMAND)
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
        val ok = code == 0
        if (ok) AppLog.i("WifiStackRecovery: WiFi restarted via $route.")
        else AppLog.w("WifiStackRecovery: WiFi restart via $route failed (code=$code): ${out.take(200)}")
        ok to out
    }

    /** The system WiFi panel on Android 10+, the WiFi settings page before it. */
    fun wifiPanelIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(android.provider.Settings.Panel.ACTION_WIFI)
        } else {
            Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        }
}
