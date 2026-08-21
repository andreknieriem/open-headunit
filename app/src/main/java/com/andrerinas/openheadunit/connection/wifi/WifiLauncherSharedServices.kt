package com.andrerinas.openheadunit.connection.wifi

import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.AapService.Companion.scanningState
import com.andrerinas.openheadunit.aap.UnresponsivePeerPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.WifiDirectManager
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServer
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerHistory
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerRestartPolicy
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
    val wirelessServerHistory = WirelessServerHistory()

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

    fun startWirelessServer(launcher: WifiLauncher) {
        val commManager = App.provide(service).commManager
        val existing = wirelessServer
        val action = WirelessServerRestartPolicy.decide(
            assigned = existing != null,
            alive = existing?.isAlive == true,
            listening = existing?.isListening == true,
            nowMs = SystemClock.elapsedRealtime(),
            sessionBusy = commManager.isConnected,
            history = wirelessServerHistory
        )
        val why = WirelessServerRestartPolicy.describe(
            action,
            existing != null,
            existing?.isListening == true,
        )
        when (action) {
            WirelessServerRestartPolicy.Action.NO_OP,
            WirelessServerRestartPolicy.Action.AWAIT,
                -> {
                AppLog.d("AapService: Wireless server not started - $why.")
                return
            }

            WirelessServerRestartPolicy.Action.BACKOFF -> {
                // INFO, not DEBUG. This is the state a stuck unit sits in, and the reporter logs
                // that would have identified it are captured at INFO.
                AppLog.i("AapService: Wireless server on 5288 is not accepting connections - $why.")
                return
            }

            WirelessServerRestartPolicy.Action.REBUILD -> {
                wirelessServerHistory.attempt()
                AppLog.w("AapService: Rebuilding the wireless server on 5288 - $why (attempt ${wirelessServerHistory.rebuildsInWindow}).")
                // Only this object, never stopWirelessServer(): that also clears activeWifiMode and
                // activeHelperStrategy, and the mode has not changed - we are repairing inside it.
                try {
                    existing?.stopServer()
                } catch (e: Exception) {
                    AppLog.d("AapService: Error stopping the previous wireless server: ${e.message}")
                }
                wirelessServer = null
            }

            WirelessServerRestartPolicy.Action.START -> {
                AppLog.d("AapService: Starting the wireless server on 5288 - $why.")
            }
        }

        // Register NSD for Headunit Server (Auto), Helper Common Wifi (NSD), and the Hotspot
        // strategies (3, 4) — both devices share an IP network there too, and the companion
        // "Wireless Helper" app's discovery relies on this service record to trigger the
        // handoff instead of just blindly probing the TCP port.
        val shouldRegisterNsd = launcher.hasLocalDiscovery()

        wirelessServer = WirelessServer(
            shouldRegisterNsd,
            service,
            wirelessServerHistory,
        ).apply { start() }
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

        // Logged rather than returned silently: this gate and the re-arm below are the only two
        // ways the discovery loop can end without saying so, and a loop that stops for no visible
        // reason is the one thing a submitted log cannot be read for.
        if (commManager.isBusy) {
            AppLog.i("AapService: Discovery not started — a connection is live or being set up")
            return
        }
        if (wirelessServer == null && !oneShot)
            return

        scanningState.value = true

        // [BUG_FIX] Reused rather than rebuilt. This used to stop the old instance and replace it,
        // which defeated NetworkDiscovery's own guard: the replacement's scanJob is null, so it saw
        // no scan to wait for and probed the head unit server while the discarded instance still
        // had a probe in flight. Both reach port 5277, one of them is thrown away, and the server
        // binds to the connection nobody follows through -- deaf until the user restarts it by
        // hand. Keeping the instance lets startScan() serialise, which is what it was written for.
        // A real mode change still gets a fresh instance: stopWirelessServer() nulls this.
        if (localDiscovery == null) {
            localDiscovery = NetworkDiscovery(
                service,
                object : NetworkDiscovery.Listener {
                    override fun onServiceFound(ip: String, port: Int, socket: Socket?) {
                        if (commManager.isBusy) {
                            // Connected, or connecting, by the time this callback fired; discard the
                            // socket. isBusy rather than isConnected because handing it to connect()
                            // during a connect in flight only gets it closed one frame later.
                            try {
                                socket?.close()
                            } catch (e: Exception) {
                            }
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

                    // The flag comes from the scan that finished, not from the call that built this
                    // listener: the instance outlives any single request now, so capturing it here would
                    // pin every later scan to the first caller's choice.
                    override fun onScanFinished(wasOneShot: Boolean) {
                        scanningState.value = false
                        if (wasOneShot) {
                            AppLog.i("One-shot scan finished.")
                            return
                        }

                        // Reschedule the next scan to avoid hammering the network — and slow right down
                        // when the peer we keep reaching accepts the connection and never answers, which
                        // no amount of retrying fixes and which costs it a stranded socket each time.
                        //
                        // Unless the network changed while this sweep was running. Joining the phone's
                        // network has to start a scan promptly — waiting out the loop is most of a
                        // minute at the moment the user is starting a drive — and this is how that is
                        // done safely. The kick must never cancel a live probe to get there: two sweeps
                        // probing the head unit server at once is what wedges it, so the kick only makes
                        // the *next* sweep immediate, on the network that has actually arrived.
                        val delayMs = if (service.rescanWithoutWaiting) {
                            service.rescanWithoutWaiting = false
                            AppLog.i("AapService: network changed during the last scan; rescanning immediately")
                            0L
                        } else {
                            UnresponsivePeerPolicy.rescanDelayMs(commManager.silentPeerFailures)
                        }

                        service.serviceScope.launch {
                            delay(delayMs)
                            if (wirelessServer == null) {
                                AppLog.i("AapService: Discovery loop ends — the wireless server is gone")
                            } else if (commManager.isBusy) {
                                AppLog.i("AapService: Discovery loop ends — a connection is live or being set up")
                            } else {
                                startLocalDiscovery()
                            }
                        }
                    }
                },
            )
        }

        localDiscovery?.startScan()
    }

    private fun stopLocalDiscovery() {
        if (localDiscovery == null)
            return

        service.rescanWithoutWaiting = false
        service.discoveryDormantAfterWifiLoss = false
        localDiscovery?.stop()
        localDiscovery = null
    }
}
