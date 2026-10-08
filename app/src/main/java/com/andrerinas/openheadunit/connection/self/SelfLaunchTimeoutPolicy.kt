package com.andrerinas.openheadunit.connection.self

/** Which of Self Mode's two bring-up routes a launch attempt took. */
enum class SelfLaunchPath {
    /** AA < 17.4: fire `WirelessStartupActivity` and wait for Gearhead to dial our port 5288. */
    LEGACY,

    /** AA >= 17.4: dial Android Auto's own head unit server on `127.0.0.1:5277` ourselves. */
    HEADUNIT_SERVER,
}

/**
 * How long a Self Mode launch may take before the user is told it did not work, and what may be
 * torn down when that happens.
 *
 * The deadline used to be 2500 ms for both routes, and expiring it called `CommManager.emitError`,
 * which disconnects. On [SelfLaunchPath.LEGACY] that is self-defeating twice over: the disconnect
 * takes down the dummy VPN whose `Network` we just handed Gearhead as `PARAM_SERVICE_WIFI_NETWORK`,
 * and it closes the port 5288 server that is the only way the phone can arrive. Gearhead needs 15
 * to 20 s on that route, so the deadline expired every time and killed sessions that were working.
 *
 * Hence the two rules here. A deadline the slower route can actually meet, and a timeout that
 * reports without tearing anything down - the launch may still be in flight, and on the head unit
 * server route a connection dropped before the AAP version exchange leaves that server deaf until
 * the user restarts it by hand.
 */
object SelfLaunchTimeoutPolicy {

    /**
     * Gearhead has to start `WirelessStartupActivity`, pick up the network it was handed and dial
     * back over loopback. Measured at 15 to 20 s on a UNISOC head unit, so the deadline is set
     * clear of that rather than at it.
     */
    const val LEGACY_DEADLINE_MS = 30_000L

    /**
     * `CommManager.connect` finishes its physical dial before the launcher returns. Keep a
     * short deadline only when no connection arrived. A fulfilled launch retires its deadline;
     * later handshake failure or settings teardown belongs to that session's recovery policy.
     */
    const val HEADUNIT_SERVER_DEADLINE_MS = 10_000L

    fun deadlineMs(path: SelfLaunchPath): Long = when (path) {
        SelfLaunchPath.LEGACY -> LEGACY_DEADLINE_MS
        SelfLaunchPath.HEADUNIT_SERVER -> HEADUNIT_SERVER_DEADLINE_MS
    }

    /**
     * Never, on either route, and a test holds that. A timeout means "we have not heard yet", which
     * is not the same as "nothing is coming" - the answer is to say so, not to remove the things the
     * answer would arrive on. A route that ever needs a teardown here should justify it in this
     * function rather than reaching for a disconnect at the call site.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mayDisconnect(path: SelfLaunchPath): Boolean = false
}
