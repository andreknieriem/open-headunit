package com.andrerinas.openheadunit.connection.wifi.modes

import com.andrerinas.openheadunit.connection.wifi.WifiLauncher
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode

class WifiLauncherAuto(
    manager: WifiLauncherManager
) : WifiLauncher(manager) {

    override val mode = WifiLauncherMode.AUTO

    override fun hasSameStartConfiguration(launcher: WifiLauncher): Boolean {
        return launcher is WifiLauncherAuto
    }

    override fun hasWifiDirect() = false

    override fun hasWirelessServer() = true

    override fun hasLocalDiscovery() = true

    override fun start(quiet: Boolean) {
        // Auto discovery for standard server mode via NSD/mDNS
        // #startDiscovery(oneShot = false) handled by SharedServices
    }

    override fun stop() {
    }
}
