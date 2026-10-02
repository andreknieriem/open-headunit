package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

/** How soon [ZbtAaCarrier] dials a daemon again after it refused, and when to say it is gone. */
object ZbtReopenPolicy {

    /** The longest wait between dials. */
    const val CEILING_MS = 30_000L

    private val DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L)

    /** A daemon back within a second should not cost half a minute. [consecutiveRefusals] counts from 1. */
    fun delayAfterRefusalMs(consecutiveRefusals: Int): Long =
        DELAYS_MS.getOrElse(maxOf(consecutiveRefusals, 1) - 1) { CEILING_MS }

    /** Whether the daemon has stayed away long enough to tell the user the route is dead. */
    fun warnsNoDaemon(sinceFirstRefusalMs: Long): Boolean =
        sinceFirstRefusalMs >= ZbtReachabilityPolicy.REFUSAL_WINDOW_MS
}
