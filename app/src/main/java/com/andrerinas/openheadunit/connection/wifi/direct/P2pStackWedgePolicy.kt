package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether this unit's WiFi Direct stack is wedged, and whether the app may restart WiFi to free it.
 *
 * Measured on a MediaTek phone on Android 10: when the app's process dies while it owns a group,
 * wpa_supplicant keeps the dead P2P interface registered. From then on every attempt to bring the
 * interface back fails inside the framework (`WifiP2pNative: Failed to setup P2p iface in
 * supplicant`), so it is added and torn down in a loop, the state broadcast flips between disabled
 * and enabled, and every createGroup answers BUSY. Nothing the app asks for can succeed until WiFi
 * itself is switched off and on, which restarts the supplicant. It does not clear on its own.
 *
 * The signature that separates this from an ordinary refusal is the pair: the state broadcast keeps
 * reporting WiFi Direct as disabled while WiFi is on, *and* creates keep failing. A radio that is
 * merely refusing a band never disables P2P, and a user switching WiFi off is not "WiFi on".
 * [P2pInterfaceChurnPolicy] covers a different case - another app cycling the stack while we ask
 * for nothing - and by design never counts edges that land while a request of ours is out, which
 * is exactly when this one happens.
 *
 * Android 10 and later refuse to let an app switch WiFi, so the restart needs root or this unit's
 * own ADB, and is opt-in. Without either the banner offers the system WiFi panel instead.
 */
object P2pStackWedgePolicy {

    /** How far back the two counts look. */
    const val WINDOW_MS = 60_000L

    /** Disabled broadcasts within [WINDOW_MS] before the stack counts as cycling. */
    const val MIN_DISABLE_EDGES = 2

    /** Failed creates within [WINDOW_MS] before the cycling counts as blocking us. */
    const val MIN_CREATE_FAILURES = 3

    /** No second automatic restart sooner than this after the last one. */
    const val RESTART_COOLDOWN_MS = 120_000L

    /** At most this many automatic restarts in any hour, so a unit that never recovers stops trying. */
    const val MAX_RESTARTS_PER_HOUR = 3

    const val HOUR_MS = 3_600_000L

    /** Run through root or this unit's ADB; both can call `svc`. The pause lets the supplicant exit. */
    const val RESTART_COMMAND = "svc wifi disable && sleep 3 && svc wifi enable"

    /** How many of [times] fall within [windowMs] before [now]. A time after [now] is a clock jump and is ignored. */
    fun countRecent(times: Collection<Long>, now: Long, windowMs: Long = WINDOW_MS): Int =
        times.count { val age = now - it; age in 0..windowMs }

    /** True when WiFi is on but its Direct stack keeps reporting disabled and our creates keep failing. */
    fun isWedged(wifiEnabled: Boolean, disableEdgesInWindow: Int, createFailuresInWindow: Int): Boolean =
        wifiEnabled &&
            disableEdgesInWindow >= MIN_DISABLE_EDGES &&
            createFailuresInWindow >= MIN_CREATE_FAILURES

    /**
     * Whether an automatic restart may run now.
     *
     * Never while a session is connected: a live projection proves the stack works, and restarting
     * WiFi would end it. Never without the user's opt-in, because it switches WiFi off for every app.
     */
    fun mayAutoRestart(
        optedIn: Boolean,
        sessionConnected: Boolean,
        now: Long,
        restartTimes: Collection<Long>,
    ): Boolean {
        if (!optedIn || sessionConnected) return false
        val last = restartTimes.maxOrNull()
        if (last != null && (now - last) in 0 until RESTART_COOLDOWN_MS) return false
        return countRecent(restartTimes, now, HOUR_MS) < MAX_RESTARTS_PER_HOUR
    }
}
