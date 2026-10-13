package com.andrerinas.openheadunit.secondscreen.rtp

/**
 * When the RTP push binds its socket again. A network that comes up late, or returns as a new one,
 * leaves a socket bound to the old route sending into the dark; a fresh bind picks the route up.
 */
object RtpRebindPolicy {
    const val MIN_GAP_MS = 5_000L

    fun shouldRebind(nowMs: Long, lastBindMs: Long, onDestinationNetwork: Boolean, failedSinceBind: Boolean): Boolean =
        nowMs - lastBindMs >= MIN_GAP_MS && (failedSinceBind || !onDestinationNetwork)

    /**
     * A new socket is a new source port, so a rebind keeps the old one unless a send failed or a bind
     * to a new candidate can work. Ids are network ids; null means none.
     */
    fun keepsSocket(failedSinceBind: Boolean, candidateId: String?, boundId: String?, lastFailedBindId: String?): Boolean =
        !failedSinceBind && (candidateId == null || candidateId == boundId || candidateId == lastFailedBindId)
}
