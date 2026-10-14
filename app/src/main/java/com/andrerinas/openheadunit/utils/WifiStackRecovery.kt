package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.wifi.direct.P2pStackWedgePolicy
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Restarts this unit's WiFi to free a wedged WiFi Direct stack. See [P2pStackWedgePolicy].
 *
 * Android 10 and later return false from `WifiManager.setWifiEnabled` for an app targeting them,
 * so the restart goes through the same two privileged routes [OemAppManager] uses: root, or this
 * unit's own ADB on 127.0.0.1:5555. With neither, [wifiPanelIntent] is the one-tap fallback.
 */
object WifiStackRecovery {

    /** Which route a restart would take right now, without prompting for anything. */
    suspend fun availableRoute(): OemAppManager.ExecutionBackend = OemAppManager.checkAvailableBackend()

    /**
     * Ask for root once, so the prompt appears while the user is setting this up rather than on
     * the road. Returns true if root is now granted. Safe to call when it already is.
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell()
            Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            AppLog.w("WifiStackRecovery: root request failed: ${e.message}")
            false
        }
    }

    /**
     * Switch WiFi off and on. Returns whether the command ran, and what it printed.
     * The WiFi Direct manager rebuilds its group by itself once the stack reports enabled again.
     */
    suspend fun restartWifi(context: Context, reason: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var route = availableRoute()
        // A fresh process has not opened a root shell yet, so libsu cannot say root is granted
        // until it asks - and asking raises the superuser prompt when no grant is saved. So only
        // ask when the user already granted it from the settings switch; the superuser app then
        // answers from its saved policy without showing anything.
        val rootGrantedBefore = try { App.provide(context).settings.wifiRestartRootGranted } catch (e: Exception) { false }
        if (route == OemAppManager.ExecutionBackend.NONE && rootGrantedBefore &&
            Shell.isAppGrantedRoot() == null && requestRoot()
        ) {
            route = OemAppManager.ExecutionBackend.ROOT
        }
        if (route == OemAppManager.ExecutionBackend.NONE) {
            AppLog.w("WifiStackRecovery: cannot restart WiFi ($reason): no root and no local ADB.")
            return@withContext false to "no root and no local ADB"
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
