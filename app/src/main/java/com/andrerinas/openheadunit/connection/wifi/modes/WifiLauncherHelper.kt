package com.andrerinas.openheadunit.connection.wifi.modes

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbyManager
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncher
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherStopSequence
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WifiLauncherHelper : WifiLauncher {

    private val strategy: HelperStrategy

    var nearbyManager: NearbyManager? = null
        private set

    constructor(manager: WifiLauncherManager) : super(manager) {
        // copy settings early in construction to align with #hasSameStartConfiguration
        this.strategy = settings.helperConnectionStrategy
    }

    constructor(manager: WifiLauncherManager, strategy: HelperStrategy) : super(manager) {
        this.strategy = strategy
    }


    override val mode = WifiLauncherMode.HELPER

    override fun hasSameStartConfiguration(launcher: WifiLauncher): Boolean {
        return launcher is WifiLauncherHelper && launcher.strategy == strategy
    }

    override fun hasWifiDirect() = strategy == HelperStrategy.WIFI_DIRECT

    override fun hasWirelessServer() = true

    override fun hasLocalDiscovery(): Boolean {
        return strategy == HelperStrategy.COMMON_WIFI || strategy == HelperStrategy.PHONE_HOTSPOT || strategy == HelperStrategy.HEADUNIT_HOTSPOT
    }


    override fun start(noInfoToasts: Boolean) {
        AppLog.i("WifiLauncher: Using strategy $strategy.")

        when (strategy) {
            HelperStrategy.COMMON_WIFI -> { /* #startDiscovery(oneShot = false) handled by SharedServices */ }
            HelperStrategy.WIFI_DIRECT -> {
                val wifiManager = service.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiDirect = manager.sharedServices.wifiDirectManager!!

                if (wifiManager.isWifiEnabled) {
                    wifiDirect.makeVisible()
                } else if (!noInfoToasts) {
                    ToastUtils.showToast(service, service.getString(R.string.wifi_disabled_info), Toast.LENGTH_SHORT)
                }
            }
            HelperStrategy.NEARBY_DEVICES -> { // Google Nearby
                initNearbyManager()
                nearbyManager?.start()
            }
            HelperStrategy.PHONE_HOTSPOT, HelperStrategy.HEADUNIT_HOTSPOT -> { /* Host/Passive - just wait for connection on WirelessServer port */ }
        }

        // Hotspot logic for Helper mode if enabled (only for Strategy 4: Headunit Hotspot)
        if (settings.autoEnableHotspot && strategy == HelperStrategy.HEADUNIT_HOTSPOT) {
            service.serviceScope.launch {
                AppLog.i("AapService: Auto-enabling hotspot for Helper mode...")
                HotspotManager.setHotspotEnabled(service, true)
            }
        }
    }

    override fun restartDiscovery() {
        when (strategy) {
            HelperStrategy.NEARBY_DEVICES -> nearbyManager?.start()
            HelperStrategy.WIFI_DIRECT -> {
                val wifiManager = service.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiDirect = manager.sharedServices.wifiDirectManager

                if (wifiManager.isWifiEnabled) {
                    wifiDirect?.makeVisible()
                }
            }
            else -> super.restartDiscovery()
        }
    }

    override fun stop(seq: WifiLauncherStopSequence) {
        if (!seq.handledAt(WifiLauncherStopSequence.LAST))
            return

        nearbyManager?.stop()
    }

    private fun initNearbyManager() {
        if (nearbyManager != null)
            return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                nearbyManager = NearbyManager(service, service.serviceScope) { socket ->
                    val appSettings = App.provide(service).settings
                    appSettings.saveLastConnection(Settings.CONNECTION_TYPE_NEARBY)
                    service.serviceScope.launch(Dispatchers.IO) {
                        App.provide(service).commManager.connect(socket)
                    }
                }
            } catch (e: Exception) {
                AppLog.e("AapService: Failed to init NearbyManager: ${e.message}")
            }
        }
    }
}
