package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Whether this head unit's WiFi radio has a 5 GHz band at all.
 *
 * Two reporter threads argued for weeks about a band nobody could establish. One unit is 2.4 GHz
 * only and said so in a comment; another has a working 5 GHz radio and was read as if it did not.
 * Neither fact reached a log, so no capture could be sorted into the right arm.
 * `WifiManager.is5GHzBandSupported()` has answered this since API 21 and this app has never asked.
 *
 * **Only a `false` is worth acting on.** [SoftApBandPolicy][com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBandPolicy]
 * already records why: the call describes the station side, and vendors ship radios that answer yes
 * there and still refuse to host anything on it. A no means the band is not present, which is a
 * hardware fact and is safe to build on; a yes means only that the station could join one.
 */
object WifiBandCapability {

    /**
     * True, false, or null when the platform could not be asked.
     *
     * Null rather than a default, because the two answers mean opposite things to a caller and a
     * guess would be indistinguishable from a reading. Below API 21 the method does not exist, and
     * the read can throw on a unit whose WiFi service is unavailable.
     */
    fun supports5Ghz(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            wifiManager.is5GHzBandSupported
        } catch (e: Exception) {
            AppLog.d("WifiBandCapability: could not read the 5 GHz band capability: ${e.message}")
            null
        }
    }

    /**
     * Whether this platform can be asked about the band at all.
     *
     * Distinct from a null [supports5Ghz], which also means a WiFi service that refused on a unit
     * that does have the call. Below API 21 neither signal exists: the radio cannot be asked and
     * [sessionFrequencyMhz] reads 0, so a caller weighing the link has nothing to weigh.
     */
    fun bandUnreadable(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

    @Volatile private var liveGroupFrequencyMhz: Int = 0

    /** The frequency the group this unit hosts came up on; 0 when there is none or it is unreadable. */
    fun reportSessionFrequency(frequencyMhz: Int) {
        liveGroupFrequencyMhz = frequencyMhz
    }

    /**
     * The frequency the network this session runs on came up on, or 0 when nothing reported one.
     *
     * `WifiP2pGroup` carries it from API 29 only and the other transports never report one, so 0
     * means unreadable and must never be taken for 2.4 GHz.
     */
    fun sessionFrequencyMhz(): Int = liveGroupFrequencyMhz

    /**
     * The frequency this unit's own station link is on, or 0 when it is not joined to a network.
     *
     * Read for the same reason [StationStandDown][com.andrerinas.openheadunit.connection.wifi.direct.StationStandDown]
     * reads it: while a unit is associated, it is a concurrent STA+P2P device and the platform puts
     * the group on the station's channel, because the one radio cannot serve two. That makes this a
     * reading about the group even where [sessionFrequencyMhz] has none.
     *
     * Needs no Location permission: only the SSID is redacted without it, and this never asks for
     * the SSID. Returns 0 rather than throwing on a unit whose WiFi service will not answer.
     */
    fun stationFrequencyMhz(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return 0
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return 0
            wifiManager.connectionInfo?.frequency ?: 0
        } catch (e: Exception) {
            AppLog.d("WifiBandCapability: could not read the station frequency: ${e.message}")
            0
        }
    }

    /**
     * Whether this unit currently holds a station association.
     *
     * `SupplicantState.COMPLETED` rather than an SSID check, for the reason `logStationCoexistence`
     * gives: the SSID and the network id are redacted whenever the location gate is unsatisfied,
     * and that is routine on a head unit. The supplicant state is not.
     */
    fun stationAssociated(context: Context): Boolean = try {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.connectionInfo?.supplicantState == SupplicantState.COMPLETED
    } catch (e: Exception) {
        AppLog.d("WifiBandCapability: could not read the station state: ${e.message}")
        false
    }

    /** The frequency this unit's own station link is on, or 0 when it is not joined to a network. */
    fun stationFrequencyMhz(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return 0
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return 0
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.frequency ?: 0
        } catch (e: Exception) {
            AppLog.d("WifiBandCapability: could not read the station frequency: ${e.message}")
            0
        }
    }

    /** Whether the station association is complete and can be used as a group-channel proxy. */
    fun stationAssociated(context: Context): Boolean = try {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.connectionInfo?.supplicantState == SupplicantState.COMPLETED
    } catch (e: Exception) {
        AppLog.d("WifiBandCapability: could not read the station state: ${e.message}")
        false
    }

    /** How the answer reads in a log line a reporter pastes into an issue. */
    fun describe(supports5Ghz: Boolean?): String = when (supports5Ghz) {
        true -> "this unit's WiFi radio reports a 5 GHz band"
        false -> "this unit's WiFi radio has no 5 GHz band, so every wireless route here runs on 2.4 GHz"
        null -> "this unit will not say whether it has a 5 GHz band (below Android 5.0, or the " +
            "WiFi service would not answer), so nothing here is decided on the band"
    }
}
