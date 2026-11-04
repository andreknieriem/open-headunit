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

    /**
     * The id is optional metadata, not a prerequisite for using a group. The getter did not
     * exist on API 16, was hidden on 17–29, and became public on 30.
     *
     * null means unreadable, not profile 0 or a framework temporary/persistent sentinel.
     * Keep the group usable without this metadata, but never derive a deletion target from it.
     */
    fun getGroupNetworkId(group: WifiP2pGroup): Int? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Api30Impl.getGroupNetworkId(group)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 -> LegacyNetworkId.read(group)
        else -> null
    }

    private object LegacyNetworkId {
        // Cache both success and refusal: this metadata must not repeatedly probe hidden APIs.
        private val getter = try {
            WifiP2pGroup::class.java.getMethod("getNetworkId")
        } catch (e: Exception) {
            null
        }

        fun read(group: WifiP2pGroup): Int? = try {
            getter?.invoke(group) as? Int
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private object Api30Impl {
        fun getGroupNetworkId(group: WifiP2pGroup): Int = group.networkId
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
