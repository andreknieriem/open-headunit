package com.andrerinas.openheadunit.connection

import android.content.Context
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.andrerinas.openheadunit.aap.ApInterfaceCandidate
import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.aap.PreflightProbe
import com.andrerinas.openheadunit.aap.SoftApBssidPolicy
import com.andrerinas.openheadunit.aap.SoftApCredentials
import com.andrerinas.openheadunit.aap.SoftApNetworkPolicy
import com.andrerinas.openheadunit.aap.SoftApState
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotConfigReader
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import com.andrerinas.openheadunit.utils.NetworkAddresses
import com.andrerinas.openheadunit.utils.SoftApStateReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds out what this head unit can tell a phone about its own network, without putting one on air.
 *
 * Gathering only. Every judgement is
 * [com.andrerinas.openheadunit.aap.NativeCredentialsPreflightPolicy]'s, so that the rules are
 * testable off-device and this file stays a list of reads.
 *
 * The reads are chosen for one property: a negative answer here has to mean the same thing it would
 * mean during a handshake. That holds for the hotspot name and passphrase, because
 * [HotspotConfigReader] goes through `getSoftApConfiguration()`, which reads the *stored*
 * configuration - a device that refuses it now refuses it with the access point up too. It does not
 * hold for a MAC, which has to be read off an interface that exists, so that answer is reported as
 * inconclusive rather than negative whenever there was nothing to read from.
 */
object NativeCredentialsPreflight {

    /** How long to wait on the framework's own answer for the P2P device address. */
    private const val DEVICE_INFO_TIMEOUT_MS = 1_500L

    /**
     * Blocking reads and a `Looper` callback, so never on the caller's thread.
     *
     * The overrides are passed in rather than read from `Settings`, because the caller is a settings
     * screen holding values the user has typed but not yet saved. Checking the stored copy would ask
     * for a name they had just entered.
     */
    suspend fun probe(
        context: Context,
        transport: NativeTransport,
        manualSsid: String,
        manualPassword: String,
        staticBssid: String?,
        hotspotInterface: String
    ): PreflightProbe = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // Read only when it could matter. On the WiFi Direct route the answer is unused, and once a
        // manual name is set SoftApCredentialsPolicy.resolve never consults it either - so asking
        // would cost a reflective call whose answer is already outranked.
        val systemConfig = if (transport == NativeTransport.HOTSPOT && manualSsid.isEmpty()) {
            HotspotConfigReader.getSystemHotspotConfig(app)?.let { SoftApCredentials(it.first, it.second) }
        } else null

        val bssid = when (transport) {
            NativeTransport.HOTSPOT -> probeHotspotBssid(app, hotspotInterface)
            NativeTransport.WIFI_DIRECT -> probeWifiDirectBssid(app)
        }

