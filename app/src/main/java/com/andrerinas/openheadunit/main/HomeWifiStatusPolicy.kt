package com.andrerinas.openheadunit.main

/**
 * What the WiFi line next to the main button's icon says, and when the Scan WiFi button beside the
 * main button shows. Only for Headunit Server, where this unit joins the phone's hotspot; Native
 * and WiFi Direct bring their own network, so "not connected" would mislead there.
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

        /** Not on WiFi: the scan is the next thing to do, so its button stands out. */
        val needsAttention: Boolean get() = kind == Kind.OFF || kind == Kind.NOT_CONNECTED
    }

    /** Below this Android reports no signal at all (WifiInfo.INVALID_RSSI is -127). */
    private const val RSSI_FLOOR = -100
    private const val RSSI_FULL = -50

    fun line(isHeadunitServer: Boolean, cardIsWifi: Boolean, facts: Facts): Line = when {
        !isHeadunitServer || !cardIsWifi -> Line(Kind.HIDDEN)
        !facts.wifiEnabled -> Line(Kind.OFF)
        !facts.connected -> Line(Kind.NOT_CONNECTED)
        else -> Line(Kind.CONNECTED, cleanSsid(facts.rawSsid), band(facts.frequencyMhz), signalPercent(facts.rssiDbm))
    }

    /**
     * Whether the Scan WiFi button shows. Not while a connection is on its way, during the
     * reconnect countdown, or over a session: the main button and Cancel are what matter then.
     * While the stack only listens for the phone it does show: that is when a scan helps most.
     */
    fun showsScanButton(line: Line, cardIdle: Boolean): Boolean = line.kind != Kind.HIDDEN && cardIdle

    /** Whether the card is idle enough for the Scan WiFi button; see [showsScanButton]. */
    fun cardIdle(status: HomePrimaryPolicy.Status): Boolean = when (status) {
        HomePrimaryPolicy.Status.CONNECTING,
        HomePrimaryPolicy.Status.RECONNECTING,
        HomePrimaryPolicy.Status.ACTIVE_SESSION -> false
        else -> true
    }

    /**
     * Whether Scan WiFi drops its label for its icon alone: when the actions row cannot hold the
     * main button's one-line label, the gap, Scan WiFi in full and whatever else is in the row
     * (Cancel while the stack listens). Otherwise the main button, which gives up width first, is
     * squeezed to nothing on a portrait phone.
     */
    fun scanCompact(availablePx: Int, actionNeededPx: Int, gapPx: Int, scanFullPx: Int, othersPx: Int): Boolean =
        availablePx > 0 && actionNeededPx + gapPx + scanFullPx + othersPx > availablePx

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
