package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherHelper
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy

/** A warning that the link carrying an active session is about to go away. */
enum class LinkLossTrigger {
    /** The device is shutting down or rebooting. Everything is about to go away. */
    DEVICE_SHUTDOWN,

    /** WiFi station mode is being switched off. Only sessions riding it are affected. */
    WIFI_STATION_DISABLING
}

/**
 * Whether to close the current session *now*, while the link still works, rather than let it die
 * with the interface.
 *
 * Android Auto's head unit server is wedged permanently by a peer that vanishes without closing: it
 * goes on accepting connections and answering none of them, and only a restart on the phone brings
 * it back. A session that closes properly does not do that — which is the difference between a
 * drive that ends with the app disconnecting and one that ends with the power being cut.
 *
 * We cannot help the cases that arrive without warning (driving out of range, the access point
 * restarting, a power cut with no orderly shutdown). We can help the ones the system tells us about
 * first, and this decides which of those apply to the session actually running.
 */
object LinkLossTeardownPolicy {

    fun shouldTearDown(
        trigger: LinkLossTrigger,
        launcher: WifiLauncher,
        sessionIsWireless: Boolean = true
    ): Boolean = when (trigger) {
        // The whole device is going, so every route's link is going with it — USB included.
        LinkLossTrigger.DEVICE_SHUTDOWN -> true

        // Only routes that ride WiFi station mode. A P2P group and a soft AP are separate
        // interfaces, and on several chipsets they outlive the station toggle entirely — tearing
        // a healthy Native AA session down here would cost a 45-90s reconnect to prevent nothing.
        // A USB session rides none of it and must be left alone whatever the settings say.
        LinkLossTrigger.WIFI_STATION_DISABLING ->
            sessionIsWireless &&
                !launcher.hasWifiDirect() &&
                !ridesOwnAccessPoint(launcher)
    }

    /** The two routes where the phone sits on an access point this device is hosting. */
    private fun ridesOwnAccessPoint(launcher: WifiLauncher): Boolean {
        return when (launcher) {
            is WifiLauncherNative -> launcher.strategy == NativeStrategy.HOTSPOT
            is WifiLauncherHelper -> launcher.strategy == HelperStrategy.HEADUNIT_HOTSPOT
            else -> false
        }
    }
}
