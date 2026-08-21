package com.andrerinas.openheadunit.aap

/**
 * Which wireless configurations reach [com.andrerinas.openheadunit.connection.NetworkDiscovery],
 * and therefore probe Android Auto's head unit server on port 5277.
 *
 * Worth naming rather than leaving inline, because the answer surprises people: "Headunit Server"
 * (mode 1) and "Wireless Helper / Phone Hotspot (Host)" (mode 2, strategy 3) look like unrelated
 * features in the settings screen and are the same code path underneath. A defect found on one is
 * a defect on the other, which is how one bug report has read like two.
 *
 * @see com.andrerinas.openheadunit.utils.Settings.wifiConnectionMode
 * @see com.andrerinas.openheadunit.utils.Settings.helperConnectionStrategy
 */
object DiscoveryModePolicy {

    /**
     * @param mode 0 = Manual, 1 = Auto (Headunit Server), 2 = Helper, 3 = Native AA.
     * @param strategy only meaningful when [mode] is 2: 0 = Common WiFi (NSD), 1 = WiFi Direct,
     *   2 = Google Nearby, 3 = Phone Hotspot (Host), 4 = Headunit Hotspot (Passive).
     */
    fun usesNetworkDiscovery(mode: Int, strategy: Int): Boolean = when (mode) {
        // Native AA runs its own handshake and never scans for a server.
        3 -> false
        // WiFi Direct and Nearby bring the phone to us; the others need us to go and find it.
        2 -> strategy == 0 || strategy == 3 || strategy == 4
        else -> true
    }
}
