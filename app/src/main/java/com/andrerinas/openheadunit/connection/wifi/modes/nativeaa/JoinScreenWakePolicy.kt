package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether to light this unit's screen while a phone joins the network it was just handed.
 *
 * With the screen off, Android turns on the WiFi driver's suspend optimizations, and on some
 * radios that filter drops every broadcast frame before it reaches the system. A phone's DHCP
 * request is a broadcast. Measured on a Note8 hosting the Native group: the phone completed the
 * WPA handshake twelve times, nothing arrived on UDP 67, no lease was given, and each round ended
 * in WIFI_NETWORK_UNAVAILABLE about 36 s after the credentials - Android Auto opening on the phone
 * and closing again. Lit, the same join landed in 3-4 s; dark with the optimizations switched off,
 * in 3 s. A high-performance WiFi lock does not lift them on that build, so the screen is the
 * lever an app has.
 *
 * Only for a phone that has opened the handshake and asked for the credentials: a wake poke
 * nobody answers must not light a parked unit.
 */
object JoinScreenWakePolicy {

    enum class Hold {
        /** Dark: light it, or the phone's DHCP may never arrive. */
        WAKE,
        /** Lit: keep it so. The same Note8 lets a lit keyguard go dark after 5 s, inside a join. */
        KEEP_LIT,
        /** The screen state could not be read, so nothing is done to it. */
        NONE,
        /**
         * The phone kept refusing with the screen held for it, so the screen is not what stops it
         * (a hotspot on the phone is the usual cause) and every retry would light a parked unit.
         */
        RULED_OUT,
    }

    /**
     * The longest the screen is held for one join. The phone's own attempt took 36-37 s in every
     * refused round measured; the handoff's settle window is the same budget, and the hold is
     * released earlier the moment the join ends either way.
     */
    const val HOLD_MS = NativeHandoffPolicy.SETTLE_TIMEOUT_MS

    /**
     * What to do with the screen for this join, after [refusalsInARow] joins the phone refused. One
     * refusal is let through as the refusal backoff lets it through: it can be a slow lease.
     */
    fun hold(screenInteractive: Boolean?, refusalsInARow: Int): Hold = when {
        refusalsInARow > JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE -> Hold.RULED_OUT
        screenInteractive == false -> Hold.WAKE
        screenInteractive == true -> Hold.KEEP_LIT
        else -> Hold.NONE
    }
}
