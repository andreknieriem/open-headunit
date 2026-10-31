package com.andrerinas.openheadunit.connection.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.AapService.Companion.scanningState
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.HeldServerSocket
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

    var wifiDirectManager: WifiDirectManager? = null
        private set

    var wirelessServer: WirelessServer? = null
        private set
    val wirelessServerHistory = WirelessServerHistory()

    var localDiscovery: NetworkDiscovery? = null
        private set
    private var discoveryOperation: ConnectionOperation? = null
    private var directStartOwner: Any? = null
    private var serverStartOwner: Any? = null
    private var discoveryStartOwner: Any? = null

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

    /** Every shared start and discovery child carries [active]'s original [operation]. */
    fun update(active: WifiLauncher, operation: ConnectionOperation) {
        val commManager = App.provide(service).commManager
        // Take every obsolete resource before the first external stop/start. A late return from
        // that work may clean only these captured owners, never fields a newer arm published.
        val retirements = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) null else listOfNotNull(
                if (active.hasWifiDirect()) null else detachWifiDirect(false),
                if (active.hasWirelessServer()) null else detachWirelessServer(),
                if (active.hasLocalDiscovery()) null else detachLocalDiscovery(),
            )
        } ?: return
        retirements.forEach { it() }
        if (active.hasWifiDirect()) startWifiDirect(operation)
        if (active.hasWirelessServer()) startWirelessServer(active, operation)
        if (active.hasLocalDiscovery()) startLocalDiscovery(oneShot = false, operation = operation)
    }

    /** Called at lifecycle admission; the returned cleanup never reads replacement fields. */
    internal fun detachAll(keepGroup: Boolean): () -> Unit {
        val direct = detachWifiDirect(keepGroup)
        val server = detachWirelessServer()
        val discovery = detachLocalDiscovery()
        return { direct(); server(); discovery() }
    }

    private fun detachWifiDirect(keepGroup: Boolean): () -> Unit {
        directStartOwner = null
        val direct = wifiDirectManager.also { wifiDirectManager = null }
        hotspotTeardown = null
        return { direct?.stop(keepGroup); Unit }
    }

    private fun detachWirelessServer(): () -> Unit {
        serverStartOwner = null
        val server = wirelessServer.also { wirelessServer = null }
        scanningState.value = false
        return { server?.stopServer(); Unit }
    }

    private fun detachLocalDiscovery(): () -> Unit {
        discoveryStartOwner = null
        discoveryOperation = null
        val discovery = localDiscovery.also { localDiscovery = null }
        val heldSockets = HeldServerSocket.takeAll()
        service.rescanWithoutWaiting = false
        service.discoveryDormantAfterWifiLoss = false
        return {
            discovery?.stop()
            heldSockets.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun startWifiDirect(operation: ConnectionOperation) {
        val commManager = App.provide(service).commManager
        val reservation = Any()
        val cleanup = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) null else {
                val retired = detachWifiDirect(false)
                directStartOwner = reservation
                retired
            }
        } ?: return
        cleanup()
        if (!commManager.isCurrent(operation)) return
        // Framework initialization is external work; the reservation survives its callbacks.
        val candidate = WifiDirectManager(service.applicationContext, operation)
        val published = commManager.withAppConnectionLifecycleLock {
            if (directStartOwner !== reservation || !commManager.isCurrent(operation)) false else {
                wifiDirectManager = candidate
                directStartOwner = candidate
                true
            }
        }
        if (!published) { candidate.stop(); return }

        val wifiManager = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val radioBlockedOff = wifiManager != null &&
            WifiRadioSwitchPolicy.isBlockedOff(wifiManager.isWifiEnabled, Build.VERSION.SDK_INT)
        if (radioBlockedOff) {
            AppLog.i("AapService: WiFi is off and cannot be switched on by this app; leaving the hotspot alone.")
        } else {
            val teardown = service.serviceScope.launch(Dispatchers.IO) {
                val accepted = commManager.withAppConnectionLifecycleLock {
                    directStartOwner === candidate && commManager.isCurrent(operation)
                }
                if (!accepted) return@launch
                AppLog.i("AapService: Mode requires WiFi Direct — ensuring hotspot is disabled first...")
                HotspotManager.disableAndAwaitDown(service)
            }
            val kept = commManager.withAppConnectionLifecycleLock {
                if (directStartOwner !== candidate || !commManager.isCurrent(operation)) false
                else { hotspotTeardown = teardown; true }
            }
            if (!kept) teardown.cancel()
        }
        candidate.setCredentialsListener { _, _, _, _, _, _ ->
            AppLog.d("AapService: WiFi credentials received, but not in Native AA mode. Skipping HandshakeManager update.")
        }
    }

    fun startWirelessServer(launcher: WifiLauncher, operation: ConnectionOperation) {
        val commManager = App.provide(service).commManager
        val reservation = Any()
        val retired = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) return@withAppConnectionLifecycleLock null
            val existing = wirelessServer
            val action = WirelessServerRestartPolicy.decide(
                assigned = existing != null,
                alive = existing?.isAlive == true,
                listening = existing?.isListening == true,
                nowMs = SystemClock.elapsedRealtime(),
                sessionBusy = commManager.isConnected,
                history = wirelessServerHistory,
            )
            val why = WirelessServerRestartPolicy.describe(action, existing != null, existing?.isListening == true)
            when (action) {
                WirelessServerRestartPolicy.Action.NO_OP, WirelessServerRestartPolicy.Action.AWAIT -> {
                    AppLog.d("AapService: Wireless server not started - $why.")
                    return@withAppConnectionLifecycleLock null
                }
                WirelessServerRestartPolicy.Action.BACKOFF -> {
                    AppLog.i("AapService: Wireless server on 5288 is not accepting connections - $why.")
                    return@withAppConnectionLifecycleLock null
                }
                WirelessServerRestartPolicy.Action.REBUILD -> {
                    wirelessServerHistory.attempt()
                    AppLog.w("AapService: Rebuilding the wireless server on 5288 - $why (attempt ${wirelessServerHistory.rebuildsInWindow}).")
                }
                WirelessServerRestartPolicy.Action.START -> AppLog.d("AapService: Starting the wireless server on 5288 - $why.")
            }
            wirelessServer = null
            serverStartOwner = reservation
            Pair(existing, reservation)
        } ?: return
        try { retired.first?.stopServer() } catch (e: Exception) {
            AppLog.d("AapService: Error stopping the previous wireless server: ${e.message}")
        }
        if (!commManager.isCurrent(operation)) return
        val candidate = WirelessServer(launcher.hasLocalDiscovery(), service, wirelessServerHistory, operation)
        val published = commManager.withAppConnectionLifecycleLock {
            if (serverStartOwner !== reservation || !commManager.isCurrent(operation)) false else {
                wirelessServer = candidate
                serverStartOwner = candidate
                true
            }
        }
        if (!published) { candidate.stopServer(); return }
        candidate.start()
    }

    /**
     * Starts an NSD (mDNS) scan for Android Auto Wireless services on the local network.
     *
     * @param oneShot if `true`, does not reschedule after the scan finishes —
     *                used for the "auto WiFi" reconnect case.
     * @param operation the input's original ticket and Save, owned by the discovery generation.
     */
    fun startLocalDiscovery(
        oneShot: Boolean = false,
        operation: ConnectionOperation,
    ) {
        val commManager = App.provide(service).commManager
        if (!commManager.isCurrent(operation)) return

        // Logged rather than returned silently: this gate and the re-arm below are the only two
        // ways the discovery loop can end without saying so, and a loop that stops for no visible
        // reason is the one thing a submitted log cannot be read for.
        if (commManager.isBusy) {
            AppLog.i("AapService: Discovery not started — a connection is live or being set up")
            return
        }
        if (wirelessServer == null && !oneShot)
            return

        val reservation = Any()
        val reserved = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation) || commManager.isBusy || (wirelessServer == null && !oneShot)) false else {
                discoveryStartOwner = reservation
                discoveryOperation = operation
                scanningState.value = true
                true
            }
        }
        if (!reserved) return

        // [BUG_FIX] Reused rather than rebuilt. This used to stop the old instance and replace it,
        // which defeated NetworkDiscovery's own guard: the replacement's scanJob is null, so it saw
        // no scan to wait for and probed the head unit server while the discarded instance still
        // had a probe in flight. Both reach port 5277, one of them is thrown away, and the server
        // binds to the connection nobody follows through -- deaf until the user restarts it by
        // hand. Keeping the instance lets startScan() serialise, which is what it was written for.
        // A real mode change still gets a fresh instance: detachLocalDiscovery() clears it.
        val candidate = localDiscovery ?: NetworkDiscovery(
                service,
                object : NetworkDiscovery.Listener {
                    override fun onServiceFound(ip: String, port: Int, socket: Socket?, operation: ConnectionOperation) {
                        if (!commManager.isCurrent(operation) || commManager.isBusy) {
                            // Connected, or connecting, by the time this callback fired; discard the
                            // socket. isBusy rather than isConnected because handing it to connect()
                            // during a connect in flight only gets it closed one frame later.
                            // Not when a user connect adopted it: that socket is the session now.
                            if (socket != null && HeldServerSocket.adopt(socket)) {
                                try { socket.close() } catch (e: Exception) {}
                            }
                            return
                        }
                        when (port) {
                            5277 -> {
                                // Headunit Server detected — reuse the pre-opened socket when possible
                                AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                                val delivery = service.serviceScope.launch {
                                    if (!commManager.isCurrent(operation)) {
                                        if (socket != null && HeldServerSocket.adopt(socket)) {
                                            try { socket.close() } catch (_: Exception) {}
                                        }
                                        return@launch
                                    }
                                    if (socket == null) {
                                        commManager.connect(ip, 5277, ticket = operation.ticket, expectedState = operation.save)
                                    } else {
                                        // Admission adopts the held socket with the request's claim, in one
                                        // step; one a dial already adopted is that dial's, not ours.
                                        commManager.connectHeld(socket, ip to 5277, operation.ticket, expectedState = operation.save)
                                    }
                                }
                                delivery.invokeOnCompletion {
                                    // The callback owns an untaken socket even if its child never enters.
                                    if (socket != null && HeldServerSocket.adopt(socket)) {
                                        try { socket.close() } catch (_: Exception) {}
                                    }
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
                    override fun onScanFinished(wasOneShot: Boolean, operation: ConnectionOperation) {
                        val next = commManager.withAppConnectionLifecycleLock {
                            val owner = discoveryOperation
                            if (owner?.ticket != operation.ticket || owner.save !== operation.save ||
                                !commManager.isCurrent(operation)
                            ) return@withAppConnectionLifecycleLock null
                            scanningState.value = false
                            if (wasOneShot) {
                                AppLog.i("One-shot scan finished.")
                                return@withAppConnectionLifecycleLock null
                            }
                            val delayMs = if (service.rescanWithoutWaiting) {
                                service.rescanWithoutWaiting = false
                                AppLog.i("AapService: network changed during the last scan; rescanning immediately")
                                0L
                            } else UnresponsivePeerPolicy.rescanDelayMs(commManager.silentPeerFailures)
                            discoveryStartOwner to delayMs
                        } ?: return
                        service.serviceScope.launch {
                            delay(next.second)
                            val accepted = commManager.withAppConnectionLifecycleLock {
                                discoveryStartOwner === next.first && commManager.isCurrent(operation) &&
                                    wirelessServer != null && !commManager.isBusy
                            }
                            if (accepted) startLocalDiscovery(operation = operation)
                        }
                    }
                },
            )

        // Forwarded, not dropped. Both one-shot callers - the Auto-mode retry after an unclean
        // disconnect, and the scan button - were asking for a single sweep and getting the
        // self-rescheduling loop instead.
        val scanner = commManager.withAppConnectionLifecycleLock {
            if (discoveryStartOwner !== reservation || !commManager.isCurrent(operation)) null else {
                localDiscovery = candidate
                candidate
            }
        } ?: return
        scanner.startScan(oneShot, operation)
    }

}
