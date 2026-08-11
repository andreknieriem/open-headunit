package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.AapService.Companion.scanningState
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.utils.VpnControl
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WifiLauncherSharedServices(val service: AapService) {

    var wifiDirectManager: WifiDirectManager? = null
        private set
    var wirelessServer: WirelessServer? = null
        private set
    var localDiscovery: NetworkDiscovery? = null
        private set

    fun update(active: WifiLauncher) {
        if (active.hasWifiDirect()) startWifiDirect() else stopWifiDirect()
        if (active.hasWirelessServer()) startWirelessServer(active) else stopWirelessServer()
        if (active.hasLocalDiscovery()) startLocalDiscovery(oneShot = false) else stopLocalDiscovery()
    }

    fun stopAll() {
        stopWifiDirect()
        stopWirelessServer()
        stopLocalDiscovery()
    }

    private fun startWifiDirect() {
        if (wifiDirectManager != null)
            stopWifiDirect() // reset from previous session

        wifiDirectManager = WifiDirectManager(service)

        // This chipset potentially can't run SoftAP and WiFi Direct concurrently — make sure hotspot is off before P2P starts.
        service.serviceScope.launch {
            AppLog.i("AapService: Mode requires WiFi Direct — ensuring hotspot is disabled first...")
            HotspotManager.setHotspotEnabled(service, false)
        }

        wifiDirectManager?.setCredentialsListener { _, _, _, _ ->
            AppLog.d("AapService: WiFi credentials received, but not in Native AA mode. Skipping HandshakeManager update.")
        }
    }

    private fun stopWifiDirect() {
        wifiDirectManager?.stop()
        wifiDirectManager = null
    }

    private fun startWirelessServer(launcher: WifiLauncher) {
        if (wirelessServer != null) {
            // don't stop old server if start-properties haven't changed
            if (wirelessServer!!.registerNsd == launcher.hasLocalDiscovery())
                return

            stopWirelessServer()
        }

        // Register NSD for Headunit Server (Auto), Helper Common Wifi (NSD), and the Hotspot
        // strategies (3, 4) — both devices share an IP network there too, and the companion
        // "Wireless Helper" app's discovery relies on this service record to trigger the
        // handoff instead of just blindly probing the TCP port.
        val shouldRegisterNsd = launcher.hasLocalDiscovery()

        wirelessServer = WirelessServer(shouldRegisterNsd, service).apply { start() }
    }

    private fun stopWirelessServer() {
        if (wirelessServer == null)
            return

        wirelessServer?.stopServer()
        wirelessServer = null
        scanningState.value = false
        VpnControl.stopVpn(service)
    }

    /**
     * Starts an NSD (mDNS) scan for Android Auto Wireless services on the local network.
     *
     * @param oneShot if `true`, does not reschedule after the scan finishes —
     *                used for the "auto WiFi" reconnect case.
     */
    fun startLocalDiscovery(oneShot: Boolean = false) {
        val commManager = App.provide(service).commManager

        if (commManager.isConnected || (wirelessServer == null && !oneShot))
            return

        localDiscovery?.stop()
        scanningState.value = true

        localDiscovery = NetworkDiscovery(service, object : NetworkDiscovery.Listener {
            override fun onServiceFound(ip: String, port: Int, socket: Socket?) {
                if (commManager.isConnected) {
                    // Already connected by the time this callback fired; discard the socket
                    try { socket?.close() } catch (e: Exception) {}
                    return
                }
                when (port) {
                    5277 -> {
                        // Headunit Server detected — reuse the pre-opened socket when possible
                        AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                        service.serviceScope.launch {
                            if (socket != null && socket.isConnected)
                                commManager.connect(socket)
                            else
                                commManager.connect(ip, 5277)
                        }
                    }
                    5289 -> {
                        // WiFi Launcher detected. The wake (holding the probe socket open) already
                        // happened in NetworkDiscovery; here we just wait for the helper to launch
                        // and connect back to our WirelessServer on 5288.
                        AppLog.i("AapService: WiFi Launcher detected at $ip:$port; awaiting inbound helper connection on 5288")
                    }
                }
            }

            override fun onScanFinished() {
                scanningState.value = false
                if (oneShot) {
                    AppLog.i("One-shot scan finished.")
                    return
                }
                // Reschedule the next scan after 10 s to avoid hammering the network
                service.serviceScope.launch {
                    delay(10000)
                    if (wirelessServer != null && !commManager.isConnected) startLocalDiscovery()
                }
            }
        })
        localDiscovery?.startScan()
    }

    private fun stopLocalDiscovery() {
        if (localDiscovery == null)
            return

        localDiscovery?.stop()
        localDiscovery = null
    }
}
