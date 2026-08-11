package com.andrerinas.openheadunit.connection.wifi.modes.helper

enum class HelperStrategy(val id: Int) {

    COMMON_WIFI(0), // NSD
    WIFI_DIRECT(1), // P2P
    NEARBY_DEVICES(2), // Google Nearby
    PHONE_HOTSPOT(3),
    HEADUNIT_HOTSPOT(4);

    companion object {

        val DEFAULT: HelperStrategy = NEARBY_DEVICES


        fun byIdOrDefault(id: Int): HelperStrategy {
            for (mode in HelperStrategy.entries) {
                if (mode.id == id)
                    return mode
            }

            return DEFAULT
        }
    }
}
