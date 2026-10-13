package com.andrerinas.openheadunit.secondscreen.rtp

import com.andrerinas.openheadunit.decoder.video.ParameterSetInspector
import com.andrerinas.openheadunit.secondscreen.EncodedOutput
import com.andrerinas.openheadunit.secondscreen.H264Units
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Random

/** Where the datagrams go. */
interface RtpSocket {
    /** The destination as `ip:port`, for the log. */
    val target: String

    /** Opens the socket and returns the network it is bound to, for the log. */
    fun open(): String

    /** True when the socket is bound to the network that holds the destination. */
    val onDestinationNetwork: Boolean

    /**
     * Opens a new socket by the same route as [open] when a bind can change or [failedSinceBind];
     * otherwise keeps the socket. Returns the network label.
     */
    fun rebind(failedSinceBind: Boolean): String
    fun send(buf: ByteArray, off: Int, len: Int)
    fun close()
}

/** Looks for other senders on the push's destination; a fault in it never reaches the push. */
interface RtpSenderProbe {
    fun start()

    /** The current socket summary, or null when there is nothing to say. Throws when the tables cannot be read. */
    fun sockets(): String?
    fun stop()

    companion object {
        val NONE = object : RtpSenderProbe {
            override fun start() {}
            override fun sockets(): String? = null
            override fun stop() {}
        }
    }
}

/**
 * Pushes the second stream as RTP over UDP to a typed address, for a receiver that connects to
 * nothing. A send failure is logged once per kind and never reaches the aux lane. Every
 * [refreshIntervalMs] with no keyframe it asks for one, because nothing else repairs a lost packet.
 */
