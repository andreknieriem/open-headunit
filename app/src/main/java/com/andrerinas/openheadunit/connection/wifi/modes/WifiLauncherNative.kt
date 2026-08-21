package com.andrerinas.openheadunit.connection.wifi.modes

import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.direct.WifiDirectManager
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeAaHandshakeManager
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApCredentialsProvider
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncher
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherStopSequence
import com.andrerinas.openheadunit.utils.AppLog

class WifiLauncherNative : WifiLauncher {

    val strategy: NativeStrategy

    var handshakeManager: NativeAaHandshakeManager? = null
        private set
    private var softApCredentialsProvider: SoftApCredentialsProvider? = null

    constructor(manager: WifiLauncherManager) : super(manager) {
        // copy settings early in construction to align with #hasSameStartConfiguration
        this.strategy = settings.nativeApStrategy
    }

    constructor(manager: WifiLauncherManager, strategy: NativeStrategy) : super(manager) {
        this.strategy = strategy
    }

    override val mode = WifiLauncherMode.NATIVE

    override fun hasSameStartConfiguration(launcher: WifiLauncher) = launcher is WifiLauncherNative && launcher.strategy == strategy

    override fun hasWifiDirect() = true

    override fun hasWirelessServer() = strategy == NativeStrategy.WIFI_DIRECT

    override fun hasLocalDiscovery() = false

    override fun start(noInfoToasts: Boolean) {
        val wifiDirect = manager.sharedServices.wifiDirectManager!!

        handshakeManager = NativeAaHandshakeManager(service, this, service.serviceScope)
        softApCredentialsProvider = SoftApCredentialsProvider(service, service.serviceScope, settings)

        // Skip the whole route, not just the handshake, when the Bluetooth this unit's
        // phone is bonded to isn't reachable from here: with no Bluetooth channel there is
        // nobody to hand the credentials to, so hosting a P2P group or holding the hotspot
        // open would only churn the WiFi stack for nothing.
        val externalBt = NativeAaHandshakeManager.externalBtDiagnostic()
        if (externalBt != null) AppLog.e(externalBt)
        val blockedByExternalBt =
            externalBt != null && !NativeAaHandshakeManager.externalBtOverridden(service)

        if (!blockedByExternalBt) {
            if (this.strategy == NativeStrategy.HOTSPOT) {
                // Read this device's own access point instead of hosting a P2P group. The AP
                // itself is the user's to switch on; the provider only resolves and watches it.
                AppLog.i("AapService: Native AA on the head unit hotspot — resolving access point credentials.")
                softApCredentialsProvider?.start()
            } else {
                // Start WiFi Direct as a "quiet host" (P2P Group for phone to join)
                // We let WifiDirectManager handle the WiFi state (enabling if needed)
                setupWifiDirect(wifiDirect)
                wifiDirect.startNativeAaQuietHost()
            }

            // Start the official Bluetooth handshake servers
            handshakeManager?.start()
        }
    }

    override fun stop(seq: WifiLauncherStopSequence) {
        // Before the hotspot goes, not after: SoftApCredentialsProvider watches
        // WIFI_AP_STATE_CHANGED and switches an access point it started back on when it sees one
        // drop. Left registered here it would treat this very teardown as the hotspot failing and
        // bring it back up as the service dies — leaving the access point running with nothing
        // left to serve it.
        if (seq.handledAt(WifiLauncherStopSequence.BEFORE_HOTSPOT_DISABLE))
            softApCredentialsProvider?.stop()

        if (seq.handledAt(WifiLauncherStopSequence.LAST))
            handshakeManager?.stop()
    }

    private fun setupWifiDirect(wifiDirectManager: WifiDirectManager) {
        val commManager = App.provide(service).commManager

        wifiDirectManager.setCredentialsListener { ssid, psk, ip, bssid ->
            onNativeCredentials(ssid, psk, ip, bssid)
        }

        // Settling counts as in-flight here: isHandshakeInFlight() goes false the instant Type 3
        // is written, but the phone still has to associate, do WPS and get a DHCP lease, and
        // recreating the group in that window hands it an SSID it can no longer join.
        wifiDirectManager.setNativeHandshakeStateProvider {
            handshakeManager?.isHandshakeInFlight() == true ||
            handshakeManager?.isHandoffSettling() == true
        }
        wifiDirectManager.setNativeSessionConnectedProvider { commManager.isConnected }
        wifiDirectManager.setNativeGroupInvalidatedListener { handshakeManager?.invalidateCredentials() }
        softApCredentialsProvider?.setCredentialsListener { ssid, psk, ip, bssid ->
            onNativeCredentials(ssid, psk, ip, bssid)
        }
        softApCredentialsProvider?.setInvalidatedListener { handshakeManager?.invalidateCredentials() }
    }

    /**
     * Triggers a refresh of the WiFi Direct "quiet host" state.
     * Called by NativeAaHandshakeManager if it's waiting for credentials that haven't arrived yet.
     */
    fun triggerWifiDirectRefresh() {
        if (this.strategy == NativeStrategy.HOTSPOT) {
            AppLog.i("AapService: Access point refresh requested.")
            softApCredentialsProvider?.refresh()

        } else {
            AppLog.i("AapService: WiFi Direct refresh requested.")
            manager.sharedServices.wifiDirectManager?.startNativeAaQuietHost()
        }
    }

    /**
     * Credentials for the network the phone should join, from whichever transport produced them.
     * Both mode-3 transports funnel through here so the poke rules stay in one place.
     */
    private fun onNativeCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        val commManager = App.provide(service).commManager

        if (settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
            AppLog.d("AapService: WiFi credentials received, but not in Native AA mode. Skipping HandshakeManager update.")
            return
        }

        AppLog.i("AapService: Received WiFi credentials from manager (SSID=$ssid, IP=$ip). Updating and Triggering Poke.")
        handshakeManager?.updateWifiCredentials(ssid, psk, ip, bssid)

        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
            AppLog.i("AapService: USB/other session already active. Skipping auto-poke to avoid pulling phone into wireless flow.")
        } else if (!service.userExitedAA) {
            handshakeManager?.triggerPoke()
        } else {
            AppLog.i("AapService: userExitedAA is true. Skipping auto-poke.")
        }
    }

    /**
     * Whether the AAP TCP port the phone will be sent to is bound and accepting.
     *
     * The Bluetooth handshake checks this before handing over credentials, mirroring the ordering
     * the reference head unit software uses: access point up, address resolved, port bound, and
     * only then talk to the phone.
     */
    fun isWirelessServerListening(): Boolean = manager.sharedServices.wirelessServer?.isListening == true
}
