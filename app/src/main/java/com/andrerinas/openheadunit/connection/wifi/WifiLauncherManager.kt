package com.andrerinas.openheadunit.connection.wifi

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherHelper
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

class WifiLauncherManager(val service: AapService) {

    val sharedServices: WifiLauncherSharedServices = WifiLauncherSharedServices(service)
    var active: WifiLauncher? = null
        private set


    fun isActive(): Boolean = active != null

    fun getActiveMode(): WifiLauncherMode? = active?.mode

    fun setActiveFromSettings(force: Boolean = false, quiet: Boolean = true) {
        val settings = App.provide(service).settings

        setActive(settings.wifiConnectionMode, force, quiet)
    }

    fun setActive(mode: WifiLauncherMode, force: Boolean = false, quiet: Boolean = true) {
        setActive(mode.factory(this), force, quiet)
    }

    fun setActive(newLauncher: WifiLauncher, force: Boolean = false, quiet: Boolean = true) {
        if (newLauncher.manager != this)
            throw IllegalArgumentException("newLauncher.manager is different instance")
        if (active == newLauncher)
            throw IllegalArgumentException("newLauncher is already active")

        if (!force && (active?.hasSameStartConfiguration(newLauncher) ?: false)) {
            AppLog.d("WifiLauncher: WiFi Mode ${newLauncher.mode}.mode with same start-configuration is already initialized.")
            return
        }

        // Every automatic entry point lands here, including the Bluetooth auto-start that fires
        // when the phone comes into range — which on a looping unit would walk straight back into
        // the crash the guard was set to avoid. Explicit user actions release the pause first, so
        // this only ever blocks a start nobody asked for.
        if (Settings.isWirelessPausedByBootLoop(service)) {
            AppLog.w("AapService: Wireless bring-up requested, but it is paused by the boot-loop guard. Open the app to re-enable it.")
            return
        }

        AppLog.i("WifiLauncher: Initializing WiFi Mode: ${newLauncher.mode}")

        // stop old launcher
        active?.stop()

        // replace it with new one
        active = newLauncher
        sharedServices.update(newLauncher)
        active?.start(quiet)
    }

    fun stop() {
        if (active == null)
            return

        sharedServices.stopAll()
        active?.stop()
        active = null
    }

    fun forceStartDiscoveryScan() {
        val discovery = sharedServices.localDiscovery

        if (discovery != null) {
            discovery.stop()
            discovery.startScan()
        }
    }

    fun startDiscovery(oneShot: Boolean = false) {
        // Allow discovery for Strategy 0 (NSD), 3 (Phone Hotspot) and 4 (Headunit Hotspot)
        if (active == null || active?.hasLocalDiscovery() == false)
            return

        sharedServices.startLocalDiscovery(oneShot)
    }

    fun restartDiscovery() {
        active?.restartDiscovery()
    }
}
