package com.andrerinas.openheadunit.connection.self.launchers

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager.Companion.AA_PACKAGE
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherManual
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.delay

class SelfLauncherLegacy(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "v17.3 and older"

    override suspend fun run(): Boolean {
        runWifiLauncher()

        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            services.connectivityManager.activeNetwork else null
        val networkToUse = activeNetwork ?: services.fakeNetwork
        val fakeWifiInfo = services.fakeWifiInfo

        val magicalIntent = Intent().apply {
            setClassName(
                AA_PACKAGE,
                "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            putExtra("PARAM_SERVICE_PORT", 5288)
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            fakeWifiInfo?.let { putExtra("wifi_info", it) }
        }

        AppLog.i("SelfMode: Launching AA Wireless Startup via Activity...")
        services.ensureLaunchAllowed()
        services.aap.startActivity(magicalIntent)
        return true
    }

    suspend fun runWifiLauncher() {
        // The port, not a wireless mode. Self Mode projects this device's own Android Auto over
        // the loopback address; it needs 5288 bound and nothing else. Arming the Native
        // launcher instead replaced whatever mode the user had configured, created a P2P group
        // and opened the RFCOMM listeners and poke loop it has no use for, could be refused
        // outright by the boot-loop guard, no-opped for a user already in Native mode because
        // it did not force, and on the hotspot strategy never bound 5288 at all - while the
        // magic intent below still points Gearhead at 127.0.0.1:5288.
        //
        // The launcher argument only decides whether an NSD record is registered, and Self Mode
        // wants none: WifiLauncherManual answers false and starts nothing of its own.
        services.wifiLauncherManager.sharedServices.startWirelessServer(
            services.wifiLauncherManager.active ?: WifiLauncherManual(services.wifiLauncherManager)
        )

        val connectivityManager = services.aap.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager.activeNetwork == null) {
            // Wait up to 1 second for the Dummy VPN to become the active network
            for (i in 1..10) {
                if (connectivityManager.activeNetwork != null) break
                delay(100)
            }
        }
    }
}
