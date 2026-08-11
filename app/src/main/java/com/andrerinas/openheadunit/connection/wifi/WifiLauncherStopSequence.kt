package com.andrerinas.openheadunit.connection.wifi

/**
 * Certain services may only be stopped in a specific order.
 */
enum class WifiLauncherStopSequence {

    /**
     * Sequence doesn't matter, stop every service.
     */
    ANY,

    /**
     * Called before the hotspot is turned off.
     */
    BEFORE_HOTSPOT_DISABLE,

    /**
     * Last sequence called.
     */
    LAST;


    // Get whether we shall stop the given service at this sequence.
    // Basically just lets ANY pass as well at any time
    fun handledAt(seq: WifiLauncherStopSequence): Boolean {
        return this == ANY || this == seq
    }
}
