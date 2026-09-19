package com.andrerinas.openheadunit.connection.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.AapService.Companion.scanningState
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.UnresponsivePeerPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.WifiDirectManager
import com.andrerinas.openheadunit.connection.wifi.direct.WifiRadioSwitchPolicy
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServer
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerHistory
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerRestartPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotManager
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WifiLauncherSharedServices(val service: AapService) {
    private var activeLauncher: WifiLauncher? = null
    private var discoveryUsesP2p: Boolean? = null
    private var discoveryEpoch = 0

    var wifiDirectManager: WifiDirectManager? = null
        private set

    var wirelessServer: WirelessServer? = null
        private set
    val wirelessServerHistory = WirelessServerHistory()

    var localDiscovery: NetworkDiscovery? = null
        private set

    /**
     * The hotspot teardown a WiFi Direct bring-up has to run *behind*, or null when none is pending.
     *
     * Posted to the main looper and never waited for, this ran after the create it was meant to
     * clear the radio for: on a single-radio unit the group was asked for, and WiFi asked to come
     * on, while the access point still held the chip. Callers join it instead.
     */
    @Volatile
    var hotspotTeardown: Job? = null
        private set

    fun update(active: WifiLauncher) {
        activeLauncher = active
        val serverP2p = App.provide(service).settings.usesServerWifiDirect()
        if (discoveryUsesP2p != null && discoveryUsesP2p != serverP2p) stopLocalDiscovery()
        if (active.hasWifiDirect()) startWifiDirect() else stopWifiDirect()
        if (active.hasWirelessServer()) startWirelessServer(active) else stopWirelessServer()
        if (active.hasLocalDiscovery()) startLocalDiscovery(oneShot = false) else stopLocalDiscovery()
    }

    fun stopAll() {
        activeLauncher = null
        stopWifiDirect()
        stopWirelessServer()
        stopLocalDiscovery()
    }

    private fun startWifiDirect() {
        if (wifiDirectManager != null)
            stopWifiDirect() // reset from previous session

        wifiDirectManager = WifiDirectManager(service)
        wifiDirectManager?.visibleBringUpAllowed = {
            !App.provide(service).settings.usesServerWifiDirect() || hotspotTeardown?.isCompleted != false
        }
        wifiDirectManager?.discoveryNetworkListener = { network ->
            if (network?.hasClient == true && App.provide(service).settings.usesServerWifiDirect()) {
                service.discoveryDormantAfterWifiLoss = false
                service.rescanWithoutWaiting = true
                startLocalDiscovery()
            }
        }

        // This chipset potentially can't run SoftAP and WiFi Direct concurrently — make sure hotspot
        // is off before P2P starts. On IO because the wait for it to actually go is a blocking one.
        // Skipped when no group can ever form: freeing the radio for one buys nothing, and on a
        // single-radio unit it takes down the only network that was working.
        val wifiManager =
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val radioBlockedOff = wifiManager != null &&
            WifiRadioSwitchPolicy.isBlockedOff(wifiManager.isWifiEnabled, Build.VERSION.SDK_INT)
        if (radioBlockedOff && !App.provide(service).settings.usesServerWifiDirect()) {
            AppLog.i(
                "AapService: WiFi is off and this Android does not let an app switch it on, so no " +
                    "WiFi Direct group can be created — leaving this unit's hotspot alone."
            )
            hotspotTeardown = null
        } else {
            hotspotTeardown = service.serviceScope.launch(Dispatchers.IO) {
                AppLog.i("AapService: Mode requires WiFi Direct — ensuring hotspot is disabled first...")
                HotspotManager.disableAndAwaitDown(service)
            }
        }

        wifiDirectManager?.setCredentialsListener { _, _, _, _, _ ->
            AppLog.d("AapService: WiFi credentials received, but not in Native AA mode. Skipping HandshakeManager update.")
        }
    }

    private fun stopWifiDirect() {
        wifiDirectManager?.stop()
        wifiDirectManager = null
        // The teardown itself is left to finish - the access point should stay down for whatever
        // comes next - but nothing may wait on a job belonging to a mode that has gone.
        hotspotTeardown = null
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
        // No VpnControl.stopVpn() here. The dummy VPN belongs to Self Mode or to the session that
        // asked for it, never to the wireless server, and update() runs this on every mode change
        // - which is what took a user's VPN down three milliseconds after it came up. The owning
        // teardowns call AapService.stopDummyVpn(); see DummyVpnPolicy.
    }

    /**
     * Starts an NSD (mDNS) scan for Android Auto Wireless services on the local network.
     *
     * @param oneShot if `true`, does not reschedule after the scan finishes —
     *                used for the "auto WiFi" reconnect case.
     */
    fun startLocalDiscovery(oneShot: Boolean = false) {
        val commManager = App.provide(service).commManager
        if (service.wirelessCancelledByUser()) return
        val serverP2p = App.provide(service).settings.usesServerWifiDirect()
        if (serverP2p && activeLauncher?.hasLocalDiscovery() != true) return
        if (serverP2p && wifiDirectManager?.discoveryNetwork?.hasClient != true) return

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
            discoveryUsesP2p = serverP2p
            val epoch = ++discoveryEpoch
            localDiscovery = NetworkDiscovery(
                service,
                object : NetworkDiscovery.Listener {
                    override fun onServiceFound(ip: String, port: Int, socket: Socket?) {
                        if (commManager.isBusy || epoch != discoveryEpoch || service.wirelessCancelledByUser()) {
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
                                if (serverP2p && (socket == null || !socket.isConnected)) {
                                    runCatching { socket?.close() }
                                    return
                                }
                                // Headunit Server detected — reuse the pre-opened socket when possible
                                AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                                service.serviceScope.launch {
                                    if (epoch != discoveryEpoch || service.wirelessCancelledByUser() || commManager.isBusy) {
                                        runCatching { socket?.close() }
                                        return@launch
                                    }
                                    if (socket != null && socket.isConnected)
                                        commManager.connect(socket, serverP2p = serverP2p)
                                    else
                                        commManager.connect(ip, 5277)
                                }
                            }

                            5289 -> {
                                // WiFi Launcher detected. The wake (holding the probe socket open) already
                                // happened in NetworkDiscovery; here we just wait for the helper to launch
                                // and connect back to our WirelessServer on 5288.
                                AppLog.i("AapService: WiFi Launcher detected at $ip:$port; awaiting inbound helper connection on 5288")
                                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                            }
                        }
                    }

                    // The flag comes from the scan that finished, not from the call that built this
                    // listener: the instance outlives any single request now, so capturing it here would
                    // pin every later scan to the first caller's choice.
                    override fun onScanFinished(wasOneShot: Boolean) {
                        if (epoch != discoveryEpoch) return
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
                            if (epoch != discoveryEpoch) return@launch
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
                p2pNetwork = if (serverP2p) ({ wifiDirectManager?.discoveryNetwork }) else null,
            )
        }

        // Forwarded, not dropped. Both one-shot callers - the Auto-mode retry after an unclean
        // disconnect, and the scan button - were asking for a single sweep and getting the
        // self-rescheduling loop instead.
        localDiscovery?.startScan(oneShot)
    }

    private fun stopLocalDiscovery() {
        discoveryEpoch++
        discoveryUsesP2p = null
        if (localDiscovery == null)
            return

        service.rescanWithoutWaiting = false
        service.discoveryDormantAfterWifiLoss = false
        localDiscovery?.stop()
        localDiscovery = null
    }
}
