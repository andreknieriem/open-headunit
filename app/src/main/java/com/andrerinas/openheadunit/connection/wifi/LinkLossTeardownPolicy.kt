package com.andrerinas.openheadunit.connection.wifi


/** A warning that the link carrying an active session is about to go away. */
enum class LinkLossTrigger {
    /** The device is shutting down or rebooting. Everything is about to go away. */
    DEVICE_SHUTDOWN,

    /** WiFi station mode is being switched off. Only sessions riding it are affected. */
    WIFI_STATION_DISABLING,

    /** The car is being switched off: an OEM ACC-off intent, or power lost as the screen goes. */
    ACC_POWER_LOST
}

/**
 * Whether to close the current session *now*, while the link still works, rather than let it die
 * with the interface.
 *
 * Android Auto's head unit server is wedged permanently by a peer that vanishes without closing: it
 * goes on accepting connections and answering none of them, and only a restart on the phone brings
 * it back. A session that closes properly does not do that — which is the difference between a
 * drive that ends with the app disconnecting and one that ends with the power being cut.
 *
 * We cannot help the cases that arrive without warning (driving out of range, the access point
 * restarting, a power cut with no orderly shutdown). We can help the ones the system tells us about
 * first, and this decides which of those apply to the session actually running.
 */
object LinkLossTeardownPolicy {

    /**
     * @param launcher the wireless route that is armed, or null when none is. Null is a real state
     *   rather than a missing argument: a wired session quiesces the wireless stack, and a shutdown
     *   arriving in that window still has a session to close.
     * @param peerIsHeadUnitServer the session's peer is Android Auto's own head unit server, the
     *   one route where a missing close costs the user their next drive.
     * @param accSignalIsExplicit the unit named the ACC-off itself, rather than us inferring it.
     */
    fun shouldTearDown(
        trigger: LinkLossTrigger,
        launcher: WifiLauncher?,
        sessionIsWireless: Boolean = true,
        peerIsHeadUnitServer: Boolean = false,
        accSignalIsExplicit: Boolean = false
    ): Boolean = when (trigger) {
        // The whole device is going, so every route's link is going with it — USB included.
        LinkLossTrigger.DEVICE_SHUTDOWN -> true

        // Only routes that ride WiFi station mode. A P2P group and a soft AP are separate
        // interfaces, and on several chipsets they outlive the station toggle entirely — tearing
        // a healthy Native AA session down here would cost a 45-90s reconnect to prevent nothing.
        // A USB session rides none of it and must be left alone whatever the settings say.
        LinkLossTrigger.WIFI_STATION_DISABLING ->
            sessionIsWireless &&
                launcher?.hasWifiDirect() != true &&
                launcher?.hostsOwnAccessPoint() != true

        // A named ACC-off is the whole board going, so it counts for every route. An inferred one
        // is only worth acting on where a missed close lasts beyond this drive, and where coming
        // back costs a gateway dial rather than a group and a handshake.
        LinkLossTrigger.ACC_POWER_LOST -> accSignalIsExplicit || peerIsHeadUnitServer
    }

}
