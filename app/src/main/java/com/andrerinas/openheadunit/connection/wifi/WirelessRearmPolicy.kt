package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy

/**
 * Whether a settings change has to re-arm the wireless launcher.
 *
 * A launcher freezes its start configuration when it is built, so a field changed here that only
 * reaches SharedPreferences leaves the running stack on the old one: the transport was missing from
 * this list and a unit switched to the hotspot kept hosting a P2P group until something else
 * happened to re-arm it.
 */
object WirelessRearmPolicy {

    /**
     * Everything a launcher reads once at construction. Named in one place so a field added later
     * cannot be remembered at one call site and forgotten at the other.
     */
    data class Config(
        val wifiConnectionMode: WifiLauncherMode,
        val helperConnectionStrategy: HelperStrategy,
        val nativeApStrategy: NativeStrategy,
        val bluetoothManagerServiceName: String,
        /** Whether WiFi is one of the chosen connection modes; unchecking it stops the stack. */
        val wirelessSelected: Boolean,
        /** The band and channel a group is created on, both frozen by createGroup. */
        val wifiDirectBand: Int,
        val fiveGhzChannel: Int,
        /** The two that decide whether the Native servers start at all. */
        val externalBtZbtTransport: Boolean,
        val nativeAaIgnoreExternalBt: Boolean,
        /** Read once by the Helper launcher, and once per listener start. */
        val autoEnableHotspot: Boolean,
        val insecureAaRfcommListener: Boolean,
        val headunitServerWifiDirect: Boolean = false,
    )

    fun requiresRearm(before: Config, after: Config): Boolean = before != after
}
