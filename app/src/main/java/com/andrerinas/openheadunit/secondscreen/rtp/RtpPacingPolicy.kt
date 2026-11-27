package com.andrerinas.openheadunit.secondscreen.rtp

/**
 * How a large access unit is spread out. A keyframe sent as one burst can overflow a small receiver's
 * socket buffer, and losing the keyframe loses the very repair the refresh asked for.
 */
object RtpPacingPolicy {

    /** A unit this small goes out at once. */
    const val UNPACED_PACKETS = 8
    /** The most a unit may be spread over, so the lane never falls behind the stream. */
    const val MAX_SPREAD_MS = 20L

    /**
     * Packets to send between 1 ms pauses, or 0 for none. [gapMs] is the time since the previous unit,
     * or null for the first, which is usually the keyframe a receiver needs most.
     */
    fun packetsPerPause(packets: Int, gapMs: Long?): Int {
        if (packets <= UNPACED_PACKETS) return 0
        val spreadMs = if (gapMs == null) MAX_SPREAD_MS else minOf(MAX_SPREAD_MS, gapMs / 2)
        if (spreadMs < 1) return 0
        return maxOf(UNPACED_PACKETS / 2, ((packets + spreadMs - 1) / spreadMs).toInt())
    }

    /** The datagrams [length] bytes of Annex-B will take, near enough to choose a pace. */
    fun estimatePackets(length: Int): Int = length / RtpH264Packetizer.MAX_PAYLOAD + 1
}
