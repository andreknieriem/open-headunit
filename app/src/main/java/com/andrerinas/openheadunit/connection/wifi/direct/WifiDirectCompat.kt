package com.andrerinas.openheadunit.connection.wifi.direct

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Handles WiFi P2P API calls that require higher API levels to avoid NoClassDefFoundError
 * on older Android versions during class loading of [WifiDirectManager].
 */
object WifiDirectCompat {

    /**
     * Safely calls [WifiP2pManager.requestDeviceInfo] if running on API 29+.
     */
    fun requestDeviceInfo(
        manager: WifiP2pManager?,
        channel: WifiP2pManager.Channel?,
        onDeviceAvailable: (address: String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null && channel != null) {
            Api29Impl.requestDeviceInfo(manager, channel, onDeviceAvailable)
        }
    }

    fun getGroupFrequency(group: WifiP2pGroup?): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && group != null) {
            return Api29Impl.getGroupFrequency(group)
        }
        return 0
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29Impl {
        fun requestDeviceInfo(
            manager: WifiP2pManager,
            channel: WifiP2pManager.Channel,
            onDeviceAvailable: (address: String) -> Unit
        ) {
            try {
                manager.requestDeviceInfo(channel) { device ->
                    device?.let {
                        onDeviceAvailable(it.deviceAddress ?: "00:00:00:00:00:00")
                    }
                }
            } catch (e: Exception) {
                AppLog.w("WifiDirectCompat: requestDeviceInfo failed: ${e.message}")
            }
        }

        fun getGroupFrequency(group: WifiP2pGroup): Int {
            return group.frequency
        }
    }
}