class RtpStreamOutput(
    private val socket: RtpSocket,
    private val clock: () -> Long,
    random: Random,
    private val refreshIntervalMs: Long = 0,
    private val pause: (Long) -> Unit = ::sleepKeepingInterrupt,
    private val probe: RtpSenderProbe = RtpSenderProbe.NONE,
    private val onStreamParameters: (String) -> Unit = {},
) : EncodedOutput {

    override val output = SecondScreenOutputPolicy.Output.RTP_PUSH
    override var onKeyframeNeeded: (() -> Unit)? = null

    private val initialTimestamp = random.nextInt()
    private val packetizer = RtpH264Packetizer(::pacedSend, random.nextInt(), random.nextInt())
    internal val failureCounts = HashMap<String, Int>()
    private val failuresSinceReport = HashMap<String, Int>()

    private var firstNanos = -1L
    private var keyframeDecided = false
    private var lastKeyframeNanos = 0L
    private var lastRequestNanos = 0L
    private var refreshes = 0
    private var lastUnitNanos = -1L
    private var packetsPerPause = 0
    private var sentInUnit = 0
    private var pauses = 0
    private var lastReportNanos = 0L
    private var largestGapMs = 0L
    private var lastSocketLine: String? = null
    private var lastBindMs = 0L
    private var lastBindLabel = ""
    private var failedSinceBind = false
    @Volatile private var running = false

    init {
        packetizer.onParameterSets = { sps, pps -> logParameterSets(sps, pps) }
    }

    override fun start() {
        val network = socket.open()
        running = true
        lastReportNanos = clock()
        lastBindMs = lastReportNanos / 1_000_000
        lastBindLabel = network
        failedSinceBind = false
        lastKeyframeNanos = lastReportNanos
        lastRequestNanos = lastReportNanos
        AppLog.i("SecondScreen: RTP push to ${socket.target} open (network=$network, refresh=${refreshIntervalMs}ms)")
        try {
            probe.start()
            senderLineIfChanged()?.let { AppLog.i("SecondScreen: $it") }
        } catch (e: Exception) {
            AppLog.w("SecondScreen: RTP push sender probe failed (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    private fun logParameterSets(sps: ByteArray, pps: ByteArray) {
        val parsed = ParameterSetInspector.parseH264Sps(sps, 0, sps.size)
        val fmtp = RtpDestinationPolicy.streamParameters(sps, pps)
        AppLog.i("SecondScreen: RTP push stream SPS " + (parsed?.toString() ?: "unreadable (${sps.size} bytes)") +
            (fmtp?.let { ", fmtp $it" } ?: ""))
        fmtp?.let(onStreamParameters)
    }

    /** The socket line when it differs from the last one logged; a failed read keeps its own grep text. */
    internal fun senderLineIfChanged(): String? {
        val line = try {
            probe.sockets()?.let { "RTP push UDP sockets: $it" }
        } catch (e: Exception) {
            "RTP push UDP sockets not readable (${e.javaClass.simpleName}: ${e.message})"
        } ?: return null
        if (line == lastSocketLine) return null
        lastSocketLine = line
        return line
    }

    override fun onAccessUnit(buf: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        try {
            val now = clock()
            rebindIfDue(now)
            if (firstNanos < 0) firstNanos = now
            // Truncation to 32 bits is the RTP timestamp's own wrap.
            val timestamp = (initialTimestamp + (now - firstNanos) * 90 / 1_000_000).toInt()
            if (!keyframeDecided) decideKeyframe(buf, offset, length)
            val gapMs = if (lastUnitNanos < 0) null else (now - lastUnitNanos) / 1_000_000
            lastUnitNanos = now
            if (gapMs != null && gapMs > largestGapMs) largestGapMs = gapMs
            packetsPerPause = RtpPacingPolicy.packetsPerPause(RtpPacingPolicy.estimatePackets(length), gapMs)
            sentInUnit = 0
            if (packetizer.packetize(buf, offset, length, timestamp)) lastKeyframeNanos = now
            refreshIfDue(now)
        } catch (e: Exception) {
            failedSinceBind = true
            noteFailure(e)
        }
        reportIfDue()?.let {
            AppLog.i("SecondScreen: $it")
            senderLineIfChanged()?.let { line -> AppLog.i("SecondScreen: $line") }
        }
    }

    // The lane exists only now, so the one request cannot come from start(). A config-only unit
    // usually comes just before the IDR, so wait for the next unit rather than cycle focus on it.
    private fun decideKeyframe(buf: ByteArray, offset: Int, length: Int) {
        if (H264Units.isKeyframe(buf, offset, length)) {
            keyframeDecided = true
        } else if (H264Units.parameterSets(buf, offset, length) == null) {
            keyframeDecided = true
            lastRequestNanos = clock()
            onKeyframeNeeded?.invoke()
        }
    }

    private fun rebindIfDue(now: Long) {
        val nowMs = now / 1_000_000
        if (!RtpRebindPolicy.shouldRebind(nowMs, lastBindMs, socket.onDestinationNetwork, failedSinceBind)) return
        lastBindMs = nowMs
        try {
            val label = socket.rebind(failedSinceBind)
            failedSinceBind = false
            if (label != lastBindLabel) {
                lastBindLabel = label
                AppLog.i("SecondScreen: RTP push bound again (network=$label)")
            }
        } catch (e: Exception) {
            noteFailure(e)
        }
    }

    private fun pacedSend(buf: ByteArray, off: Int, len: Int) {
        if (packetsPerPause > 0 && sentInUnit > 0 && sentInUnit % packetsPerPause == 0) {
            pauses++
            pause(1)
        }
        sentInUnit++
        socket.send(buf, off, len)
    }

    private fun refreshIfDue(now: Long) {
        if (!RtpRefreshPolicy.shouldRequest(now / 1_000_000, lastKeyframeNanos / 1_000_000,
                lastRequestNanos / 1_000_000, refreshIntervalMs)) return
        lastRequestNanos = now
        refreshes++
        if (RtpRefreshPolicy.logsEachRequest(refreshIntervalMs)) {
            AppLog.i("SecondScreen: RTP push asking for a keyframe, none sent for ${(now - lastKeyframeNanos) / 1_000_000}ms")
        }
        onKeyframeNeeded?.invoke()
    }

    private fun noteFailure(e: Exception) {
        val kind = e.javaClass.simpleName
        val seen = failureCounts[kind] ?: 0
        failureCounts[kind] = seen + 1
        failuresSinceReport[kind] = (failuresSinceReport[kind] ?: 0) + 1
        if (seen == 0) {
            AppLog.w("SecondScreen: RTP push send failed ($kind): ${e.message}")
        }
    }

    /** The report line once ten seconds have passed since the last one, and the counters restart. */
    internal fun reportIfDue(): String? {
        val now = clock()
        if (now - lastReportNanos < REPORT_NANOS) return null
        var line = "RTP push sent ${packetizer.packets} packets, ${packetizer.bytes} bytes, " +
            "${packetizer.keyframes} keyframes in ${(now - lastReportNanos) / 1_000_000}ms, " +
            "${packetizer.keyframesWithOwnParameterSets} keyframes carried their own SPS and PPS, " +
            "$refreshes refreshes asked, $pauses pacing pauses, largest gap ${largestGapMs}ms, " +
            "${packetizer.configUnitsHeld} config units held"
        if (failuresSinceReport.isNotEmpty()) {
            line += ", send failures " + failuresSinceReport.keys.sorted().joinToString(" ") { "$it=${failuresSinceReport[it]}" }
            failuresSinceReport.clear()
        }
        packetizer.resetCounters()
        refreshes = 0
        pauses = 0
        largestGapMs = 0
        lastReportNanos = now
        return line
    }

    override fun stop() {
        if (!running) return
        running = false
        try { probe.stop() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
        AppLog.i("SecondScreen: RTP push stopped")
    }

    private companion object {
        const val REPORT_NANOS = 10_000_000_000L
    }
}

/** A pause on the aux lane must not swallow the interrupt that stops the lane. */
private fun sleepKeepingInterrupt(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
