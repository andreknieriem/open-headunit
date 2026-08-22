package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings, private val onFrameCorrupted: () -> Unit) {

    companion object {
        /** Enough for H.264 at the resolutions most head units negotiate. ~2MB. */
        private const val INITIAL_BUFFER_BYTES = Messages.DEF_BUFFER_LENGTH * 16

        /** Ceiling for [ensureCapacity]. ~8MB, which covers H.265 at 4K. */
        private const val MAX_BUFFER_BYTES = Messages.DEF_BUFFER_LENGTH * 64

        /** Spacing of the anomaly summary, matched to the decoder's throughput line so they interleave. */
        private const val ANOMALY_REPORT_INTERVAL_MS = 5000L
    }

    /**
     * Reassembly buffer for fragmented frames. Grows on demand.
     *
     * It used to be sized up front from settings.videoCodec, which is the user's preference and
     * not the codec the session runs: ServiceDiscoveryResponse negotiates H.265 at 1440p and 4K
     * whatever that setting says, and falls back to H.264 when the user asked for H.265 on a
     * device with no HEVC decoder. Either mismatch handed an H.265 stream the 2MB H.264 buffer and
     * turned every large frame into a fragment overflow. The real answer is also not available
     * here - AapVideo is constructed with the transport, before the phone has negotiated anything
     * - so start small and let a frame that does not fit be the thing that asks for more.
     */
    private var messageBuffer = ByteBuffer.allocate(INITIAL_BUFFER_BYTES)
    private var legacyAssembledBuffer: ByteArray? = null
    private var lastKeyframeRequestMs = 0L
    private var lastAnomalyReportMs = 0L

    /** Ordering rules for the fragment run. Pure and unit-tested; see [VideoFragmentAssembler]. */
    private val assembler = VideoFragmentAssembler()

    /** Every line the injector prints. Declared first: [faultInjector]'s initializer announces on it. */
    private val reporter = VideoFaultReporter("AapVideo")

    /**
     * Null unless the user has deliberately turned fault injection on, so the healthy path costs one
     * null check. See [VideoFaultInjector] for why this exists at all.
     */
    private val faultInjector: VideoFaultInjector? =
        VideoFaultInjector(
            settings.debugVideoFaultInjection,
            settings.debugVideoFaultRate,
            settings.debugVideoFaultBudget
        )
            // Stage-scoped, so a reader-stage mode is applied once in the reader and not a second
            // time here. See VideoFaultInjector.isActiveAt.
            .takeIf { it.isActiveAt(VideoFaultInjector.Stage.ASSEMBLER) }
            ?.also { reporter.announce(it) }

    /**
     * Asks the phone for a fresh keyframe because a frame was lost or arrived unusable.
     *
     * Every anomaly [VideoFragmentAssembler] reports means some later frame will predict from a
     * reference we never decoded, so the picture drifts - washed out and blocky, "melting" - until
     * a keyframe lands. On an idle stream the phone's own cadence is a fixed ~69s GOP, so waiting
     * for it costs about half a minute of broken picture; asking brings that down to seconds.
     *
     * Throttled by [VideoRecoveryPolicy] because the ask is not free: [AapTransport] implements it
     * as an unsolicited focus nudge, escalating to a real focus cycle only under much stricter
     * gates, and the phone rebuilds the stream in response.
     */
    /**
     * The reader's framing audit found a run short of the bytes its first fragment declared.
     *
     * This is the one corruption mode nothing downstream of the reader can see. A middle fragment
     * that never arrives leaves [VideoFragmentAssembler] looking at a first, some middles and a last
     * in order, so the frame is assembled with a hole and decoded as though it were whole - no
     * anomaly, no ask, and a picture that drifts until the phone's own keyframe cadence comes round.
     * [FragmentedMessageAudit] is the only thing that notices, and until now all it did was log.
     *
     * Runs on the poll thread, which is the thread [process] and every other keyframe request
     * already run on, so it shares their throttle and their ordering without any locking of its own.
     */
    fun onFragmentRunHoled(discardAssembledUnit: Boolean) {
        // One-shot, consumed by the very next process() call: the audit raises the finding on the
        // run's last fragment, and that same message is the next thing this class sees. Any other
        // action arriving instead means the run did not complete normally and the verdict is moot.
        if (discardAssembledUnit) discardNextAssembly = true
        requestKeyframe("fragment run lost bytes")
    }

    private var discardNextAssembly = false

    private fun requestKeyframe(reason: String) {
        // Above the throttle, on purpose: the throttle paces messages on the wire, and the
        // concealment window this opens is a local render decision with its own hard bound. A
        // second fault inside the throttle window must still hold the picture even though it
        // sends nothing.
        videoDecoder.noteStreamCorrupted(reason)
        val now = android.os.SystemClock.elapsedRealtime()
        if (VideoRecoveryPolicy.canRequestKeyframe(now, lastKeyframeRequestMs)) {
            lastKeyframeRequestMs = now
            AppLog.w("AapVideo: %s, requesting keyframe to recover stream", reason)
            onFrameCorrupted()
        }
    }

    /**
     * Logs, counts and reacts to one broken-stream event.
     *
     * The counters live on the assembler and are summarised periodically by [reportAnomalies]; the
     * per-event line is what tells a reporter's log *when* it happened relative to what they saw.
     */
    private fun handleAnomaly(anomaly: VideoFragmentAssembler.Anomaly, message: AapMessage) {
        when (anomaly) {
            VideoFragmentAssembler.Anomaly.TRUNCATED_PREVIOUS -> {
                // The previous frame never got its flag 10 and was dropped unassembled. Nothing bad
                // reached the codec, but a reference frame is missing all the same, which is why
                // this asks for a keyframe rather than only logging as it used to.
                AppLog.w("AapVideo: Previous frame was truncated! Resetting assembly state.")
                messageBuffer.clear()
                requestKeyframe("frame truncated")
            }
            VideoFragmentAssembler.Anomaly.ORPHANED_FRAGMENT -> {
                AppLog.e("AapVideo: Orphaned fragment (Flag ${message.flags.toInt()}) detected! Frame data lost.")
                messageBuffer.clear()
                requestKeyframe("orphaned fragment")
            }
            VideoFragmentAssembler.Anomaly.HEADLESS_FIRST_FRAGMENT -> {
                AppLog.w(
                    "AapVideo: First fragment has no start code at offset 10 or 2 (len=%d, first bytes %s). " +
                        "Discarding the frame instead of assembling it headless.",
                    message.size, firstBytesOf(message)
                )
                messageBuffer.clear()
                requestKeyframe("first fragment has no start code")
            }
            VideoFragmentAssembler.Anomaly.OVERFLOW -> {
                // Message logged at the overflow site, which knows which flag and how far in.
                messageBuffer.clear()
                requestKeyframe("frame exceeded the reassembly buffer")
            }
        }
    }

    /**
     * A periodic count of everything the stream got wrong, emitted only when something did.
     *
     * A line that appears only when the reassembler is unhappy is worth more in a bug report than a
     * field that reads zero on every healthy line, so this is its own summary rather than extra
     * columns on the decoder's throughput line.
     */
    private fun reportAnomalies() {
        if (!assembler.hasAnomalies()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAnomalyReportMs == 0L) {
            // Arm the window on the first event rather than reporting a zero-length one. A single
            // isolated anomaly is already covered by its own line above; the summary exists to show
            // a rate.
            lastAnomalyReportMs = now
            return
        }
        val elapsed = now - lastAnomalyReportMs
        if (elapsed < ANOMALY_REPORT_INTERVAL_MS) return
        lastAnomalyReportMs = now
        AppLog.w(
            "AapVideo: reassembly anomalies over %dms: truncated=%d, orphan=%d, headless=%d, overflow=%d",
            elapsed,
            assembler.countOf(VideoFragmentAssembler.Anomaly.TRUNCATED_PREVIOUS),
            assembler.countOf(VideoFragmentAssembler.Anomaly.ORPHANED_FRAGMENT),
            assembler.countOf(VideoFragmentAssembler.Anomaly.HEADLESS_FIRST_FRAGMENT),
            assembler.countOf(VideoFragmentAssembler.Anomaly.OVERFLOW)
        )
        assembler.resetCounts()
    }

    private fun firstBytesOf(message: AapMessage): String {
        val end = minOf(message.size, 8)
        val sb = StringBuilder(end * 3)
        for (i in 0 until end) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02x", message.data[i]))
        }
        return sb.toString()
    }

    /**
     * Makes room for [additionalBytes] more of the frame being assembled, growing [messageBuffer]
     * if it has to. Returns false only at [MAX_BUFFER_BYTES], where the frame is genuinely too
     * large to be one we can decode and the caller should invalidate it.
     */
    private fun ensureCapacity(additionalBytes: Int): Boolean {
        if (messageBuffer.remaining() >= additionalBytes) return true

        val needed = messageBuffer.position() + additionalBytes
        if (needed > MAX_BUFFER_BYTES) return false

        var capacity = messageBuffer.capacity()
        while (capacity < needed) capacity *= 2
        val grown = ByteBuffer.allocate(capacity.coerceAtMost(MAX_BUFFER_BYTES))
        grown.put(messageBuffer.array(), 0, messageBuffer.position())
        messageBuffer = grown
        AppLog.i("AapVideo: Reassembly buffer grown to ${grown.capacity() / 1024}KB")
        return true
    }

    /**
     * Length of the Annex B start code at [offset], or -1 if there is none.
     *
     * Bounded by [len] - the message's payload length - and not by `buf.size`. The two are only
     * equal because the SSL layer currently hands out an exactly-sized array per message; reading
     * to the end of the array would become a stale-data read the moment that buffer is pooled, and
     * a start code found in the previous message's leftovers would send a garbage frame to the
     * codec.
     */
    private fun findStartCode(buf: ByteArray, offset: Int, len: Int): Int {
        if (offset + 3 > len) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3 // 3-byte start code
            if (offset + 4 <= len && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4 // 4-byte start code
        }
        return -1
    }

    /** Whether a frame starts at [offset], i.e. there is a start code there with payload behind it. */
    private fun payloadStartsAt(buf: ByteArray, offset: Int, len: Int): Boolean {
        val startCodeLen = findStartCode(buf, offset, len)
        return startCodeLen > 0 && len > offset + startCodeLen
    }

    fun process(message: AapMessage): Boolean {
        val buf = message.data
        val len = message.size
        val flags = message.flags.toInt()

        val injector = faultInjector
        val fault = injector?.effectFor(flags) ?: VideoFaultInjector.Effect.NONE
        if (injector != null) {
            reporter.onMessage(injector, fault, flags, len)
            // A dropped message must not reach the assembler at all - that is the point. Reported as
            // consumed, because from the protocol's side it did arrive.
            if (fault == VideoFaultInjector.Effect.DROP) return true
        }
        val hideStartCode = fault == VideoFaultInjector.Effect.HIDE_START_CODE

        val decision = assembler.onMessage(
            flags = flags,
            payloadStartsAt10 = !hideStartCode &&
                payloadStartsAt(buf, VideoFragmentAssembler.OFFSET_TIMESTAMP_INDICATION, len),
            payloadStartsAt2 = !hideStartCode &&
                payloadStartsAt(buf, VideoFragmentAssembler.OFFSET_MEDIA_INDICATION, len)
        )

        decision.anomaly?.let { handleAnomaly(it, message) }
        reportAnomalies()

        // Consumed whatever the action turns out to be, so a verdict against a run that never
        // completes cannot linger and eat the following healthy frame.
        val discardCompletedAssembly = discardNextAssembly
        discardNextAssembly = false

        return when (val action = decision.action) {
            is VideoFragmentAssembler.Action.DecodeWhole -> {
                messageBuffer.clear()
                videoDecoder.decode(buf, action.payloadOffset, len - action.payloadOffset, settings.forceSoftwareDecoding, settings.videoCodec)
                true
            }

            is VideoFragmentAssembler.Action.BeginAssembly -> {
                messageBuffer.clear()
                val bytes = len - action.payloadOffset
                if (ensureCapacity(bytes)) {
                    messageBuffer.put(buf, action.payloadOffset, bytes)
                } else {
                    // A first fragment on its own larger than the ceiling. Used to throw a
                    // BufferOverflowException that the read loop swallowed, taking the rest of the
                    // frame's fragments with it and explaining nothing.
                    AppLog.e(
                        "AapVideo: First fragment overflow (Flag 9)! Size $bytes exceeds the " +
                            "${MAX_BUFFER_BYTES / 1024}KB cap. Invalidating frame."
                    )
                    handleAnomaly(assembler.onOverflow(), message)
                }
                true
            }

            VideoFragmentAssembler.Action.Append -> {
                if (ensureCapacity(len)) {
                    messageBuffer.put(buf, 0, len)
                } else {
                    AppLog.e(
                        "AapVideo: Fragment overflow (Flag 8)! Size $len on top of ${messageBuffer.position()} " +
                            "exceeds the ${MAX_BUFFER_BYTES / 1024}KB cap. Invalidating frame."
                    )
                    handleAnomaly(assembler.onOverflow(), message)
                }
                true
            }

            VideoFragmentAssembler.Action.AppendAndDecode -> {
                if (!ensureCapacity(len)) {
                    AppLog.e(
                        "AapVideo: Final fragment overflow (Flag 10)! Frame exceeds the " +
                            "${MAX_BUFFER_BYTES / 1024}KB cap. Invalidating frame."
                    )
                    handleAnomaly(assembler.onOverflow(), message)
                    return true
                }
                messageBuffer.put(buf, 0, len)
                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()

                if (discardCompletedAssembly) {
                    // The framing audit found this run short by at least a whole fragment. Decoding
                    // the unit anyway is what used to smear the picture with no anomaly and no
                    // second reporter; the concealment window the audit's ask opened is already
                    // holding the screen, so the gap this leaves is invisible.
                    AppLog.w(
                        "AapVideo: discarding a %d-byte access unit the framing audit found short",
                        assembledSize
                    )
                    messageBuffer.clear()
                    return true
                }

                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    videoDecoder.decode(legacyAssembledBuffer!!, 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                } else {
                    videoDecoder.decode(messageBuffer.array(), 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                }

                messageBuffer.clear()
                true
            }

            is VideoFragmentAssembler.Action.Discard -> {
                if (flags == VideoFragmentAssembler.FLAG_SINGLE) {
                    // A complete frame we cannot find the start of. Deliberately not an anomaly:
                    // the small ones seen in the wild (len=4, len=6 at session start) are control
                    // traffic on the video channel, not lost picture, and asking for a keyframe for
                    // each would fire during setup for no reason.
                    AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
                }
                action.consumed
            }
        }
    }

    fun release() {
        // Decoding is synchronous here, so there is nothing to drain - but the fragment run has to
        // be closed, or a session that ends mid-frame leaves the next one's first fragments looking
        // like a truncation.
        discardNextAssembly = false
        assembler.reset()
        lastAnomalyReportMs = 0L
    }
}
