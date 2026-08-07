package com.andrerinas.openheadunit.connection.wifi.modes

import android.content.Context
import com.andrerinas.openheadunit.connection.wifi.WifiLauncher
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherSharedServices
import com.andrerinas.openheadunit.utils.Settings

class WifiLauncherManual(
    manager: WifiLauncherManager
) : WifiLauncher(manager) {

    override val mode = WifiLauncherMode.MANUAL

    override fun hasSameStartConfiguration(launcher: WifiLauncher) = launcher is WifiLauncherManual

    override fun hasWifiDirect() = false

    override fun hasWirelessServer() = false

    override fun hasLocalDiscovery() = false

    override fun start(quiet: Boolean) {
    }

    override fun stop() {
    }
}
