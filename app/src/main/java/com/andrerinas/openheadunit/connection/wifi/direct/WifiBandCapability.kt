package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.aap.NarrowBandProfilePolicy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HotspotBandPreference
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

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
     * [sessionFrequencyMhz] reads 0, so a caller weighing the link has nothing to weigh. Also true
     * while the local-only hotspot runs on a band that holds 2.4 GHz or could not be read.
     */
    fun bandUnreadable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        platformCannotAsk(sdkInt) || localHotspotNarrow

    /** Below API 21 the platform has no call for the band. The log header reads this, not [bandUnreadable]. */
    fun platformCannotAsk(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.LOLLIPOP

    @Volatile private var localHotspotNarrow = false

    /** Whether the local-only hotspot set the cap, for the advice text. */
    fun isLocalHotspotNarrow(): Boolean = localHotspotNarrow

    /**
     * Whether the local-only hotspot forces the video cap. While set, [bandUnreadable] is true;
     * [sessionFrequencyMhz] stays 0 either way, so no channel list is sent.
     */
    fun reportLocalHotspotNarrow(narrow: Boolean) {
        localHotspotNarrow = narrow
    }

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

    /** Keeps the one fact a later `false` from [supports5Ghz] cannot undo: this radio ran above 4 GHz. */
    fun noteFrequency(settings: Settings, frequencyMhz: Int) {
        if (frequencyMhz <= NarrowBandProfilePolicy.MAX_24GHZ_FREQUENCY_MHZ || settings.radioSeenAbove24Ghz) return
        settings.radioSeenAbove24Ghz = true
        AppLog.i("WifiBandCapability: this unit's WiFi radio was seen on $frequencyMhz MHz")
    }

    /** [noteFrequency] for the network this unit is joined to, if any. */
    fun noteStationFrequency(context: Context, settings: Settings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || settings.radioSeenAbove24Ghz) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            noteFrequency(settings, wifiManager?.connectionInfo?.frequency ?: 0)
        } catch (e: Exception) {
            AppLog.d("WifiBandCapability: could not read the station's frequency: ${e.message}")
        }
    }

    /**
     * [supports5Ghz] as the video cap should read it. On the Native Hotspot transport a `false` is
     * dropped when this radio has run on 5 GHz, or when the user set the system hotspot to 5 GHz only:
     * an app cannot read that hotspot's band, and a unit that hosts 5 GHz can still answer `false`.
     * With the system hotspot on 2.4 GHz only the `false` stands.
     */
    fun radioAnswerForCap(context: Context, settings: Settings): Boolean? {
        val radio = supports5Ghz(context)
        if (radio != false) return radio
        noteStationFrequency(context, settings)
        return NarrowBandProfilePolicy.radioAnswer(
            supports5Ghz = radio,
            nativeHotspot = settings.wifiConnectionMode == WifiLauncherMode.NATIVE &&
                settings.nativeApStrategy == NativeStrategy.HOTSPOT,
            seenAbove24Ghz = settings.radioSeenAbove24Ghz,
            systemHotspotBand = HotspotBandPreference.fromSetting(settings.hotspotBand).takeIf {
                LocalHotspotPolicy.source(Build.VERSION.SDK_INT, settings.useSystemHotspot) == LocalHotspotPolicy.Source.SYSTEM
            },
        )
    }

    /** How the answer reads in a log line a reporter pastes into an issue. */
    fun describe(supports5Ghz: Boolean?): String = when (supports5Ghz) {
        true -> "this unit's WiFi radio reports a 5 GHz band"
        false -> "this unit's WiFi radio has no 5 GHz band, so every wireless route here runs on 2.4 GHz"
        null -> "this unit will not say whether it has a 5 GHz band (below Android 5.0, or the " +
            "WiFi service would not answer), so nothing here is decided on the band"
    }
}