        PreflightProbe(
            manualSsid = manualSsid,
            manualPassword = manualPassword,
            staticBssid = staticBssid,
            systemConfig = systemConfig,
            probedBssid = bssid.address,
            bssidProbeConclusive = bssid.conclusive,
            locationServicesEnabled = locationServicesEnabled(app)
        )
    }

    /**
     * A MAC, and whether the sources tried are the ones that would decide it at handshake time.
     *
     * [conclusive] false means there was nothing to read from yet - not that this device cannot say.
     */
    private data class BssidProbe(val address: String?, val conclusive: Boolean)

    /**
     * The access point's own MAC, read exactly the way `SoftApCredentialsProvider` will read it.
     *
     * Only answerable while an access point is up: the address lives on an interface that does not
     * exist until then. With the hotspot off this is inconclusive, which is the honest answer and
     * the reason the name and passphrase are checked separately - those *are* answerable either way,
     * and they are the ones that strand people.
     */
    private fun probeHotspotBssid(context: Context, hotspotInterface: String): BssidProbe {
        val named = hotspotInterface.trim()
        val candidates = interfaceCandidates() ?: return BssidProbe(null, conclusive = false)

        val iface = candidates.firstOrNull { named.isNotEmpty() && it.name.equals(named, ignoreCase = true) }
            ?: SoftApNetworkPolicy.pickApInterface(candidates, NetworkAddresses.stationIpv4(context))
            ?: return BssidProbe(null, conclusive = false)

        // The same trap SoftApCredentialsProvider's publish() guards against, and it matters more
        // here: with the access point down, a cellular bridge is the sort of interface that gets
        // picked, and reading its MAC would report an answer about the wrong network. A named
        // interface is exempt for the reason NativeCredentialsPolicy gives - naming one is a claim
        // about which network is up - and UNKNOWN never refuses, because that read is blocked
        // outright on some devices.
        if (named.isEmpty() && SoftApStateReader.read(context) == SoftApState.NOT_ENABLED) {
            return BssidProbe(null, conclusive = false)
        }

        return BssidProbe(
            address = SoftApBssidPolicy.choose(
                staticOverride = null,
                shellMac = InterfaceMacReader.read(iface.name),
                hardwareAddress = hardwareAddressOf(iface.name)
            ).ifEmpty { null },
            conclusive = true
        )
    }

    /**
     * The P2P device address, from the sources in `WifiDirectManager`'s fallback chain that do not
     * need a group.
     *
     * Three of its six do: `Settings.Secure`, the sysfs sweep, and `requestDeviceInfo`. The three
     * left out all read a live `WifiP2pGroup`. So a hit here is real, and a miss is only ever
     * reported as inconclusive - a group might still produce one, and a MAC typed to answer a
     * question this could not ask would then outrank every automatic source for good.
     */
    private suspend fun probeWifiDirectBssid(context: Context): BssidProbe {
        secureP2pDeviceAddress(context)?.let { return BssidProbe(it, conclusive = true) }
        sysfsP2pAddress()?.let { return BssidProbe(it, conclusive = true) }
        deviceInfoAddress(context)?.let { return BssidProbe(it, conclusive = true) }
        return BssidProbe(null, conclusive = false)
    }

    /** Fallback 5's source. A Samsung and Pixel trick, and free to ask. */
    private fun secureP2pDeviceAddress(context: Context): String? = try {
        android.provider.Settings.Secure
            .getString(context.contentResolver, "wifi_p2p_device_address")
            ?.takeIf { SoftApBssidPolicy.isUsable(it) }
    } catch (e: Exception) {
        AppLog.d("NativeCredentialsPreflight: Settings.Secure p2p address unavailable: ${e.message}")
        null
    }

    /**
     * Fallback 4's last resort: any `p2p*` interface sysfs still lists.
     *
     * Limited to `p2p`-prefixed names on purpose. The chain's version sweeps every interface because
     * by then it is choosing between a masked address and none at all; here a wrong answer would be
     * recorded as "this unit can supply its own MAC" and suppress the prompt for good, so anything
     * that is not certainly the P2P interface is left to the inconclusive path.
     */
    private fun sysfsP2pAddress(): String? = try {
        File("/sys/class/net").listFiles()
            ?.filter { it.name.startsWith("p2p") }
            ?.firstNotNullOfOrNull { dir ->
                InterfaceMacReader.fromSysfs(dir.name)?.takeIf { SoftApBssidPolicy.isUsable(it) }
            }
    } catch (e: Exception) {
        AppLog.d("NativeCredentialsPreflight: sysfs sweep for a p2p interface failed: ${e.message}")
        null
    }

    /**
     * Fallback 2's source, asked directly.
     *
     * Initialises a channel of our own rather than borrowing `WifiDirectManager`'s: this runs from
     * Settings, where that manager may not be started, and asking for a device address creates no
     * group and joins nothing. The channel is closed again on the API levels that allow it.
     */
    private suspend fun deviceInfoAddress(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = try {
            context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        } catch (e: Exception) {
            AppLog.d("NativeCredentialsPreflight: no WiFi P2P service: ${e.message}")
            null
        } ?: return null

        var channel: WifiP2pManager.Channel? = null
        return try {
            channel = manager.initialize(context, Looper.getMainLooper(), null) ?: return null
            val answer = CompletableDeferred<String?>()
            WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                answer.complete(address.takeIf { SoftApBssidPolicy.isUsable(it) })
            }
            // The callback is not guaranteed to fire at all - on a device that masks the address it
            // may simply never answer - so the wait is bounded rather than awaited.
            withTimeoutOrNull(DEVICE_INFO_TIMEOUT_MS) { answer.await() }
        } catch (e: CancellationException) {
            // Ahead of the catch below, which would otherwise swallow it: await() raises this when
            // the caller's scope is cancelled, and it is an ordinary Exception, so catching it and
            // returning null would leave the rest of the probe running after the caller had gone.
            throw e
        } catch (e: Exception) {
            AppLog.d("NativeCredentialsPreflight: requestDeviceInfo probe failed: ${e.message}")
            null
        } finally {
            // Channel became AutoCloseable in 27; before that it is released with the process.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try { channel?.close() } catch (e: Exception) {}
            }
        }
    }

    /**
     * Whether either location provider is on, or null where the question could not be put.
     *
     * Null and false are kept apart deliberately, the same way [com.andrerinas.openheadunit.aap.SoftApState]
     * keeps them apart: telling somebody their location is off when we could not find out is how a
     * check gets dismissed on the unit that needed it.
     */
    private fun locationServicesEnabled(context: Context): Boolean? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        AppLog.d("NativeCredentialsPreflight: could not read the location services state: ${e.message}")
        null
    }

    /** Null rather than an empty list when the interfaces cannot be enumerated at all. */
    private fun interfaceCandidates(): List<ApInterfaceCandidate>? = try {
        NetworkInterface.getNetworkInterfaces().toList().map { nif ->
            ApInterfaceCandidate(
                name = nif.name,
                isLoopback = try { nif.isLoopback } catch (e: Exception) { false },
                isUp = try { nif.isUp } catch (e: Exception) { false },
                siteLocalIpv4 = nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            )
        }
    } catch (e: Exception) {
        AppLog.w("NativeCredentialsPreflight: could not enumerate network interfaces: ${e.message}")
        null
    }

    private fun hardwareAddressOf(name: String): String? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
            ?.joinToString(":") { String.format("%02x", it) }
    } catch (e: Exception) {
        null
    }
}
