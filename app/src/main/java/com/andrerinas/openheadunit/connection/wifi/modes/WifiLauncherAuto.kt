package com.andrerinas.openheadunit.connection.wifi.modes

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.wifi.WifiLauncher
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherStopSequence
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class WifiLauncherAuto(
    manager: WifiLauncherManager,
    val useWifiDirect: Boolean,
) : WifiLauncher(manager) {
    constructor(manager: WifiLauncherManager) : this(manager, App.provide(manager.service).settings.headunitServerWifiDirect)
    private var bringUpJob: Job? = null

    override val mode = WifiLauncherMode.AUTO

    override fun hasSameStartConfiguration(launcher: WifiLauncher): Boolean {
        return launcher is WifiLauncherAuto && launcher.useWifiDirect == useWifiDirect
    }

    override fun hasWifiDirect() = useWifiDirect

    override fun hasWirelessServer() = true

    override fun hasLocalDiscovery() = true

    override fun start(noInfoToasts: Boolean) {
        // Auto discovery for standard server mode via NSD/mDNS
        // #startDiscovery(oneShot = false) handled by SharedServices
        ConnectionStageTracker.report(ConnectionStage.SEARCHING)
        if (useWifiDirect) {
            if (bringUpJob != null) return
            bringUpJob = service.serviceScope.launch {
                manager.sharedServices.hotspotTeardown?.join()
                val wifi = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!wifi.isWifiEnabled && Build.VERSION.SDK_INT < 29) {
                    @Suppress("DEPRECATION")
                    runCatching { wifi.isWifiEnabled = true }
                    var attempts = 0
                    while (!wifi.isWifiEnabled && attempts++ < 10) delay(500)
                }
                ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
                manager.sharedServices.wifiDirectManager?.makeVisible()
            }
        }
    }

    override fun restartDiscovery() {
        if (!useWifiDirect) { super.restartDiscovery(); return }
        // Missing address/client is not a reason to tear down and recreate a group.
        // WifiDirectManager owns recovery; SharedServices gates scans on a fresh P2P snapshot.
        manager.startDiscovery()
    }

    override fun stop(seq: WifiLauncherStopSequence) {
        bringUpJob?.cancel()
        bringUpJob = null
    }
}
