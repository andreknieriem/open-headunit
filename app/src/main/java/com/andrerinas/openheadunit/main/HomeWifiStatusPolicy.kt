package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.wifi.HeadunitServerNetwork
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode

/**
 * The home's side of Headunit Server on the phone's hotspot ([HeadunitServerNetwork.PHONE_HOTSPOT]):
 * the WiFi line next to the main card's icon, what the main button does while this unit is on no
 * WiFi network, and the optional scans the home makes by itself. Nothing here applies to any
 * other mode or network; the main button keeps doing there what it always did.
 *
 * Android 10 units can sit a minute with a saved hotspot in range, because the platform's own
 * reconnect scans back off while nothing is connected. One scan asked for by hand ends that wait,
 * and once the unit joins, the Headunit Server search finds the phone by itself.
 *
 * Pure, so every case is a unit test rather than a head unit.
 */
object HomeWifiStatusPolicy {

    /** What WifiManager and ConnectivityManager say, read by [HomeWifiStatus]. */
    data class Facts(
        val wifiEnabled: Boolean,
        val connected: Boolean,
        /** As WifiInfo gives it, quotes and `<unknown ssid>` included. */
        val rawSsid: String? = null,
        val frequencyMhz: Int? = null,
        val rssiDbm: Int? = null,
    )

    enum class Kind { HIDDEN, OFF, NOT_CONNECTED, CONNECTED }

    enum class Band { GHZ_2_4, GHZ_5, GHZ_6 }

    data class Line(
        val kind: Kind,
        /** Null when Android withholds the name: it does without location access. */
        val ssid: String? = null,
        val band: Band? = null,
        val signalPercent: Int? = null,
    ) {
        /** Lit bars out of four; zero without a reading. */
        val bars: Int get() = barsFor(signalPercent)

        /** Not on WiFi: nothing can connect until the unit joins the hotspot. */
        val needsAttention: Boolean get() = kind == Kind.OFF || kind == Kind.NOT_CONNECTED
    }

    /** What the main button does on the phone's hotspot. */
    enum class ConnectAction {
        /** What it always did: search for the phone. */
        CONNECT,
        /** Ask for a WiFi scan, and search once the unit has joined the hotspot. */
        SCAN_AND_WAIT,
        /** Ask for WiFi to be turned on, and search once the unit has joined the hotspot. */
        TURN_WIFI_ON_AND_WAIT,
    }

    /** How long the main button waits for the unit to join the hotspot before it says so. */
    const val JOIN_WAIT_MS = 60_000L

    /**
     * Between two scans of the home's own. From Android 9 a foreground app gets four scans in two
     * minutes and the platform drops the rest, so this is the shortest pace that is never refused.
     */
    const val RESCAN_MS = 35_000L

    /** The automatic scans slow down after this long without the hotspot: it is most likely off. */
    const val AUTO_SCAN_BRISK_MS = 120_000L
    const val AUTO_SCAN_SLOW_MS = 60_000L

    /** Below this Android reports no signal at all (WifiInfo.INVALID_RSSI is -127). */
    private const val RSSI_FLOOR = -100
    private const val RSSI_FULL = -50

    /** Whether any of this applies: Headunit Server (Auto) with the phone's hotspot chosen. */
    fun appliesTo(mode: WifiLauncherMode, network: HeadunitServerNetwork): Boolean =
        mode == WifiLauncherMode.AUTO && network == HeadunitServerNetwork.PHONE_HOTSPOT

    fun line(phoneHotspot: Boolean, cardIsWifi: Boolean, facts: Facts): Line = when {
        !phoneHotspot || !cardIsWifi -> Line(Kind.HIDDEN)
        !facts.wifiEnabled -> Line(Kind.OFF)
        !facts.connected -> Line(Kind.NOT_CONNECTED)
        else -> Line(Kind.CONNECTED, cleanSsid(facts.rawSsid), band(facts.frequencyMhz), signalPercent(facts.rssiDbm))
    }

    /**
     * What the main button does on the phone's hotspot. On WiFi, what it always did. On none, it
     * gets the unit onto the hotspot first: searching before then can only find nothing. An access
     * point of this unit's own means the network was set up some other way than the one chosen,
     * so the button does what it always did rather than scan beside it.
     */
    fun connectAction(facts: Facts, unitHotspotUp: Boolean): ConnectAction = when {
        unitHotspotUp -> ConnectAction.CONNECT
        !facts.wifiEnabled -> ConnectAction.TURN_WIFI_ON_AND_WAIT
        !facts.connected -> ConnectAction.SCAN_AND_WAIT
        else -> ConnectAction.CONNECT
    }

    /** Whether the wait the main button started is over because the unit joined a network. */
    fun waitJoined(facts: Facts): Boolean = facts.wifiEnabled && facts.connected

    /**
     * Whether the home scans by itself now: only when the user turned that on, and only while there
     * is a hotspot to find and nothing else in the way.
     */
    fun shouldAutoScan(enabled: Boolean, facts: Facts, unitHotspotUp: Boolean, sessionActive: Boolean): Boolean =
        enabled && facts.wifiEnabled && !facts.connected && !unitHotspotUp && !sessionActive

    /**
     * The pause before the next automatic scan, by how long the unit has been without WiFi since
     * the home came on screen: brisk at first, when the hotspot has most likely just been switched
     * on, slower once it has stayed away.
     */
    fun autoScanDelayMs(sinceStartMs: Long): Long =
        if (sinceStartMs < AUTO_SCAN_BRISK_MS) RESCAN_MS else AUTO_SCAN_SLOW_MS

    /** The name without its quotes, or null for Android's placeholder. */
    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (s.isEmpty() || s.equals("<unknown ssid>", ignoreCase = true) || s == "0x") return null
        return s
    }

    fun band(frequencyMhz: Int?): Band? = when (frequencyMhz) {
        null -> null
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5900 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> null
    }

    /** -100 dBm and below is 0 %, -50 dBm and above is 100 %; null when there is no reading. */
    fun signalPercent(rssiDbm: Int?): Int? {
        if (rssiDbm == null || rssiDbm < -126 || rssiDbm >= 0) return null
        val clamped = rssiDbm.coerceIn(RSSI_FLOOR, RSSI_FULL)
        return (clamped - RSSI_FLOOR) * 100 / (RSSI_FULL - RSSI_FLOOR)
    }

    fun barsFor(signalPercent: Int?): Int = when {
        signalPercent == null || signalPercent <= 0 -> 0
        signalPercent >= 75 -> 4
        signalPercent >= 50 -> 3
        signalPercent >= 25 -> 2
        else -> 1
    }
}
