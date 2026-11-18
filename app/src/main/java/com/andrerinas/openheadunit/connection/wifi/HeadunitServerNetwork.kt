package com.andrerinas.openheadunit.connection.wifi

/**
 * Which network the phone and this unit share in Headunit Server (Auto) mode. The server mode does
 * not care, but the WiFi on this unit does: on the phone's hotspot nothing can be found until this
 * unit has joined it, while on its own access point or a shared router the unit is never a WiFi
 * client of the phone at all.
 *
 * Only [PHONE_HOTSPOT] changes anything; the other two keep Headunit Server as it always was.
 */
enum class HeadunitServerNetwork(val id: Int) {

    /** The router in the car or at home that both are on. What Headunit Server always assumed. */
    SHARED(0),

    /** This unit joins the phone's hotspot. */
    PHONE_HOTSPOT(1),

    /** The phone joins this unit's access point. */
    UNIT_HOTSPOT(2);

    companion object {

        /**
         * The choice on a unit that has never made one: [UNIT_HOTSPOT] for a user who already
         * handed the app their access point, [SHARED] for everyone else, so an update changes
         * nothing for anybody.
         */
        fun byIdOrDefault(id: Int, autoEnableHotspot: Boolean): HeadunitServerNetwork =
            entries.firstOrNull { it.id == id } ?: if (autoEnableHotspot) UNIT_HOTSPOT else SHARED

        /**
         * Whether a discovery sweep is pointless right now: on the phone's hotspot, with this unit
         * on no WiFi network, there is no network the phone could be on. The sweep would probe
         * whatever else is up, a cellular bridge typically, and find nothing.
         */
        fun sweepWaitsForWifi(mode: WifiLauncherMode, network: HeadunitServerNetwork, wifiJoined: Boolean): Boolean =
            mode == WifiLauncherMode.AUTO && network == PHONE_HOTSPOT && !wifiJoined
    }
}
