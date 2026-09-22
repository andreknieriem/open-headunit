package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * How many bytes a second actually arrive, split into video, audio and everything else.
 *
 * The third instrument on the inbound side and the one that answers what the other two cannot.
 * [LinkGapMonitor] says when a channel went quiet and [UplinkStallMonitor] says whether our own
 * writes drained, but a picture that sags without stopping leaves both of them silent, and neither
 * can tell a link at its ceiling from a phone that decided to send less.
 *
 * The rates apart are what carries the answer, which is why they are not summed into one number.
 * On the capture this was written for, video fell to a tenth of its rate while 48 kHz stereo audio
 * held its own throughout - about 1.5 Mbit/s of PCM crossing the same 2.4 GHz link, in the same
 * seconds the picture was dark. A saturated link cannot do that, so the phone was choosing.
 *
 * The window matches [LinkGapMonitor.WINDOW_MS] so the two lines land together and read against each
 * other. Unlike the gap monitor this reports on **every** window, including the healthy ones: a rate
 * only means something next to the rate beside it, and the window a reader most needs to compare
 * against is the one where nothing was wrong.
 *
 * **Diagnostic only - nothing reads the report.** Pure and clock-free: the caller passes the time,
 * so a measured session replays in a unit test.
 */
class InboundRateMonitor {

    private var started = false
    private var windowStartMs = 0L
    private var videoBytes = 0L
    private var audioBytes = 0L
    private var otherBytes = 0L
    private var videoMessages = 0
    private var audioMessages = 0
    private var otherMessages = 0
    private var auxVideoBytes = 0L
    private var auxVideoMessages = 0

    /**
     * Feed one decrypted inbound message and get a report when a window closes.
     *
     * [bytes] is the message payload; a negative one is counted as zero rather than allowed to run
     * the totals backwards, because the size field comes off the wire.
     */
    fun onMessage(channel: Int, bytes: Int, nowMs: Long): Report? {
        if (!started) {
            started = true
            windowStartMs = nowMs
        }

        val size = if (bytes > 0) bytes.toLong() else 0L
        when {
            channel == Channel.ID_VID -> { videoBytes += size; videoMessages++ }
            // Its own bucket rather than folded into video: every bandwidth reading in the tracker
            // is against the main stream, and a second display would silently inflate all of them.
            channel == Channel.ID_VID2 -> { auxVideoBytes += size; auxVideoMessages++ }
            Channel.isAudio(channel) -> { audioBytes += size; audioMessages++ }
            else -> { otherBytes += size; otherMessages++ }
        }

        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < WINDOW_MS) return null

        val report = Report(
            windowMs = elapsedMs,
            videoBytes = videoBytes,
            audioBytes = audioBytes,
            otherBytes = otherBytes,
            videoMessages = videoMessages,
            audioMessages = audioMessages,
            otherMessages = otherMessages,
            auxVideoBytes = auxVideoBytes,
            auxVideoMessages = auxVideoMessages,
        )

        windowStartMs = nowMs
        videoBytes = 0L
        audioBytes = 0L
        otherBytes = 0L
        videoMessages = 0
        audioMessages = 0
        otherMessages = 0
        auxVideoBytes = 0L
        auxVideoMessages = 0
        return report
    }

    /** Forget the session. A new one starts its first window at its first message. */
    fun reset() {
        started = false
        windowStartMs = 0L
        videoBytes = 0L
        audioBytes = 0L
        otherBytes = 0L
        videoMessages = 0
        audioMessages = 0
        otherMessages = 0
    }

    /** One window's worth of arrivals. */
    data class Report(
        val windowMs: Long,
        val videoBytes: Long,
        val audioBytes: Long,
        val otherBytes: Long,
        val videoMessages: Int,
        val audioMessages: Int,
        val otherMessages: Int,
        val auxVideoBytes: Long = 0L,
        val auxVideoMessages: Int = 0,
    ) {
        /** Whole kB per second, which is the resolution anything here is read at. */
        fun kbPerSecond(bytes: Long): Long = if (windowMs <= 0L) 0L else bytes * 1000 / windowMs / 1024

        // The auxiliary display is named only when it carried something, so a unit without one
        // prints exactly the line it always did and every existing grep still matches.
        override fun toString(): String =
            "inbound rate over ${windowMs}ms: video=${kbPerSecond(videoBytes)}kB/s ($videoMessages msgs), " +
                "audio=${kbPerSecond(audioBytes)}kB/s ($audioMessages msgs), " +
                "other=${kbPerSecond(otherBytes)}kB/s ($otherMessages msgs)" +
                if (auxVideoMessages > 0) {
                    ", auxVideo=${kbPerSecond(auxVideoBytes)}kB/s ($auxVideoMessages msgs)"
                } else ""
    }

    companion object {
        /** Shared with [LinkGapMonitor] so the two lines cover the same window and compare directly. */
        const val WINDOW_MS = LinkGapMonitor.WINDOW_MS
    }
}
