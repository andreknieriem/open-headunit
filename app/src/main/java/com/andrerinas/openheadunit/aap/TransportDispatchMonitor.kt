package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * How long the transport's read thread spends dispatching rather than reading the socket.
 *
 * One thread used to serve every channel, and [com.andrerinas.openheadunit.decoder.video.VideoFeedThrottlePolicy]
 * can park for up to a second on a full video queue. Nothing was read while that happened, audio
 * included, which this measured: 132 to 145 blocks a window, up to 233 ms each, with the audio sink
 * underrunning beside them.
 *
 * Video has its own thread now, so the park lives there and this line should read `blocks=0` with
 * the picture still under load. `videoQueue` is where the backlog moved to, and is what says the
 * park is still happening rather than gone. It is not bounded by the ack window we announce, which
 * a held feed ran twenty-one times past, so read it against `AapTransport`'s own ceiling and treat
 * `videoShed` above zero as that ceiling being reached.
 *
 * Pure and clock-free: the caller passes the elapsed time, so a measured session replays in a test.
 */
class TransportDispatchMonitor {

    private var started = false
    private var windowStartMs = 0L
    private var videoMs = 0L
    private var audioMs = 0L
    private var otherMs = 0L
    private var worstMs = 0L
    private var worstChannel = -1
    private var blocks = 0
    private var maxVideoQueue = 0
    private var shedAtWindowStart = -1L
    private var videoShed = 0L

    /** A dispatch that held the thread at least this long is worth counting on its own. */
    private val blockingMs = 50L

    /**
     * Feed one dispatch. [tookMs] is how long the handler held the read thread, and [nowMs] the
     * time it finished.
     */
    fun onDispatch(
        channel: Int,
        tookMs: Long,
        nowMs: Long,
        videoQueueDepth: Int = 0,
        videoShedTotal: Long = 0
    ): Report? {
        if (!started) {
            started = true
            windowStartMs = nowMs
        }

        if (videoQueueDepth > maxVideoQueue) maxVideoQueue = videoQueueDepth
        if (shedAtWindowStart < 0L) shedAtWindowStart = videoShedTotal
        // A total that went backwards is a restarted transport, not a negative count.
        videoShed = (videoShedTotal - shedAtWindowStart).coerceAtLeast(0L)
        val took = if (tookMs > 0L) tookMs else 0L
        when {
            // The main display only. An auxiliary display's dispatch time lands in otherMs, and
            // worstChannel names it, so a slow second lane is still attributable.
            channel == Channel.ID_VID -> videoMs += took
            Channel.isAudio(channel) -> audioMs += took
            else -> otherMs += took
        }
        if (took >= blockingMs) blocks++
        if (took > worstMs) {
            worstMs = took
            worstChannel = channel
        }

        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < InboundRateMonitor.WINDOW_MS) return null

        val report = Report(
            elapsedMs, videoMs, audioMs, otherMs, worstMs, worstChannel, blocks, maxVideoQueue, videoShed
        )
        windowStartMs = nowMs
        shedAtWindowStart = videoShedTotal
        videoShed = 0L
        videoMs = 0L
        audioMs = 0L
        otherMs = 0L
        worstMs = 0L
        worstChannel = -1
        blocks = 0
        maxVideoQueue = 0
        return report
    }

    fun reset() {
        started = false
        windowStartMs = 0L
        videoMs = 0L
        audioMs = 0L
        otherMs = 0L
        worstMs = 0L
        worstChannel = -1
        blocks = 0
        maxVideoQueue = 0
        shedAtWindowStart = -1L
        videoShed = 0L
    }

    /** One window of dispatch cost. */
    data class Report(
        val windowMs: Long,
        val videoMs: Long,
        val audioMs: Long,
        val otherMs: Long,
        val worstMs: Long,
        val worstChannel: Int,
        val blocks: Int,
        /** Deepest the video thread's backlog got: where the park moved to. */
        val maxVideoQueue: Int = 0,
        /** Messages refused because the backlog was at its ceiling. Zero on a healthy link. */
        val videoShed: Long = 0
    ) {
        /** Share of the window the socket went unread, in whole percent. */
        val deadPercent: Int
            get() = if (windowMs <= 0L) 0 else (((videoMs + audioMs + otherMs) * 100) / windowMs).toInt()

        /**
         * Whether anything here is worth a reader's attention.
         *
         * The 5% was written before video had its own thread. On a slow unit audio dispatch is now
         * most of what keeps the socket unread - 2598ms of a 2917ms total, over 151 windows - so
         * this reads false there on the audio line alone, with `video=` a thirteenth of it.
         */
        val healthy: Boolean
            get() = blocks == 0 && deadPercent < 5 && videoShed == 0L

        override fun toString(): String {
            val worst = if (worstChannel < 0) "none" else Channel.name(worstChannel)
            return "transport dispatch over ${windowMs}ms: video=${videoMs}ms, audio=${audioMs}ms, " +
                "other=${otherMs}ms, unread=$deadPercent%, blocks=$blocks, " +
                "longest=${worstMs}ms on $worst, videoQueue=$maxVideoQueue, videoShed=$videoShed"
        }
    }
}
