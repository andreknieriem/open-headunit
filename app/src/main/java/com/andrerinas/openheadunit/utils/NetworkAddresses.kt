package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

/**
 * This device's own address on the WiFi network it has *joined*, as opposed to one it is hosting.
 *
 * Exists because those two are otherwise indistinguishable from the outside: a head unit that runs
 * a soft AP on one radio and a station connection on another shows two `wlan*` interfaces, both up
 * and both holding a site-local IPv4. Knowing the station's address is what lets
 * [com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApNetworkPolicy] rule it out.
 */
object NetworkAddresses {

    /** The station IPv4, or null when this device is not associated with a WiFi network. */
    fun stationIpv4(context: Context): String? =
        fromConnectivityManager(context) ?: fromWifiManager(context)

    /**
     * Asks for the WiFi transport specifically rather than the active network: the active one may
     * be the VPN this app itself runs, or the AP, neither of which is the station.
     */
    private fun fromConnectivityManager(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.asSequence()
                .filter { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps != null &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                .mapNotNull { network ->
                    cm.getLinkProperties(network)?.linkAddresses
                        ?.firstOrNull { it.address is Inet4Address }
                        ?.address?.hostAddress
                }
                .firstOrNull()
        } catch (e: Exception) {
            AppLog.d("NetworkAddresses: ConnectivityManager lookup failed: ${e.message}")
            null
        }
    }

    /** Older devices, and anything the transport query could not answer. */
    @Suppress("DEPRECATION")
    private fun fromWifiManager(context: Context): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val raw = wm.connectionInfo?.ipAddress ?: 0
        // 0 means "not associated", which is the common case while hosting an access point.
        if (raw == 0) null else
            "${raw and 0xFF}.${(raw shr 8) and 0xFF}.${(raw shr 16) and 0xFF}.${(raw shr 24) and 0xFF}"
    } catch (e: Exception) {
        AppLog.d("NetworkAddresses: WifiManager lookup failed: ${e.message}")
        null
    }
}
