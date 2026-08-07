package com.andrerinas.openheadunit.connection.wifi.modes.native

/** Which network the Native AA mode puts the phone on. */
enum class NativeStrategy(val id: Int) {

    /** A WiFi Direct P2P group with this head unit as group owner. The default. */
    WIFI_DIRECT(0),

    /** This head unit's own WPA2 access point, as the OEM ZLink app uses. Experimental. */
    HOTSPOT(1);

    companion object {

        val DEFAULT: NativeStrategy = WIFI_DIRECT


        fun byIdOrDefault(id: Int): NativeStrategy {
            for (mode in NativeStrategy.entries) {
                if (mode.id == id)
                    return mode
            }

            return DEFAULT
        }
    }
}
