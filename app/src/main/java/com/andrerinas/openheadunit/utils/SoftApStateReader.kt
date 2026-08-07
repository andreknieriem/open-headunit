package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApState

/**
 * Asks the framework whether this device is running an access point.
 *
 * `WifiManager.getWifiApState()` is the soft-AP counterpart to the public `getWifiState()`, but it
 * is not public API, so it is reached by reflection and its constants are written out here. On
 * devices where the non-SDK interface restrictions block it the call throws, which is reported as
 * [SoftApState.UNKNOWN] rather than as "no access point" — a question we could not ask is not a
 * question answered no, and callers treat the two very differently.
 */
object SoftApStateReader {

    private const val WIFI_AP_STATE_ENABLED = 13

    fun read(context: Context): SoftApState = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        when (val state = wm.javaClass.getMethod("getWifiApState").invoke(wm) as? Int) {
            null -> SoftApState.UNKNOWN
            WIFI_AP_STATE_ENABLED -> SoftApState.ENABLED
            // 10 disabling, 11 disabled, 12 enabling, 14 failed. Enabling counts as not-yet:
            // callers poll, and it resolves on a later pass.
            else -> {
                AppLog.d("SoftApStateReader: getWifiApState reports $state (13 = enabled).")
                SoftApState.NOT_ENABLED
            }
        }
    } catch (e: Exception) {
        AppLog.d("SoftApStateReader: getWifiApState unavailable: ${e.message}")
        SoftApState.UNKNOWN
    }
}
