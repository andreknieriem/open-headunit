package com.andrerinas.openheadunit.connection.broker

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.andrerinas.openheadunit.App

/**
 * Reads the live Wi-Fi Direct group this display is hosting.
 * The passphrase only exists on the live group, not in the saved advertisement digest.
 */
object WifiDirectDisplayInfo {
    @SuppressLint("MissingPermission")
    fun read(context: Context, onResult: (NativeBTBrokerProtocol.DisplayInfo) -> Unit) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            onResult(fallback(context))
            return
        }
        val channel = manager.initialize(app, Looper.getMainLooper(), null)
        if (channel == null) {
            onResult(fallback(context))
            return
        }
        manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                onResult(fromGroup(context, group, info))
            }
        }
    }

    private fun fromGroup(
        context: Context,
        group: WifiP2pGroup?,
        info: WifiP2pInfo?,
    ): NativeBTBrokerProtocol.DisplayInfo {
        val saved = App.provide(context).settings.wifiDirectAdvertisedEndpoint
        return display(
            ip = info?.groupOwnerAddress?.hostAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: saved?.ip.orEmpty(),
            ssid = group?.networkName?.takeIf { it.isNotBlank() } ?: saved?.ssid.orEmpty(),
            key = group?.passphrase.orEmpty(),
            bssid = group?.owner?.deviceAddress?.takeIf { it.isNotBlank() } ?: saved?.bssid.orEmpty(),
        )
    }

    private fun fallback(context: Context): NativeBTBrokerProtocol.DisplayInfo {
        val saved = App.provide(context).settings.wifiDirectAdvertisedEndpoint
        return display(
            ip = saved?.ip.orEmpty(),
            ssid = saved?.ssid.orEmpty(),
            key = "",
            bssid = saved?.bssid.orEmpty(),
        )
    }

    private fun display(ip: String, ssid: String, key: String, bssid: String) = NativeBTBrokerProtocol.DisplayInfo(
        ipAddress = ip,
        port = NativeBTBrokerProtocol.PROJECTION_PORT,
        ssid = ssid,
        key = key,
        bssid = bssid,
    )
}
