package com.andrerinas.openheadunit.app

/** What an ACL event from a Bluetooth device does to a pending auto-disconnect. */
enum class BtAutoDisconnectArm { IGNORE, ARM, CANCEL }

/**
 * Ends a session when a chosen device's Bluetooth link goes away, the way the Exit button does.
 * The loss arms a timer that the device coming back cancels, and nothing fires without a session:
 * Native AA's wake poke opens and closes a socket to the phone on every cycle.
 */
object BtAutoDisconnectPolicy {

    /**
     * How long after this app itself closed a socket to the device a Bluetooth loss is taken as
     * that close rather than the device leaving. The handshake and poke sockets close seconds
     * after the handoff and the OS then reports the phone's link gone.
     */
    const val OWN_SOCKET_CLOSE_GRACE_MS = 15_000L

    const val MAX_DELAY_SECONDS = 3600

    fun onAclEvent(watchedMacs: Set<String>, deviceMac: String?, connected: Boolean): BtAutoDisconnectArm {
        if (deviceMac == null || deviceMac !in watchedMacs) return BtAutoDisconnectArm.IGNORE
        return if (connected) BtAutoDisconnectArm.CANCEL else BtAutoDisconnectArm.ARM
    }

    fun graceDelayMs(delaySeconds: Int): Long = delaySeconds.coerceIn(0, MAX_DELAY_SECONDS) * 1000L

    /**
     * Whether closing our end of a link to the phone counts for [OWN_SOCKET_CLOSE_GRACE_MS]. Not
     * when the phone closed it first: Native AA holds its channel for the whole session, and when
     * the phone's Bluetooth goes that channel ends just before the OS reports the link gone.
     * Counting that close would put every real loss inside the grace.
     */
    fun closeIsOurs(peerClosed: Boolean): Boolean = !peerClosed

    /** Asked once the grace delay has run out; [msSinceOwnSocketClose] is null if we never closed one. */
    fun shouldEndSession(sessionUp: Boolean, deviceCameBack: Boolean, msSinceOwnSocketClose: Long?): Boolean =
        sessionUp && !deviceCameBack &&
            (msSinceOwnSocketClose == null || msSinceOwnSocketClose >= OWN_SOCKET_CLOSE_GRACE_MS)
}
