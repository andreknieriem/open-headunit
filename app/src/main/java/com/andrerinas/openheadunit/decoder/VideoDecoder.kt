package com.andrerinas.openheadunit.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.openheadunit.aap.AuditReportPolicy
import com.andrerinas.openheadunit.aap.CodecConfigScanner
import com.andrerinas.openheadunit.aap.VideoKeyframeScanner
import com.andrerinas.openheadunit.aap.VideoRecoveryPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.LegacyOptimizer
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

/**
 * Main video decoding engine.
 * Handles H.264/H.265 streams via MediaCodec.
 *
 * [memoryReading] is resolved once at construction and reported with the codec configuration, so
 * that every bug report carries what the device actually has. Nothing is sized from it yet.
 */
class VideoDecoder(
    private val settings: Settings,
    private val memoryReading: DeviceMemoryReading = DeviceMemoryReading(
        DeviceMemoryProfile.NORMAL, totalRamMb = 0, heapLimitMb = 0, memoryClassMb = 0, systemLowRamFlag = false
    ),
) {
    companion object {
        private const val TIMEOUT_US = 10000L
        private const val MAX_RESTARTS_WITHOUT_FRAME = 3

        // sync_stall watchdog tuning. An intermittently slow decoder — one that renders, just not
        // within SYNC_STALL_THRESHOLD_MS — escapes restartsSinceLastFrame's cap, which counts only
        // restarts that produced no frame at all and resets the moment one renders. Without its own
        // cooldown and cap this watchdog would rebuild the MediaCodec indefinitely on marginal
        // hardware, the same failure mode
        // AapProjectionActivity.maybeRecoverFromDisplayStall() was hardened against.
        private const val SYNC_STALL_THRESHOLD_MS = 2000L
        private const val SYNC_STALL_COOLDOWN_MS = 8000L
        private const val SYNC_STALL_RESET_MS = 60000L
        private const val MAX_SYNC_STALL_RESTARTS = 4
        // First-frame window for a codec rebuilt mid-session. Measured on a UNISOC MT50: up to
        // ~8s from a mid-session reconfigure to the first frame on a session that then ran at
        // 50fps, so the 2s window above restarted codecs that were merely warming up - and each
        // restart resets the warm-up, which is how a healthy relaunch cascaded into the restart
        // budget. Applies only while the session has already rendered (the codec type is proven,
        // patience is cheap); a cold start keeps the 2s window so a genuinely dead component
        // still fails fast.
        private const val WARM_RECONFIGURE_FIRST_FRAME_GRACE_MS = 10000L

        // Interval of the decode/render throughput summary. This exists because a slow picture
        // is otherwise unattributable from a user-submitted log: the only rendered-frame count
        // the decoder keeps reaches the opt-in on-screen overlay and never the log, so "the
        // codec is slow" and "the codec keeps up and the display consumer drops the frames"
        // produce byte-identical logs. Reporting rendered alongside fed separates the two.
        private const val THROUGHPUT_LOG_INTERVAL_MS = 5000L

        // How many already-decoded frames the output thread may discard in one pass to reach the
        // newest one. Only frames the codec has *already* finished are eligible, so while it keeps
        // pace nothing is ever waiting and this never triggers; the bound exists purely so a codec
        // handing back a long backlog cannot hold the loop away from its stall checks.
        private const val MAX_CATCHUP_SKIPS = 8

        // How deep the queue between the transport and the codec is, and how long the feed thread
        // waits for the codec, both live in VideoFeedQueuePolicy - they are one decision and
        // drifting apart is what cost #830 its reference frames.
        private const val FEED_POLL_MS = 200L
        // Floor for pooled frame buffers, so the pool settles at a reusable size instead of
        // reallocating around whatever the first few frames happened to measure. Smaller on a
        // constrained device, where the floor alone accounts for a measurable slice of the heap:
        // 42 slots at 64KB is 2.7MB before a single real frame has been held.
        private const val MIN_POOLED_FRAME_BYTES = 64 * 1024
        private const val MIN_POOLED_FRAME_BYTES_CONSTRAINED = 16 * 1024

        /**
         * Soft ceiling on the bytes the frame pool may retain, on a constrained device only.
         *
         * The pool holds capacity+2 buffers and each grows to the largest frame it has ever held,
         * never shrinking, so a pool that has seen a few keyframes settles at tens of slots times
         * keyframe size - several megabytes on a device whose whole heap is twenty. This bounds that
         * without touching the queue depth, which is load-bearing (see VideoFeedQueuePolicy): a
         * buffer that would push the pool past the ceiling is simply not kept, and the next frame
         * that needs one allocates it again.
         *
         * 2MB holds every slot at the constrained floor with room left for several keyframe-sized
         * buffers, so the steady state stays pooled and only the largest ones churn.
         */
        private const val CONSTRAINED_POOL_BUDGET_BYTES = 2 * 1024 * 1024

        /** Spacing of the per-frame input-buffer-full report. The throughput line carries the rate. */
        private const val FEED_DROP_LOG_INTERVAL_MS = 1000L

        // Throttle for reporting a stall the watchdog saw but declined to act on. Once the
        // cooldown or the restart cap below suppresses a restart, that branch takes no action
        // and would otherwise print nothing, so a decoder degrading past its restart budget is
        // indistinguishable in the log from one that is running perfectly.
        private const val SYNC_STALL_SUPPRESSED_LOG_INTERVAL_MS = 10000L

        // Widest buffer alignment any decoder pads a picture dimension out to. Used to sanity
        // check a reported crop rectangle against the buffer geometry: a real crop is at most
        // this far below the buffer, so anything further off is not describing this stream.
        private const val MAX_ALIGNMENT_PADDING = 64

        // Bounds on a picture size read out of a parameter set. Android Auto projects between
        // 800x480 and 3840x2160; these are wide enough to accept anything a head unit could
        // plausibly be sent and narrow enough that a desynced parse is caught rather than
        // configured into the codec.
        private const val MIN_PLAUSIBLE_DIMENSION = 160
        private const val MAX_PLAUSIBLE_WIDTH = 7680
        private const val MAX_PLAUSIBLE_HEIGHT = 4320

        /**
         * Checks if H.265 (HEVC) hardware decoding is supported on the current device.
         */
        /**
         * Checks if H.265 (HEVC) hardware decoding is supported and reliable on the current device.
         * Used for AUTO codec selection.
         */
        fun isHevcReliable(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

            // 1. Chipset Reliability Check (from SystemOptimizer)
            val hw = Build.HARDWARE.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.lowercase()
            } else ""

            val isReliable = hw.startsWith("qcom") || hw.startsWith("msm") || // Qualcomm
                    hw.startsWith("exynos") || // Samsung
                    hw.startsWith("gs") || hw.contains("google") || // Google Tensor
                    soc.contains("qualcomm") || soc.contains("samsung") || soc.contains("google") ||
                    // High-end MediaTek (Dimensity 700/800/900/1000/9000+ series)
                    hw.startsWith("mt68") || hw.startsWith("mt69")

            if (!isReliable) return false

            return isHevcSupported()
        }

        /**
         * Checks if ANY H.265 (HEVC) hardware decoding is present, regardless of reliability.
         * Used for MANUAL codec selection (User override).
         */
        // Hardware HEVC support is fixed for a device, but the check scans the whole MediaCodecList,
        // so cache it: this is called on UI-thread paths (resolution/DPI recommendation).
        @Volatile
        private var cachedHwHevcSupported: Boolean? = null

        fun isHevcSupported(): Boolean {
            cachedHwHevcSupported?.let { return it }
            return isHevcDecoderAvailable(includeSoftware = false).also { cachedHwHevcSupported = it }
        }

        /**
         * Checks if H.265 (HEVC) decoding is present.
         *
         * Hardware-only is still the default because software HEVC has no real-time
         * performance guarantee. includeSoftware is used only for explicit user
         * overrides before falling back to bundled/native decoders.
         */
        fun isHevcDecoderAvailable(includeSoftware: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals("video/hevc", ignoreCase = true)) {
                        val name = info.name.lowercase()
                        val isSoftware = name.startsWith("omx.google.") ||
                                name.startsWith("c2.android.") ||
                                name.startsWith("omx.ffmpeg.") ||
                                name.contains(".sw.") ||
                                name.contains("software")

                        if (includeSoftware || !isSoftware) return true
                    }
                }
            }
            return false
        }

        fun isBundledHevcDecoderAvailable(): Boolean {
            return FfmpegHevcDecoder.isAvailable()
        }
    }

    private var codec: MediaCodec? = null
    private var softwareHevcDecoder: FfmpegHevcDecoder? = null
    private var codecBufferInfo: MediaCodec.BufferInfo? = null
    private var mSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var running = false
    private var startTime = 0L

    private var mWidth = 0
    private var mHeight = 0
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var codecConfigured = false
    private var currentCodecType = CodecType.H264
    private var currentCodecName: String? = null

    // Once a codec type has been used to successfully start a decoder this session, it is
    // "pinned": restarts reuse it directly instead of re-running detectCodecType() on
    // whatever raw packet happens to be arriving at that instant. detectCodecType()'s NAL-type
    // heuristic can misclassify an ordinary H.264 slice byte as an HEVC VPS/SPS/PPS NAL
    // (e.g. nal_unit_type=1/nal_ref_idc=2 -> byte 0x41 -> 0x41 >> 1 == 32), and re-detecting on
    // every restart let that false positive hijack an otherwise-working H.264 session.
    private var codecTypePinned = false
    // One-shot per session: the parameter sets are re-sent on every keyframe, and the fields we
    // want out of them do not change mid-stream, so logging them once keeps the diagnostic out of
    // the hot path while still putting it in every bug report.
    private var loggedParameterSet = false
    // One-shot per codec start: what we asked KEY_MAX_INPUT_SIZE for, and what the component
    // actually handed back. #839 measured a 2MB request answered with eight buffers of that size,
    // 16MB of graphics memory for input alone on a 1GB unit, and nothing in our own log said so.
    private var requestedMaxInputSize = 0
    private var loggedInputBufferCapacity = false
    private var loggedDecoderCapability = false

    /**
     * What the component claimed at configure time, kept so the backpressure line can quote it.
     *
     * "The codec is the bottleneck" and "the codec said it could do this" are only worth anything
     * together: the first without the second reads as a slow device, and the pair says the profile
     * we negotiated was never one this hardware could carry.
     */
    private var decoderCapability: DecoderCapabilityReport.Capability? = null

    /** Windows that both shed frames and spent a large share waiting - see [VideoBackpressurePolicy]. */
    private var backpressureWindows = 0
    private var reportedBackpressure = false
    private var restartsSinceLastFrame = 0
    private var codecFallbackUsed = false
    private var decoderPermanentlyFailed = false
    // True once any frame of this session has rendered. Session-scoped where lastFrameRenderedMs
    // is start-scoped: every stop() zeroes that timestamp, so after a surface swap a stream that
    // had rendered for an hour looks identical to one that never worked. This flag is what lets
    // the restart ladder tell them apart, and it clears only when the session itself ends.
    // Written by the output thread and the software render path, read on the transport thread.
    @Volatile private var renderedThisSession = false

    // sync_stall cooldown/cap state - see SYNC_STALL_* constants.
    private var syncStallRestartCount = 0
    private var lastSyncStallRestartMs = 0L
    private var lastSyncStallSuppressedLogMs = 0L

    // Throughput counters for the periodic telemetry tick - see THROUGHPUT_LOG_INTERVAL_MS.
    // Session-monotonic so each has exactly one writer: framesFed/inputWaitMs from the feed
    // thread, framesRendered/framesSkippedAtRender from the output thread. framesDropped is the
    // exception - both the feed thread (codec would not take the frame) and decode() (queue full)
    // count into it, and they are ordered by the queue rather than by a lock. It drives a log
    // line, so a lost increment costs a digit in a report and nothing else. The output thread
    // reads them all and keeps its own last-logged snapshots to derive per-interval deltas.
    @Volatile private var framesFed = 0L
    @Volatile private var framesDropped = 0L
    @Volatile private var framesRendered = 0L
    // Decoded frames discarded unshown to reach a newer one. Distinct from framesDropped, which
    // counts frames that never reached the decoder at all: these cost no picture quality, only
    // the motion they would have shown, and a non-zero count is the link arriving in bursts.
    @Volatile private var framesSkippedAtRender = 0L
    // Milliseconds spent waiting for a free input buffer, on the feed thread. Otherwise invisible,
    // since a frame that waits some of its attempts logs nothing and "Input buffer full" only fires
    // when every attempt is exhausted. Near zero while the frame rate is low means the frames were
    // never sent; high means frames are arriving faster than the codec drains them, which on a
    // healthy link is the signature of a burst after the link went quiet.
    @Volatile private var inputWaitMs = 0L
    private var lastThroughputLogMs = 0L
    private var lastLoggedFramesFed = 0L
    private var lastLoggedFramesDropped = 0L
    private var lastLoggedFramesRendered = 0L
    private var lastLoggedFramesSkippedAtRender = 0L
    private var lastLoggedInputWaitMs = 0L

    // Encoded frames waiting to be handed to the codec, and the thread that hands them over.
    //
    // This queue exists to keep the wait for a free codec input buffer off the transport's read
    // thread. That thread carries every channel - video, audio, microphone, control - so while it
    // sat inside dequeueInputBuffer nothing else on the link was dispatched and the socket went
    // undrained, which turned a busy decoder into stalled audio and late keepalives. Frames are
    // copied on the way in because the transport reuses the buffer it hands us.
    private class PendingFrame(var data: ByteArray, var size: Int, var arrivalNanos: Long)
    // Derived once per decoder rather than per session: the queue is allocated here, and fpsLimit
    // only changes when the user changes it, which restarts the service that owns this object.
    private val frameQueueCapacity = VideoFeedQueuePolicy.capacityFor(settings.fpsLimit)
    private val frameQueue = ArrayBlockingQueue<PendingFrame>(frameQueueCapacity)
    private val framePool = ArrayBlockingQueue<PendingFrame>(frameQueueCapacity + 2)

    private val constrained = memoryReading.profile == DeviceMemoryProfile.CONSTRAINED
    private val minPooledFrameBytes =
        if (constrained) MIN_POOLED_FRAME_BYTES_CONSTRAINED else MIN_POOLED_FRAME_BYTES
    private val pooledByteBudget = if (constrained) CONSTRAINED_POOL_BUDGET_BYTES else Int.MAX_VALUE

    /**
     * Bytes currently held by [framePool]. Atomic because frames are returned from both the feed
     * thread and the transport thread, and the ceiling it guards is only useful if it is not raced
     * away.
     */
    private val pooledBytes = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Last time a per-frame drop was reported, and how many were suppressed since.
     *
     * The two drop lines below used to print once per frame, which on a saturated link is 30-60 a
     * second - and every AppLog line builds a Throwable and walks its stack to derive the caller,
     * so on a device already collecting every five seconds the reporting costs more than the thing
     * being reported. The counters in the throughput line carry the rate; these lines only need to
     * say it is happening.
     */
    private var lastFeedDropLogMs = 0L
    private var suppressedFeedDropLogs = 0
    // Volatile and identity-checked by the loop itself: interrupt() does not abort a MediaCodec
    // call, so a feed thread parked in dequeueInputBuffer can outlive the join() below. If it
    // does, stop() goes on to release the codec and a later start() sets running back to true -
    // at which point a loop that only tested running would wake up and feed the *new* codec
    // alongside the new thread, interleaving two frames into one input queue and inverting decode
    // order. Testing identity as well means an outlived thread can only ever exit.
    @Volatile private var feedThread: Thread? = null

    // Scratch for the output thread's catch-up pass. Owned by that thread alone.
    private val readyIndices = IntArray(MAX_CATCHUP_SKIPS + 2)

    // Reuse buffers for older API levels to minimize GC pressure
    private var inputBuffers: Array<ByteBuffer>? = null
    private var legacyFrameBuffer: ByteArray? = null

    var dimensionsListener: VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    var softwareYuvFrameSink: SoftwareYuvFrameSink? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    private var loggedFirstSoftwareFrame = false
    private var loggedFirstHardwareFrame = false
    @Volatile var onFirstFrameListener: (() -> Unit)? = null
    @Volatile var lastFrameRenderedMs: Long = 0L

    // Frames rendered since whoever owns the session last zeroed this. Deliberately *not* cleared
    // by stop(), which is what separates it from framesRendered above: that one is a throughput
    // counter and must not straddle a restart, this one must survive every restart the session
    // contains. The surface goes away and comes back within a single session (leaving projection,
    // screen off, a config change), and both stop() and setSurface() zero lastFrameRenderedMs when
    // it does, so reading that to ask "did this session ever show video" answers no for a session
    // that showed plenty. See CommManager.noteSessionEnded.
    @Volatile var framesRenderedThisSession: Long = 0L

    // elapsedRealtime() of the last encoded video bytes received from the phone (input side),
    // as opposed to lastFrameRenderedMs (output side). Lets the projection watchdog tell a
    // phone-side pause (no input) apart from a local display stall (input flowing, nothing drawn).
    @Volatile var lastInputBytesReceivedMs: Long = 0L

    // True while the bundled software HEVC decoder is active. That path renders through the
    // GLES YUV sink, so the projection watchdog must not fall back to a non-GLES backend.
    val usingBundledSoftwareHevc: Boolean get() = softwareHevcDecoder != null

    @Volatile private var decoderNeedsRestart = false
    @Volatile private var decoderRestartReason: String? = null

    // Callback for transport layer integration. Carries the restart reason because the transport
    // answers a decoder rebuild by asking the phone for a keyframe, and only some rebuilds are its
    // to answer - see AapTransport's wiring of it.
    var onDecoderError: ((reason: String) -> Unit)? = null

    // Fired, throttled, while the codec is waiting for a keyframe it has no way to ask for itself.
    // Separate from onDecoderError because the whole point of the starved case is that nothing is
    // being rebuilt: the codec is fine and the stream has nothing it can decode yet.
    var onKeyframeStarved: (() -> Unit)? = null

    // Fired, throttled, when a frame is shed with the picture live - see notifyFrameDropped().
    // The transport wires it to the same gain-only keyframe nudge corrupt-frame recovery uses.
    var onFrameDropped: (() -> Unit)? = null

    // Fired when a keyframe has produced a picture, which is the only evidence a shed reference
    // frame has been repaired. Driven from the output side rather than the arrival or the feed: a
    // keyframe the queue sheds never reaches the codec, and one whose access unit lost a fragment
    // on the way reaches it and decodes to nothing. Neither repairs anything, and both used to
    // count - see [KeyframeRepairTracker].
    var onKeyframeObserved: (() -> Unit)? = null

    // Whether a keyframe has produced a picture on *this* codec instance. A codec rebuilt
    // mid-session resumes on a P-frame and can produce nothing until an IDR arrives, so this is what
    // separates "the decoder is broken" from "the decoder has been given nothing it could decode" -
    // and it counts the output rather than the feed, because a keyframe that arrived with a hole in
    // it is fed like any other and decodes to nothing. See [KeyframeRepairTracker].
    private val keyframeRepair = KeyframeRepairTracker()

    // --- Corruption concealment - see CorruptionConcealmentPolicy for the design and its bounds.

    /**
     * When the transport last reported a lost or damaged access unit, and what it called it.
     * Single writer (the transport's poll thread raises every reassembly anomaly and every
     * framing-audit finding) and single reader (the output thread), so a volatile stamp is the
     * whole synchronisation. If a second reporting thread ever appears, this needs more.
     */
    @Volatile private var corruptionReportedMs = 0L
    @Volatile private var lastCorruptionReason = ""

    // Output-thread confined, except that resetConcealment() runs from stop()/setSurface() after
    // the output thread has been joined. framesConcealed is on the throughput line.
    private var concealState = CorruptionConcealmentPolicy.State.ARMED
    private var concealWindowOpenedMs = 0L
    private var consumedCorruptionMs = 0L
    private var framesConcealed = 0L
    private var lastLoggedFramesConcealed = 0L
    private var concealReports = 0
    private var concealLastLogMs = 0L
    private var concealSuppressed = 0

    /**
     * The stream lost an access unit; the output thread holds the last good frame on screen until
     * a keyframe repairs the picture, bounded by [CorruptionConcealmentPolicy.CONCEAL_MAX_MS].
     *
     * Deliberately unthrottled: the window it opens carries its own hard bound, and the 1s
     * throttle the callers sit behind exists to pace messages on the wire, not renders - which is
     * why they call this *above* that throttle, so a report the throttle swallows still conceals.
     */
    fun noteStreamCorrupted(reason: String) {
        lastCorruptionReason = reason
        corruptionReportedMs = SystemClock.elapsedRealtime()
    }

    /**
     * Back to [CorruptionConcealmentPolicy.State.ARMED] with nothing pending. Any report already
     * stamped is marked consumed: it described a stream state the codec or surface being reset no
     * longer carries, and opening a window on the new one for it would freeze a healthy picture.
     */
    private fun resetConcealment() {
        concealState = CorruptionConcealmentPolicy.State.ARMED
        concealWindowOpenedMs = 0L
        consumedCorruptionMs = corruptionReportedMs
        framesConcealed = 0L
        lastLoggedFramesConcealed = 0L
        concealReports = 0
        concealLastLogMs = 0L
        concealSuppressed = 0
    }

    /** Applies what [CorruptionConcealmentPolicy.next] decided, and narrates the window edges. */
    private fun applyConcealmentTransition(
        outcome: CorruptionConcealmentPolicy.Outcome,
        nowMs: Long,
    ) {
        when (outcome) {
            CorruptionConcealmentPolicy.Outcome.OPEN -> {
                concealState = CorruptionConcealmentPolicy.State.CONCEALING
                concealWindowOpenedMs = nowMs
                logConcealment(
                    nowMs,
                    "VideoDecoder: holding the picture after $lastCorruptionReason - the last " +
                        "good frame stays up until a keyframe"
                )
            }
            CorruptionConcealmentPolicy.Outcome.CLOSE_REPAIRED -> {
                concealState = CorruptionConcealmentPolicy.State.ARMED
                logConcealment(
                    nowMs,
                    "VideoDecoder: picture restored ${nowMs - concealWindowOpenedMs}ms after " +
                        "$lastCorruptionReason (keyframe decoded)"
                )
                concealWindowOpenedMs = 0L
            }
            CorruptionConcealmentPolicy.Outcome.CLOSE_EXPIRED -> {
                concealState = CorruptionConcealmentPolicy.State.DISARMED
                logConcealment(
                    nowMs,
                    "VideoDecoder: no keyframe within " +
                        "${CorruptionConcealmentPolicy.CONCEAL_MAX_MS}ms of " +
                        "$lastCorruptionReason - resuming on the damaged stream"
                )
                concealWindowOpenedMs = 0L
            }
            CorruptionConcealmentPolicy.Outcome.REARM ->
                concealState = CorruptionConcealmentPolicy.State.ARMED
            CorruptionConcealmentPolicy.Outcome.SHOW,
            CorruptionConcealmentPolicy.Outcome.CONCEAL -> {}
        }
    }

    /**
     * Window-edge lines at WARN, because reporter captures run there, through the same refilling
     * budget every other repeating diagnostic uses. The edges are structurally rare - a window
     * cannot reopen until a keyframe has re-armed the policy - so the budget exists for the
     * pathological stream, not the expected one.
     */
    private fun logConcealment(nowMs: Long, message: String) {
        if (AuditReportPolicy.shouldReport(concealReports, concealLastLogMs, nowMs)) {
            val suppressed = concealSuppressed
            concealSuppressed = 0
            concealReports++
            concealLastLogMs = nowMs
            val suffix =
                if (suppressed > 0) " (and $suppressed window events since the last report)" else ""
            AppLog.w(message + suffix)
        } else {
            concealSuppressed++
        }
    }

    // Throttle stamp for onFrameDropped. Both drop sites feed it: the transport read thread on
    // queue overflow and the feed thread on input-buffer exhaustion. A race between them costs
    // at most one extra nudge, so a volatile check-then-set is enough.
    @Volatile private var lastDropKeyframeRequestMs = 0L

    /** Throttle stamp for [onKeyframeStarved], on the output thread only. */
    private var lastKeyframeStarvedAskMs = 0L

    /** Said once per process: this component's output timestamps mean nothing. Not session-scoped,
     * because it is a property of the decoder rather than of the stream. */
    private var loggedUnusableOutputTimestamps = false

    /** Diagnostic only - see [ParameterSetTracker]. Feed-thread confined, like the scan that feeds it. */
    private val parameterSetTracker = ParameterSetTracker()

    /** Config-scanner answers already reported this session, so each is said once. */
    private val loggedContentKinds = HashSet<CodecConfigScanner.Content>()

    val videoWidth: Int get() = mWidth
    val videoHeight: Int get() = mHeight

    /**
     * Whether any frame of the current session has rendered. Distinguishes a decoder rebuilt under a
     * live, proven stream from one that has never worked, which [lastFrameRenderedMs] cannot: every
     * stop() zeroes that timestamp.
     */
    val hasRenderedThisSession: Boolean get() = renderedThisSession

    enum class CodecType(val mimeType: String, val displayName: String, val settingsValue: String) {
        H264("video/avc", "H.264/AVC", "H.264"),
        H265("video/hevc", "H.265/HEVC", "H.265")
    }

    /**
     * The visible picture size described by a decoder output format.
     *
     * KEY_WIDTH/KEY_HEIGHT carry the buffer geometry, which vendors are free to pad out to their
     * macroblock alignment - Intel's AVC component reports 736 for a 720-line stream. Taking that
     * verbatim propagates a video size the stream does not have: the projection view scales to an
     * aspect ratio that is off by the padding, and a later restart reconfigures MediaCodec for the
     * padded height. The crop rectangle is the authoritative visible region, so prefer it and fall
     * back to the buffer geometry only where a decoder omits it.
     */
    private fun displaySizeOf(format: MediaFormat): Pair<Int, Int> {
        fun key(name: String): Int? =
            try { if (format.containsKey(name)) format.getInteger(name) else null } catch (e: Exception) { null }

        val bufferWidth = key(MediaFormat.KEY_WIDTH) ?: mWidth
        val bufferHeight = key(MediaFormat.KEY_HEIGHT) ?: mHeight

        // Inclusive bounds, so the visible extent is right - left + 1.
        val cropLeft = key("crop-left")
        val cropRight = key("crop-right")
        val cropTop = key("crop-top")
        val cropBottom = key("crop-bottom")

        val cropWidth = if (cropLeft != null && cropRight != null) cropRight - cropLeft + 1 else 0
        val cropHeight = if (cropTop != null && cropBottom != null) cropBottom - cropTop + 1 else 0

        return croppedOr(cropWidth, bufferWidth) to croppedOr(cropHeight, bufferHeight)
    }

    /**
     * [crop] if it is a plausible visible extent for a buffer of [buffer] pixels, else [buffer].
     *
     * Padding only ever rounds a dimension *up* to an alignment boundary, and no decoder aligns
     * more coarsely than [MAX_ALIGNMENT_PADDING], so a trustworthy crop sits just below the buffer
     * size. Anything further away is a decoder filling the crop keys with placeholders rather than
     * describing this stream - zeros are the dangerous case, since a 0..0 crop reads as a legal
     * 1-pixel extent and would otherwise be propagated as the video size and handed to
     * MediaCodec on the next restart.
     */
    private fun croppedOr(crop: Int, buffer: Int): Int =
        if (buffer > 0 && crop in (buffer - MAX_ALIGNMENT_PADDING)..buffer) crop else buffer

    /**
     * Handles dynamic video dimension changes during the session.
     */
    private fun handleOutputFormatChange(format: MediaFormat) {
        AppLog.i("Output Format Changed: $format")
        val (newWidth, newHeight) = displaySizeOf(format)
        if (mWidth != newWidth || mHeight != newHeight) {
            AppLog.i("Video dimensions changed via format: ${newWidth}x$newHeight")
            mWidth = newWidth
            mHeight = newHeight
            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        }
        try {
            codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        } catch (e: Exception) {}
    }

    /**
     * Sets the rendering surface and restarts the decoder if necessary.
     */
    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (mSurface === surface) return

            AppLog.i("New surface set: $surface")
            // Unconditional, not gated on a live codec: stop() is where the failure state
            // (decoderPermanentlyFailed, the restart counters, the one-time fallback) is reset,
            // and the failure paths release the codec on their way down - so gating this on
            // codec != null made a latched failure permanent for the rest of the session, with a
            // fresh surface arriving and nothing ever clearing the flag that blocks decode().
            // stop() is idempotent when nothing is running.
            stop(DecoderStopPolicy.REASON_NEW_SURFACE)
            mSurface = surface
            lastFrameRenderedMs = 0L
            keyframeRepair.reset()
            resetConcealment()
        }
    }

    /**
     * True when [surface] is the surface this decoder currently renders to.
     *
     * Identity comparison is deliberate: every teardown path hands back the exact Surface object
     * it created, so `===` distinguishes a live owner from a torn-down view whose callback is
     * arriving late.
     */
    fun isCurrentSurface(surface: Surface): Boolean = synchronized(this) { mSurface === surface }

    /**
     * Stops the decoder only if [surface] still owns it. Compare-and-stop is atomic under the
     * same monitor [setSurface] and [stop] already hold, so a stale teardown from a torn-down
     * view can never stop a decoder that a newer surface has since claimed. [mSurface] is left
     * as it is either way — [stop] never cleared it, and [decode] guards on validity.
     *
     * @return whether the decoder was actually stopped.
     */
    fun stopIfCurrentSurface(surface: Surface, reason: String): Boolean = synchronized(this) {
        if (mSurface !== surface) {
            AppLog.i("Decoder stop ($reason) skipped: surface is no longer current")
            return false
        }
        stop(reason)
        true
    }

    /**
     * Stops the decoder, terminates the output thread, and releases hardware resources.
     */
    fun stop(reason: String = "unknown") {
        synchronized(this) {
            running = false
            try {
                // If calling from output thread, don't join itself to avoid deadlock
                if (outputThread != null && outputThread != Thread.currentThread()) {
                    outputThread?.interrupt()
                    outputThread?.join(500)
                }
            } catch (e: Exception) {}
            outputThread = null

            // The join budget must exceed the longest MediaCodec call the feed thread can be
            // inside, or this falls through to codec.release() while the thread is still in the
            // codec - interrupt() does not abort a MediaCodec call, and calling into a released
            // codec wedges some vendor components until the process dies. The feed loop checks
            // running (cleared above) on every 10ms dequeue, so in practice it exits within one
            // dequeue; 500ms covers the worst case with margin. The identity check against
            // feedThread remains the backstop for a thread that overruns even this: it can only
            // exit, never feed the next session's codec.
            try {
                if (feedThread != null && feedThread != Thread.currentThread()) {
                    feedThread?.interrupt()
                    feedThread?.join(500)
                }
            } catch (e: Exception) {}
            feedThread = null
            clearFrameQueue()
            // Pooled frames grow to the largest ever seen and never shrink, so a 4K session would
            // otherwise pin that size for the life of the process.
            framePool.clear()
            pooledBytes.set(0)

            try {
                codec?.stop()
            } catch (e: Exception) {}
            try {
                codec?.release()
            } catch (e: Exception) {
                AppLog.e("Error releasing decoder", e)
            }
            try {
                softwareHevcDecoder?.stop()
            } catch (e: Exception) {
                AppLog.e("Error releasing software HEVC decoder", e)
            }

            codec = null
            softwareHevcDecoder = null
            inputBuffers = null
            legacyFrameBuffer = null
            codecBufferInfo = null
            if (!DecoderStopPolicy.isDecoderRestart(reason)) {
                restartsSinceLastFrame = 0
                codecFallbackUsed = false
                decoderPermanentlyFailed = false
                syncStallRestartCount = 0
                lastSyncStallRestartMs = 0L
            }
            // Everything that describes the *stream* rather than this decoder instance survives
            // anything short of the session ending, and only a real disconnect can change what the
            // phone is sending.
            //
            // The pinned codec type has to outlive a surface teardown because re-detecting on
            // whatever packet lands mid-teardown can misread an ordinary H.264 P-slice as HEVC and
            // configure the wrong decoder for the rest of the session. The parameter sets and the
            // dimensions parsed out of them are the same kind of fact: dropping them left the
            // rebuilt codec configured from HeadUnitScreenConfig's negotiated size with no csd-0 at
            // all, which is what the "Fallback to negotiated dimensions" line reports - measured
            // once per relaunch, on every backend, in every capture of the video-black round 6.
            if (DecoderStopPolicy.endsSession(reason)) {
                codecTypePinned = false
                renderedThisSession = false
                vps = null
                sps = null
                pps = null
                mWidth = 0
                mHeight = 0
                codecConfigured = false
                loggedParameterSet = false
                // Session-scoped, like everything else in this block: a restart does not change what
                // the phone is sending, so a change reported across one would be reporting the
                // tracker's own amnesia.
                parameterSetTracker.reset()
                loggedContentKinds.clear()
            }
            lastFrameRenderedMs = 0L
            // Presentation timestamps restart near zero on the next configure, so a stamp left
            // pending here would be confirmed by the new codec's very first frame.
            keyframeRepair.reset()
            resetConcealment()
            lastKeyframeStarvedAskMs = 0L
            loggedFirstSoftwareFrame = false
            loggedFirstHardwareFrame = false
            loggedInputBufferCapacity = false
            loggedDecoderCapability = false
            decoderCapability = null
            backpressureWindows = 0
            reportedBackpressure = false
            // The FPS window and the throughput counters must not straddle a restart, or the
            // first sample afterwards is averaged over the whole teardown and reads near zero.
            frameCount = 0
            lastFpsLogTime = 0L
            framesFed = 0L
            framesDropped = 0L
            framesRendered = 0L
            framesSkippedAtRender = 0L
            inputWaitMs = 0L
            lastDropKeyframeRequestMs = 0L
            lastThroughputLogMs = 0L
            lastLoggedFramesFed = 0L
            lastLoggedFramesDropped = 0L
            lastLoggedFramesRendered = 0L
            lastLoggedFramesSkippedAtRender = 0L
            lastLoggedInputWaitMs = 0L
            AppLog.i("Decoder stopped: $reason")
        }
    }

    private fun scheduleRestart(reason: String) {
        decoderRestartReason = reason
        decoderNeedsRestart = true
    }

    /**
     * Main entry point for decoding a video/control packet.
     *
     * Reports nothing back to the caller. A frame this cannot place into the codec's input queue
     * is lost here, and once the picture is live the loss asks the phone for a keyframe - see
     * [notifyFrameDropped] for the shape of that ask and why it stays silent before the first
     * rendered frame.
     */
    fun decode(buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, codecName: String) {
        synchronized(this) {
            // Input-side liveness: bytes are arriving from the phone right now.
            lastInputBytesReceivedMs = SystemClock.elapsedRealtime()

            // Check if a restart was requested by output thread
            if (decoderNeedsRestart) {
                AppLog.w("Decoder restart requested: $decoderRestartReason")

                // Track restarts that never produced a single frame for the currently pinned
                // codec type. A genuinely broken hardware decoder (e.g. an MTK HEVC component
                // that can't configure at all) will keep failing here forever otherwise. A
                // session that has already rendered is excluded: the codec type is proven for
                // this stream, so restarts after a surface swap are warm-up churn, and counting
                // them walked healthy sessions through the flip and into the permanent latch.
                if (DecoderRestartPolicy.countsTowardFailure(
                        codecTypePinned,
                        renderedSinceLastStart = lastFrameRenderedMs != 0L,
                        renderedThisSession = renderedThisSession
                    )
                ) {
                    restartsSinceLastFrame++
                    if (restartsSinceLastFrame >= MAX_RESTARTS_WITHOUT_FRAME) {
                        if (!codecFallbackUsed) {
                            val fallbackType = if (currentCodecType == CodecType.H264) CodecType.H265 else CodecType.H264
                            // Don't burn the one-time fallback on a codec type the device has no
                            // decoder for at all (hw or sw) - e.g. HEVC on a pre-Lollipop device
                            // with zero HEVC components. That flip is guaranteed to fail again.
                            if (findBestCodec(fallbackType.mimeType, true) == null) {
                                AppLog.e("${currentCodecType.displayName} failed $restartsSinceLastFrame times in a row without rendering a frame, and this device has no decoder for ${fallbackType.displayName} either. Giving up to avoid an infinite restart loop.")
                                decoderPermanentlyFailed = true
                            } else {
                                AppLog.e("${currentCodecType.displayName} failed $restartsSinceLastFrame times in a row without rendering a frame. Falling back to ${fallbackType.displayName}.")
                                currentCodecType = fallbackType
                                codecFallbackUsed = true
                                restartsSinceLastFrame = 0
                            }
                        } else {
                            AppLog.e("Both codec types failed to render a frame this session. Giving up to avoid an infinite restart loop.")
                            decoderPermanentlyFailed = true
                        }
                    }
                } else if (lastFrameRenderedMs != 0L || renderedThisSession) {
                    // Decoder was healthy before this restart, or the session has already proven
                    // the codec type; don't count it against the pin.
                    restartsSinceLastFrame = 0
                }

                val restartReason = decoderRestartReason ?: "unknown"
                stop("restart: $restartReason")
                decoderNeedsRestart = false
                decoderRestartReason = null
                onDecoderError?.invoke(restartReason)
            }

            if (decoderPermanentlyFailed) return

            // Buffer management for backward compatibility
            // Modern devices (API 21+) use the original buffer with offset/size to avoid GC pressure.
            val frameData: ByteArray
            val frameOffset: Int
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                if (legacyFrameBuffer == null || legacyFrameBuffer!!.size < size) {
                    legacyFrameBuffer = ByteArray(size + 1024)
                }
                System.arraycopy(buffer, offset, legacyFrameBuffer!!, 0, size)
                frameData = legacyFrameBuffer!!
                frameOffset = 0
            } else {
                frameData = buffer
                frameOffset = offset
            }


            // Initialization phase: detect codec and configuration (SPS/PPS).
            // Only runs the detection heuristic on the very first init of the session; once a
            // codec type is pinned (see codecTypePinned), restarts reuse it directly instead of
            // re-sniffing arbitrary mid-stream bytes.
            if (codec == null && softwareHevcDecoder == null) {
                val typeToUse = if (codecTypePinned) {
                    currentCodecType
                } else {
                    val detectedType = detectCodecType(frameData, frameOffset, size)
                    val requestedType = if (codecName.contains("265")) CodecType.H265 else CodecType.H264
                    val hevcDetectable = isHevcSupported()
                    val hevcUsable = hevcDetectable || bundledHevcSelected()
                    val selected = CodecTypeSelectionPolicy.select(
                        detected = detectedType,
                        requested = requestedType,
                        hevcDetectable = hevcDetectable,
                        hevcUsable = hevcUsable,
                    )
                    if (selected != requestedType) {
                        AppLog.i(
                            "Building a $selected decoder although the setting asks for " +
                                "$requestedType (stream says ${detectedType ?: "nothing yet"}, " +
                                "HEVC detectable=$hevcDetectable usable=$hevcUsable)"
                        )
                    }
                    selected
                }
                currentCodecType = typeToUse

                if (!codecConfigured) {
                    scanAndApplyConfig(frameData, frameOffset, size, typeToUse)

                    if (mWidth == 0) {
                         // Fallback dimensions if SPS/PPS parsing fails or is missing
                         val negotiatedW = HeadUnitScreenConfig.getNegotiatedWidth()
                         val negotiatedH = HeadUnitScreenConfig.getNegotiatedHeight()
                         if (negotiatedW > 0 && negotiatedH > 0) {
                             AppLog.i("Fallback to negotiated dimensions: ${negotiatedW}x${negotiatedH}")
                             mWidth = negotiatedW
                             mHeight = negotiatedH
                             dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                         }
                    }
                }

                if (mSurface == null || !mSurface!!.isValid) return
                if (mWidth == 0 || mHeight == 0) return

                if (shouldUseBundledHevc(typeToUse, settings.forceSoftwareDecoding || forceSoftware)) {
                    startBundledHevc(mWidth, mHeight)
                } else {
                    start(typeToUse.mimeType, settings.forceSoftwareDecoding || forceSoftware, mWidth, mHeight)
                }
            }

            softwareHevcDecoder?.let { decoder ->
                val renderedFrames = decoder.decode(frameData, frameOffset, size)
                if (renderedFrames > 0) {
                    onSoftwareFramesRendered(renderedFrames)
                } else if (renderedFrames < 0) {
                    AppLog.e("Bundled HEVC decoder failed with code $renderedFrames")
                    scheduleRestart("software_hevc_error_$renderedFrames")
                }
                return
            }

            if (codec == null) return

            enqueueForFeed(frameData, frameOffset, size)
        }
    }

    /**
     * Copies a frame onto the feed queue. Returns immediately in every case - the caller is the
     * transport's read thread and must get back to the socket.
     */
    private fun enqueueForFeed(frameData: ByteArray, frameOffset: Int, size: Int) {
        // An empty frame reaches the codec as nothing at all, so queueing it would only inflate
        // the fed count that the throughput line uses to say whether frames arrived.
        if (size <= 0) return

        val frame = borrowFrame(size)

        System.arraycopy(frameData, frameOffset, frame.data, 0, size)
        frame.size = size
        // Stamped here, not where the frame is handed over: the feed thread drains a backlog in
        // a few milliseconds, so timestamps taken there would give a dozen frames near-identical
        // values and flatten the cadence the codec sees.
        frame.arrivalNanos = System.nanoTime()

        if (!frameQueue.offer(frame)) {
            // The codec has not taken a frame for as long as this queue holds. Drop the one that
            // just arrived rather than something already queued: the older frames are what
            // everything after them is decoded against, so dropping forward costs one frame while
            // dropping backward corrupts every frame that referenced it until the next keyframe.
            // The frame shed here is still a reference for what follows it, though, so ask for
            // that keyframe rather than waiting out the phone's own cadence - see
            // notifyFrameDropped().
            recycleFrame(frame)
            notifyFrameDropped()
        }
    }

    /** Takes a buffer from the pool, growing it if this frame does not fit, or allocates one. */
    private fun borrowFrame(size: Int): PendingFrame {
        val pooled = framePool.poll()
        if (pooled != null) {
            pooledBytes.addAndGet(-pooled.data.size)
            if (pooled.data.size < size) pooled.data = ByteArray(maxOf(size, minPooledFrameBytes))
            return pooled
        }
        return PendingFrame(ByteArray(maxOf(size, minPooledFrameBytes)), 0, 0L)
    }

    /**
     * Returns a buffer to the pool, unless keeping it would push the pool past [pooledByteBudget].
     *
     * Dropping it on the floor is safe and deliberate - the next frame that needs a buffer allocates
     * one. Above a constrained device the budget is effectively unlimited, so this is the previous
     * behaviour exactly.
     */
    private fun recycleFrame(frame: PendingFrame) {
        val bytes = frame.data.size
        if (pooledBytes.get().toLong() + bytes > pooledByteBudget.toLong()) return
        if (framePool.offer(frame)) pooledBytes.addAndGet(bytes)
    }

    /**
     * Counts a shed frame and, with the picture live, asks the phone for the keyframe that
     * repairs what the loss costs.
     *
     * Every frame shed here is a reference some later frame predicts from, so the picture drifts
     * washed-out and blocky until a keyframe arrives - and the phone runs a fixed keyframe period
     * measured at ~69s, so waiting that out costs an average of ~35s of corruption on screen.
     *
     * The transport answers with the gain-only unsolicited focus nudge first, throttled so a
     * sustained backlog costs one request a second rather than one per frame, and starts a clock
     * that [com.andrerinas.openheadunit.aap.KeyframeCycleEscalationPolicy] uses to decide whether
     * that was enough. There is still no P-frame latch here and never will be: gating the feed on
     * keyframe detection is what once made recovering from a drop more expensive than the drop.
     *
     * Before the first rendered frame this stays silent - a warming codec sheds frames while
     * perfectly healthy, and that window belongs to
     * [com.andrerinas.openheadunit.aap.WarmRelaunchKeyframePolicy], which must not have a second
     * decider reaching for the same lever underneath it.
     */
    private fun notifyFrameDropped() {
        framesDropped++
        val now = SystemClock.elapsedRealtime()
        if (VideoRecoveryPolicy.shouldRequestOnDroppedFrame(lastFrameRenderedMs != 0L, now, lastDropKeyframeRequestMs)) {
            lastDropKeyframeRequestMs = now
            AppLog.w("VideoDecoder: dropped a reference frame, requesting keyframe")
            onFrameDropped?.invoke()
        }
    }

    /**
     * Drains the feed queue into the codec. Deliberately does not hold this object's monitor:
     * stop() does, and joins this thread while holding it. Follows the output thread's contract
     * instead - read [codec] once, and let stop() clear [running] and interrupt before releasing.
     */
    private fun feedThreadLoop() {
        AppLog.i(
            "Feed thread started (queue holds $frameQueueCapacity frames, " +
                "${VideoFeedQueuePolicy.heldMsAt(settings.fpsLimit)}ms at ${settings.fpsLimit}fps)"
        )
        val self = Thread.currentThread()
        while (running && feedThread === self) {
            val frame = try {
                frameQueue.poll(FEED_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue

            try {
                if (!running || feedThread !== self || codec == null) continue
                val buf = ByteBuffer.wrap(frame.data, 0, frame.size)
                when (feedInputBuffer(buf, frame.arrivalNanos)) {
                    FeedResult.FED -> framesFed++
                    // Counted and answered where the real buffer capacity is known.
                    FeedResult.DROPPED_TOO_LARGE -> {}
                    FeedResult.ERROR -> {
                        // Already logged with the exception where it happened; logFeedDrop would
                        // relabel it "Input buffer full", which is the one thing it was not. The
                        // frame is a lost reference frame all the same, so ask for the keyframe.
                        if (running && feedThread === self && codec != null) {
                            notifyFrameDropped()
                        }
                    }
                    FeedResult.NO_INPUT_BUFFER -> {
                        // A teardown fails the same way a full queue does. Say nothing in that
                        // case: the frame is moot and the log line would appear on every
                        // ordinary stop.
                        if (running && feedThread === self && codec != null) {
                            // The codec had no free input buffer for the whole wait. Drop the
                            // frame and carry on, asking for the keyframe that repairs the
                            // picture - see notifyFrameDropped(), whose rendered-frame gate keeps
                            // this quiet in the window where it used to misfire: within a second
                            // of the decoder starting, while the component is merely draining its
                            // first buffers. A decoder that is genuinely stuck rather than busy
                            // is still caught by the sync_stall watchdog.
                            logFeedDrop("")
                            notifyFrameDropped()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.w("Feed thread error: ${e.message}")
            } finally {
                recycleFrame(frame)
            }
        }
        AppLog.i("Feed thread stopped")
    }

    /**
     * Reports a frame the codec had no room for, at most once per [FEED_DROP_LOG_INTERVAL_MS].
     *
     * Called from the feed thread only, so the counters need no synchronisation.
     */
    private fun logFeedDrop(detail: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastFeedDropLogMs != 0L && now - lastFeedDropLogMs < FEED_DROP_LOG_INTERVAL_MS) {
            suppressedFeedDropLogs++
            return
        }
        lastFeedDropLogMs = now
        val alsoSuppressed = if (suppressedFeedDropLogs > 0) " (+$suppressedFeedDropLogs more since the last line)" else ""
        suppressedFeedDropLogs = 0
        AppLog.w("Input buffer full. Dropping frame.%s%s", detail, alsoSuppressed)
    }

    /** Empties the feed queue back into the pool. Anything still waiting is stale after a stop. */
    private fun clearFrameQueue() {
        while (true) {
            val frame = frameQueue.poll() ?: break
            recycleFrame(frame)
        }
    }

    private fun shouldUseBundledHevc(type: CodecType, forceSoftware: Boolean): Boolean {
        return type == CodecType.H265 &&
                forceSoftware &&
                settings.softwareVideoDecoder == Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG &&
                FfmpegHevcDecoder.isAvailable()
    }

    /**
     * True when an explicitly selected software HEVC decoder is available, i.e. H.265 is playable
     * here even though [isHevcSupported] - which is hardware-only - says no. Mirrors the
     * `explicitSoftwareHevc` half of the announcement, so the decoder and the codec we asked the
     * phone for agree about whether H.265 was ever on the table.
     */
    private fun bundledHevcSelected(): Boolean =
        settings.forceSoftwareDecoding && when (settings.softwareVideoDecoder) {
            Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> isBundledHevcDecoderAvailable()
            Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> isHevcDecoderAvailable(includeSoftware = true)
        }

    private fun startBundledHevc(width: Int, height: Int) {
        try {
            val surface = mSurface ?: return
            AppLog.i("Configuring bundled FFmpeg HEVC decoder for ${width}x$height")
            val yuvFrameSink = if (settings.viewMode == Settings.ViewMode.GLES) {
                softwareYuvFrameSink
            } else {
                null
            }
            val decoder = FfmpegHevcDecoder(
                surface = if (yuvFrameSink == null) surface else null,
                yuvFrameSink = yuvFrameSink,
                width = width,
                height = height
            )
            if (!decoder.start()) {
                AppLog.e("Bundled FFmpeg HEVC decoder is unavailable")
                return
            }
            softwareHevcDecoder = decoder
            currentCodecName = "ffmpeg-hevc"
            running = true
            startTime = System.nanoTime()
            codecTypePinned = true
            AppLog.i("Bundled FFmpeg HEVC decoder initialized")
        } catch (e: Exception) {
            AppLog.e("Failed to start bundled FFmpeg HEVC decoder", e)
            softwareHevcDecoder = null
            running = false
            scheduleRestart("bundled_hevc_start_failed: ${e.message}")
        }
    }

    private fun onSoftwareFramesRendered(renderedFrames: Int) {
        lastFrameRenderedMs = SystemClock.elapsedRealtime()
        renderedThisSession = true
        framesRenderedThisSession += renderedFrames
        if (!loggedFirstSoftwareFrame) {
            loggedFirstSoftwareFrame = true
            AppLog.i("First bundled software HEVC frame rendered")
        }
        onFirstFrameListener?.let { it(); onFirstFrameListener = null }

        frameCount += renderedFrames
        // The bundled decoder has no output thread, so drive the throughput tick from here.
        framesRendered += renderedFrames
        logThroughput()
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsLogTime
        if (elapsed >= 1000) {
            if (lastFpsLogTime != 0L) {
                val fps = (frameCount * 1000 / elapsed).toInt()
                onFpsChanged?.invoke(fps)
            }
            frameCount = 0
            lastFpsLogTime = now
        }
    }

    private fun detectCodecType(buffer: ByteArray, offset: Int, size: Int): CodecType? {
        if (size < 5) return null
        val limit = offset + size
        // Need at least 5 bytes visible from position i: [0, 0, 0/1, 1, NAL_HEADER]
        for (i in offset until limit - 4) {
            if (buffer[i].toInt() == 0 && buffer[i+1].toInt() == 0) {
                val headerPos: Int
                if (buffer[i+2].toInt() == 0 && buffer[i+3].toInt() == 1) {
                    headerPos = i + 4
                } else if (buffer[i+2].toInt() == 1) {
                    headerPos = i + 3
                } else continue
                if (headerPos >= limit) return null
                val b = buffer[headerPos].toInt()
                val avcType = b and 0x1F
                if (avcType == 7 || avcType == 8) return CodecType.H264

                val hevcType = (b and 0x7E) shr 1
                if (hevcType in 32..34 && isHevcSupported()) return CodecType.H265
            }
            // Only scan the first ~100 bytes for performance
            if (i - offset >= 96) break
        }
        return null
    }

    /**
     * Splits a combined packet into multiple NAL units and normalizes start codes.
     */
    private fun forEachNalUnit(buffer: ByteArray, offset: Int, size: Int, callback: (ByteArray, Int) -> Unit) {
        var currentPos = offset
        val limit = offset + size

        while (currentPos < limit - 3) {
            var nalStart = -1
            var startCodeLen = 0

            for (i in currentPos until limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i+1].toInt() == 0) {
                    if (buffer[i+2].toInt() == 0 && buffer[i+3].toInt() == 1) {
                        nalStart = i; startCodeLen = 4; break
                    } else if (buffer[i+2].toInt() == 1) {
                        nalStart = i; startCodeLen = 3; break
                    }
                }
            }

            if (nalStart != -1) {
                var nalEnd = limit
                for (j in (nalStart + startCodeLen) until limit - 3) {
                    if (buffer[j].toInt() == 0 && buffer[j+1].toInt() == 0 &&
                        (buffer[j+2].toInt() == 1 || (buffer[j+2].toInt() == 0 && buffer[j+3].toInt() == 1))) {
                        nalEnd = j; break
                    }
                }

                val rawNal = buffer.copyOfRange(nalStart, nalEnd)
                val fixedNal = if (startCodeLen == 3) {
                    // Normalize to 4-byte start codes for better decoder compatibility
                    ByteArray(rawNal.size + 1).apply {
                        this[0] = 0; System.arraycopy(rawNal, 0, this, 1, rawNal.size)
                    }
                } else rawNal

                callback(fixedNal, if (startCodeLen == 3) 4 else 4)
                currentPos = nalEnd
            } else break
        }
    }

    /**
     * Reads this session's sequence parameter set: picture size for the codec, and the buffering
     * fields for the log.
     *
     * One parser for both codecs, because there were two and one of them was wrong. The H.264
     * dimension parser this replaces walked `pic_order_cnt_type == 1` as though it were type 0 - it
     * read the POC LSB field that only type 0 has - and every field after that, including the width
     * and height, came out of the wrong bits. It also had no RBSP unescaping, so a `0x03` anywhere in
     * the scaling matrix of a High-profile SPS shifted the same fields. [ParameterSetInspector]
     * handles both, is covered by vectors, and discards the whole result rather than returning half
     * of one.
     *
     * The logged fields are the ones that decide how many frames a decoder holds before it emits one,
     * and we have never recorded what Android Auto actually sends. Moonlight rewrites the H.264 SPS on
     * every Android 8.0+ device it runs on because unmodified encoder output makes some hardware
     * decoders allocate 16+ reference buffers and adds frames of latency; whether that lever exists
     * here is a question about `num_ref_frames` and `bitstream_restriction`, so print them rather
     * than assume either way. Read-only - nothing modifies the bitstream.
     */
    private fun applyParameterSet(nalData: ByteArray, headerLen: Int, type: CodecType) {
        val length = nalData.size - headerLen
        val summary: String?
        val parsedWidth: Int
        val parsedHeight: Int
        if (type == CodecType.H264) {
            val parsed = ParameterSetInspector.parseH264Sps(nalData, headerLen, length)
            summary = parsed?.toString()
            parsedWidth = parsed?.width ?: 0
            parsedHeight = parsed?.height ?: 0
        } else {
            val parsed = ParameterSetInspector.parseHevcSps(nalData, headerLen, length)
            summary = parsed?.toString()
            parsedWidth = parsed?.width ?: 0
            parsedHeight = parsed?.height ?: 0
        }

        applyStreamDimensions(parsedWidth, parsedHeight, type)

        if (loggedParameterSet) return
        loggedParameterSet = true
        if (summary != null) {
            AppLog.i("Stream SPS (${type.settingsValue}): $summary")
        } else {
            AppLog.w("Stream SPS (${type.settingsValue}): could not be parsed ($length bytes)")
        }
    }

    /**
     * Takes the picture size from the stream rather than from what was negotiated.
     *
     * H.265 had no parser at all, so `mWidth` stayed zero and every H.265 session configured its
     * codec from `HeadUnitScreenConfig`'s negotiated size - which is what the phone was *asked* for,
     * not necessarily what it sent. That is what the "Fallback to negotiated dimensions" line reports,
     * and on an H.265 session it was reporting every time.
     *
     * Bounded rather than trusted, in the same spirit as the crop-rectangle check: a parse that comes
     * back with something outside any resolution a head unit projects is more likely a desync than a
     * discovery, and the negotiated size is the better guess in that case. Nothing here is final
     * either way - [handleOutputFormatChange] corrects the size again from the component's own output
     * format once frames start, which is the authority when they disagree.
     */
    private fun applyStreamDimensions(width: Int, height: Int, type: CodecType) {
        if (width <= 0 || height <= 0) return
        if (width !in MIN_PLAUSIBLE_DIMENSION..MAX_PLAUSIBLE_WIDTH ||
            height !in MIN_PLAUSIBLE_DIMENSION..MAX_PLAUSIBLE_HEIGHT
        ) {
            AppLog.w("${type.settingsValue} SPS reported an implausible ${width}x$height - ignoring it")
            return
        }
        if (mWidth == width && mHeight == height) return
        val negotiated = "${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}"
        AppLog.i("${type.settingsValue} SPS parsed: ${width}x$height (negotiated $negotiated)")
        mWidth = width
        mHeight = height
        dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
    }

    /**
     * Extracts SPS/PPS/VPS data for the decoder configuration (CSD).
     */
    private fun scanAndApplyConfig(buffer: ByteArray, offset: Int, size: Int, type: CodecType) {
        forEachNalUnit(buffer, offset, size) { nalData, headerLen ->
            val nalFirstByte = nalData[headerLen].toInt()
            if (type == CodecType.H264) {
                val nalType = nalFirstByte and 0x1F
                if (nalType == 7) { // SPS
                    sps = nalData
                    applyParameterSet(nalData, headerLen, CodecType.H264)
                } else if (nalType == 8) pps = nalData // PPS

                // H.264 requires at least SPS to start
                if (sps != null) codecConfigured = true
            } else {
                val nalType = (nalFirstByte and 0x7E) shr 1
                if (nalType == 32) vps = nalData
                else if (nalType == 33) {
                    sps = nalData
                    applyParameterSet(nalData, headerLen, CodecType.H265)
                } else if (nalType == 34) pps = nalData

                // H.265 requires VPS and SPS to start reliably
                if (vps != null && sps != null) codecConfigured = true
            }
        }
    }

    /**
     * Builds the mandatory part of the decoder format: parameter sets, input size and the per-vendor
     * stability patches. Everything here has to be set for the session to work at all, so none of it
     * takes part in the configure ladder - only [DecoderConfigLadder]'s optional keys do.
     */
    private fun buildFormat(mimeType: String, width: Int, height: Int, bestCodec: String): MediaFormat {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)

        // Deliberately no KEY_PRIORITY / KEY_OPERATING_RATE / KEY_FRAME_RATE / KEY_MAX_B_FRAMES
        // here. They were added as latency hints and measured to do nothing: the OMX components
        // on the head units they were meant to help answer with
        // "codec does not support config priority (err -1010)" and the same for the operating
        // rate. The frame-rate keys are worse than inert, because the only frame rate this
        // class knows is settings.fpsLimit - the user's cap, not the rate the phone negotiated
        // - and KEY_MAX_B_FRAMES is an encoder key. Any replacement needs a log from a device
        // where the codec actually accepts it.
        //
        // The ladder is how such a replacement gets tried safely: an optional key that the
        // component rejects now costs one retry instead of the session.

        // Apply Codec Specific Data (CSD) from parsed SPS/PPS/VPS
        if (mimeType == CodecType.H265.mimeType) {
            val combined = (vps ?: byteArrayOf()) + (sps ?: byteArrayOf()) + (pps ?: byteArrayOf())
            if (combined.isNotEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(combined))
            }
            // [BUG_FIX] Dynamic buffer size based on resolution.
            // 8MB is too large for many older 1080p decoders (Allwinner/Rockchip),
            // but we need it for 4K.
            //
            // These two figures are now a ceiling rather than the request. A component sizes its
            // whole input port from KEY_MAX_INPUT_SIZE, so asking for 2MB at 720p cost a 1GB unit
            // eight buffers of that size - 16MB of graphics memory - for a picture whose largest
            // legal coded frame is about 675KB. CodecInputSizePolicy derives the request from the
            // picture and clamps it here, so this can only ever ask for less than it used to.
            val cap = if (width * height > 1920 * 1080) {
                8 * 1024 * 1024
            } else {
                2 * 1024 * 1024
            }
            val maxInputSize = CodecInputSizePolicy.maxInputSizeFor(width, height, cap)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            requestedMaxInputSize = maxInputSize
        } else {
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))

            // [BUG_FIX] Lower buffer for legacy devices (Android < 9) to prevent startup stalls.
            // Kept as a ceiling; see the H.265 branch above for why the request itself is now
            // derived from the picture.
            val cap = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                1 * 1024 * 1024 // 1MB for legacy
            } else {
                2 * 1024 * 1024 // 2MB for modern
            }
            val maxInputSize = CodecInputSizePolicy.maxInputSizeFor(width, height, cap)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            requestedMaxInputSize = maxInputSize
        }

        val isAllwinner = bestCodec.lowercase(Locale.ROOT).contains("allwinner")
        if (isAllwinner) {
            // [BUG_FIX] Allwinner decoders often fail on adaptive playback initialization,
            // leading to a SIGABRT in CodecLooper when the surface reconfigures for padding (e.g. 1080->1088).
            AppLog.i("Decoder: Applying Allwinner stability patches.")
            format.setInteger("adaptive-playback", 0)

            if (mimeType == CodecType.H265.mimeType) {
                AppLog.w("CAUTION: Allwinner H.265 is known to be unstable. If the app crashes, please switch to H.264 in settings.")
                // Force macroblock alignment (multiple of 16) to prevent re-padding crash
                val alignedHeight = ((height + 15) / 16) * 16
                if (alignedHeight != height) {
                    AppLog.i("Decoder: Aligning Allwinner H.265 height to $alignedHeight (was $height)")
                    format.setInteger(MediaFormat.KEY_HEIGHT, alignedHeight)
                }
            }

            // Explicitly set color format to surface to help ACodec
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        return format
    }

    /**
     * Reports whether the chosen decoder can actually carry the picture it is about to be handed.
     *
     * Nothing acts on this. Codec selection stays where it was - the SoC allowlist in
     * [isHevcReliable] decides whether Auto mode offers H.265 at all - because overriding a codec the
     * user chose explicitly, or one already negotiated with the phone, is a bigger change than the
     * evidence supports. What was missing is the evidence: the artifact reports in #219 are mostly
     * from units where H.265 was selected by hand, and no log has ever said whether the component
     * that got it claims to manage that resolution and rate, or only to accept it.
     *
     * A WARN here on a unit that reports melting is the thing that would settle it.
     */
    private fun logDecoderCapability(codecName: String, mimeType: String, width: Int, height: Int) {
        if (loggedDecoderCapability) return
        loggedDecoderCapability = true
        // Silent below Lollipop, as before: there is no capability API to ask, and a WARN every
        // session on a device that can never answer is noise rather than a finding.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            // Held for the throughput line: when the codec turns out to be the bottleneck, what it
            // claimed beforehand is the other half of the finding.
            val capability = DecoderCapabilityReport.forCodec(
                codecName, mimeType, width, height, settings.fpsLimit
            )
            decoderCapability = capability
            if (capability == null) {
                AppLog.w("Decoder $codecName reported no video capabilities for $mimeType")
                return
            }
            if (capability.adequate) {
                AppLog.i("Decoder capability: $capability")
            } else {
                AppLog.w("Decoder may not manage this stream: $capability")
            }
        } catch (e: Exception) {
            AppLog.w("Decoder capability check failed for $codecName: ${e.message}")
        }
    }

    /**
     * Configures and starts the native MediaCodec.
     */
    private fun start(mimeType: String, forceSoftware: Boolean, width: Int, height: Int) {
        try {
            startTime = System.nanoTime()
            val bestCodec = findBestCodec(mimeType, !forceSoftware)
                ?: throw IllegalStateException("No decoder available for $mimeType")
            this.currentCodecName = bestCodec
            logDecoderCapability(bestCodec, mimeType, width, height)

            // Richest key set first, always ending in none. The last rung is exactly the format this
            // built before the ladder existed, so a component that rejects everything optional lands
            // on the previous behaviour rather than on a failed session.
            val tiers = DecoderConfigLadder.tiers(
                codecName = bestCodec,
                sdkInt = Build.VERSION.SDK_INT,
                // From the same report the capability line printed, so the ladder and that line can
                // no longer disagree about what the component advertises.
                advertisesLowLatencyFeature = decoderCapability?.lowLatency
                    ?: DecoderCapabilityReport.advertisesLowLatency(bestCodec, mimeType),
                lowLatencyRequested = settings.debugVideoLowLatency,
            )

            var started = false
            var lastFailure: Exception? = null
            for ((attempt, tier) in tiers.withIndex()) {
                // A configure() that throws leaves the component unusable, so each rung needs its own
                // instance - and the old one has to be released rather than dropped, because on head
                // units with a single hardware decoder instance every leaked one makes the next create
                // fail until the process dies.
                try { codec?.release() } catch (ignore: Exception) {}
                codec = null

                if (mSurface?.isValid != true) throw IllegalStateException("Surface not valid")

                codec = MediaCodec.createByCodecName(bestCodec)
                codecBufferInfo = MediaCodec.BufferInfo()
                val format = buildFormat(mimeType, width, height, bestCodec)
                for ((key, value) in tier.integerKeys) format.setInteger(key, value)

                val keyDetail = if (tier.integerKeys.isEmpty()) "" else " ${tier.integerKeys.keys}"
                AppLog.i(
                    "Configuring decoder: $bestCodec for ${width}x${height}, " +
                        "max-input-size=${requestedMaxInputSize / 1024}KB, memory=$memoryReading, " +
                        "queue=$frameQueueCapacity frames, optionalKeys=${tier.label}$keyDetail"
                )
                try {
                    codec?.configure(format, mSurface, null, 0)
                    try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (e: Exception) {}
                    codec?.start()
                    started = true
                    if (attempt > 0) {
                        AppLog.w("Decoder accepted the format only with optionalKeys=${tier.label}")
                    }
                    break
                } catch (e: Exception) {
                    lastFailure = e
                    AppLog.w("Decoder rejected optionalKeys=${tier.label}: ${e.message}")
                }
            }
            if (!started) {
                throw lastFailure ?: IllegalStateException("Decoder configure failed for $bestCodec")
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION") inputBuffers = codec?.inputBuffers
            }

            running = true
            clearFrameQueue()
            outputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                LegacyOptimizer.setHighPriority()
                outputThreadLoop()
            }.apply { name = "VideoDecoder-Output"; start() }
            // Published before start(): the loop's guard reads this field to prove its own
            // identity, and a thread that starts before the assignment lands can read the null a
            // prior stop() left and exit at birth - after which every frame queues into a feed
            // queue nobody drains.
            val newFeedThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                feedThreadLoop()
            }.apply { name = "VideoDecoder-Feed" }
            feedThread = newFeedThread
            newFeedThread.start()

            codecTypePinned = true
            AppLog.i("Codec initialized: $bestCodec")
        } catch (e: Exception) {
            AppLog.e("Failed to start decoder", e)
            // Release, not just null: a component created but never released survives the
            // reference drop, and on head units with a single hardware decoder instance each
            // leaked one makes every later create fail until the process dies.
            try { codec?.release() } catch (ignore: Exception) {}
            codec = null; running = false
            // The surface died between decode()'s validity check and this configure - the framework
            // tears it down on the main thread and no lock of ours covers that window. Restarting
            // would only fail here again, and every attempt reaches onDecoderError, which asks the
            // phone for a keyframe: measured once on hardware, that provoked an unsolicited video
            // sink start while no surface existed at all, and the session's next real relaunch
            // needed a focus cycle to recover from the state it left behind.
            //
            // So do not schedule anything. decode()'s own surface guard idles this path until
            // setSurface() arrives with a replacement, which rebuilds from there - the same route
            // every ordinary surface swap already takes.
            if (mSurface?.isValid != true) {
                AppLog.w("Decoder start aborted: the surface went away mid-configure. Waiting for a new one.")
                return
            }
            scheduleRestart("decoder_start_failed: ${e.message}")
        }
    }

    /**
     * Says once, per distinct answer, what the config scanner made of an access unit.
     *
     * Which units get `BUFFER_FLAG_CODEC_CONFIG` is decided per unit now rather than per chipset, so
     * a device that never used to receive a mid-stream config buffer may now receive one. That is a
     * real change in what reaches the component and it was previously invisible: three lines a
     * session make it a fact in the capture instead of an argument about the code.
     */
    private fun reportContentOnce(content: CodecConfigScanner.Content) {
        if (!loggedContentKinds.add(content)) return
        val consequence = when (content) {
            CodecConfigScanner.Content.PARAMETER_SETS_ONLY -> "flagged to the codec as configuration"
            CodecConfigScanner.Content.PARAMETER_SETS_WITH_PICTURE -> "carries a picture too, so not flagged"
            CodecConfigScanner.Content.NO_PARAMETER_SETS -> "an ordinary frame"
        }
        AppLog.i("VideoDecoder: access unit classified $content - $consequence (first this session)")
    }

    /**
     * Reads the parameter sets of an access unit that carries them and reports a genuine change.
     *
     * Diagnostic only - see [ParameterSetTracker] for why nothing here writes to the stored CSD or
     * the configured size. Runs only on units the config scanner says carry parameter sets, which is
     * keyframes and config messages, so the per-NAL copying [forEachNalUnit] does is paid a few times
     * a minute rather than sixty times a second.
     */
    private fun trackParameterSets(data: ByteArray, offset: Int, size: Int) {
        val hevc = currentCodecType == CodecType.H265
        var spsNal: ByteArray? = null
        var spsHeaderLen = 0
        forEachNalUnit(data, offset, size) { nalData, headerLen ->
            val first = nalData[headerLen].toInt()
            val kind = if (hevc) {
                when ((first and 0x7E) shr 1) {
                    32 -> ParameterSetTracker.Kind.VPS
                    33 -> ParameterSetTracker.Kind.SPS
                    34 -> ParameterSetTracker.Kind.PPS
                    else -> null
                }
            } else {
                when (first and 0x1F) {
                    7 -> ParameterSetTracker.Kind.SPS
                    8 -> ParameterSetTracker.Kind.PPS
                    else -> null
                }
            }
            if (kind != null) {
                parameterSetTracker.offer(kind, nalData, headerLen, nalData.size - headerLen)
                if (kind == ParameterSetTracker.Kind.SPS) {
                    spsNal = nalData
                    spsHeaderLen = headerLen
                }
            }
        }

        val change = parameterSetTracker.takeChange() ?: return
        val kinds = change.kinds.joinToString(" ")
        // Snapshotted because the lambda above assigns it; a captured var is not smart-castable.
        val sps = spsNal
        val sizeNote = if (sps != null) describeSizeChange(sps, spsHeaderLen, hevc) else "size unread"
        AppLog.w(
            "VideoDecoder: parameter sets changed mid-session ($kinds, $sizeNote) - change #${change.ordinal}"
        )
    }

    /**
     * The size the new SPS describes, against the one the codec is configured for. Read-only: the
     * configured size is corrected by the component's own output format, and second-guessing it from
     * here is the behaviour change this measurement exists to justify or rule out.
     */
    private fun describeSizeChange(nalData: ByteArray, headerLen: Int, hevc: Boolean): String {
        val length = nalData.size - headerLen
        val width: Int
        val height: Int
        if (hevc) {
            val parsed = ParameterSetInspector.parseHevcSps(nalData, headerLen, length) ?: return "size unparsed"
            width = parsed.width
            height = parsed.height
        } else {
            val parsed = ParameterSetInspector.parseH264Sps(nalData, headerLen, length) ?: return "size unparsed"
            width = parsed.width
            height = parsed.height
        }
        if (width <= 0 || height <= 0) return "size unparsed"
        return if (width == mWidth && height == mHeight) {
            "size unchanged ${width}x$height"
        } else {
            "size ${mWidth}x$mHeight -> ${width}x$height"
        }
    }

    /** Outcome of [feedInputBuffer] for one frame. */
    private enum class FeedResult {
        /** The whole frame was queued into the codec. */
        FED,

        /** No free input buffer inside the patience window; nothing was queued. */
        NO_INPUT_BUFFER,

        /** Frame exceeds the codec's input buffer; dropped and accounted for inside. */
        DROPPED_TOO_LARGE,

        /** The codec threw mid-feed; the frame is lost and the dequeued buffer was handed back. */
        ERROR,
    }

    /**
     * Queues one whole frame into the codec, or nothing at all.
     *
     * A frame is never split across input buffers: each queued buffer is parsed as a complete
     * access unit (this API range has no partial-frame flag), so a piece fed on its own reaches
     * the codec as a frame of its own and corrupts the picture as surely as the loss it was
     * trying to avoid - that is what the old truncate-and-feed path did.
     */
    private fun feedInputBuffer(buffer: ByteBuffer, arrivalNanos: Long): FeedResult {
        val currentCodec = codec ?: return FeedResult.NO_INPUT_BUFFER
        // Outside the try so the catch can hand a dequeued buffer back, and so it can tell a
        // throw before queueInputBuffer from one after it - the frame's fate differs.
        var inputIndex = -1
        var queued = false
        try {
            // ~300ms of patience. Anything much shorter gives up while the codec is merely busy:
            // at 30ms this reported a full input queue within a second of every decoder start,
            // before the component had drained its first buffers, on hardware that then went on to
            // decode at full rate.
            //
            // The whole dequeue is timed, not just the retries: the first call blocks for up to
            // TIMEOUT_US on its own, and what holds up audio behind us is the total.
            //
            // running is checked every iteration because interrupt() cannot abort a MediaCodec
            // call: stop() clears running before it joins this thread, so checking it here bounds
            // the time a stopping decoder waits on us to one dequeue rather than the full budget -
            // which is what lets stop()'s join reliably win before it releases the codec.
            val waitStart = SystemClock.elapsedRealtime()
            while (running && SystemClock.elapsedRealtime() - waitStart < VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS) {
                inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) break
            }
            inputWaitMs += SystemClock.elapsedRealtime() - waitStart

            if (inputIndex < 0) {
                // Silent here on purpose. A wait cut short by a teardown is ordinary and the frame
                // is moot, so "full" would blame the codec on every stop; and the caller reports the
                // genuine case through logFeedDrop, which throttles it. Printing at ERROR here as
                // well doubled the per-frame logging cost of a saturated link for no information.
                return FeedResult.NO_INPUT_BUFFER
            }

            val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                currentCodec.getInputBuffer(inputIndex)
            } else {
                @Suppress("DEPRECATION") inputBuffers?.get(inputIndex)
            }

            if (inputBuffer == null) {
                // Rare (a codec mid-flush can answer null), but the index is dequeued and must go
                // back or the pool is one buffer shallower for the rest of the codec's life.
                currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                return FeedResult.NO_INPUT_BUFFER
            }
            inputBuffer.clear()

            val capacity = inputBuffer.capacity()
            if (!loggedInputBufferCapacity) {
                loggedInputBufferCapacity = true
                AppLog.i(
                    "Codec input buffer: requested ${requestedMaxInputSize / 1024}KB, " +
                        "got ${capacity / 1024}KB per buffer"
                )
            }

            val content = if (buffer.hasArray()) {
                CodecConfigScanner.classify(
                    buffer.array(), buffer.position(), buffer.remaining(), currentCodecType == CodecType.H265
                )
            } else {
                CodecConfigScanner.Content.NO_PARAMETER_SETS
            }
            reportContentOnce(content)
            if (content != CodecConfigScanner.Content.NO_PARAMETER_SETS && buffer.hasArray()) {
                trackParameterSets(buffer.array(), buffer.position(), buffer.remaining())
            }
            val isKeyframe = buffer.hasArray() && VideoKeyframeScanner.containsKeyframe(
                buffer.array(), buffer.position(), buffer.remaining(), currentCodecType == CodecType.H265
            )
            // BUFFER_FLAG_CODEC_CONFIG says the buffer is codec-specific data and nothing else, so a
            // component may consume it and produce no output. Set it only for a unit that really is
            // only parameter sets: on a keyframe that leads with SPS/PPS and continues into its IDR
            // slice, this flag risks the slice - and with it the repair the keyframe was for.
            //
            // Chipset name no longer decides this. The three families that were listed here
            // (Rockchip, Allwinner, Telechips) were the only ones that flagged config mid-stream at
            // all, and they were also the only ones that could flag a fused keyframe as
            // configuration, because the old check read the first NAL and answered for the whole
            // unit. Getting the classification right makes the list unnecessary in both directions:
            // every device now gets the flag on genuine config, which Moonlight's errata says some
            // decoders crash without, and no device gets it on a picture.
            val flags = if (content == CodecConfigScanner.Content.PARAMETER_SETS_ONLY) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else 0

            if (buffer.remaining() > capacity) {
                // Handled here rather than in the caller because this is the only place the real
                // buffer capacity is known - codecs are free to allocate more or less than the
                // KEY_MAX_INPUT_SIZE the format asked for. The dequeued buffer goes straight back
                // empty so the codec's pool is not depleted by the drop.
                AppLog.w("Frame larger than the codec input buffer: ${buffer.remaining()} > $capacity bytes. Dropping frame.")
                currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                notifyFrameDropped()
                return FeedResult.DROPPED_TOO_LARGE
            }

            inputBuffer.put(buffer)
            inputBuffer.flip()

            val pts = ((if (arrivalNanos > 0L) arrivalNanos else System.nanoTime()) - startTime) / 1000

            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, flags)
            queued = true
            if (isKeyframe) {
                // Fed, not yet repaired. The picture counts as repaired at the output side, where
                // a keyframe that arrived holed is told apart from one that decodes.
                keyframeRepair.onKeyframeFed(pts)
                AppLog.i("VideoDecoder: keyframe reached the codec (${inputBuffer.limit()} bytes)")
            }
            return FeedResult.FED
        } catch (e: Exception) {
            if (queued) {
                // The frame reached the codec; only the bookkeeping after the queue failed. Calling
                // it anything but fed would report a frame the codec holds as shed and spend a
                // keyframe request on a picture that is not broken.
                AppLog.e("Error after feeding input buffer", e)
                return FeedResult.FED
            }
            if (inputIndex >= 0) {
                // Hand the dequeued buffer back empty, or the codec's input pool is permanently one
                // buffer shallower per throw - enough of these and every later feed times out with
                // NO_INPUT_BUFFER, which the stall watchdog then answers with a rebuild the codec
                // never earned.
                try {
                    currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                } catch (returnError: Exception) {
                    AppLog.e("Could not return the input buffer after a failed feed", returnError)
                }
            }
            AppLog.e("Error feeding input buffer", e)
            return FeedResult.ERROR
        }
    }

    /**
     * Periodic decode/render throughput summary.
     *
     * `rendered` counts buffers actually handed to the surface and `fed` counts frames accepted
     * into the codec's input queue, so the two together locate a slow picture: `fed` high with
     * `rendered` low is a slow codec, both high is a display consumer that cannot keep up with
     * one, and `dropped` rising is input-queue backpressure.
     *
     * `skipped` counts decoded frames discarded to reach a newer one, so it separates the two
     * shapes of a low `rendered`: with `skipped` high the frames arrived in a burst and the older
     * ones were deliberately passed over, with it near zero they never arrived at all.
     *
     * Called from the output thread on every loop iteration. That loop turns over at least every
     * 10ms (dequeueOutputBuffer's timeout) whether or not a frame came out, so the tick still
     * fires while nothing is rendering - which is the case most worth reporting.
     */
    private fun logThroughput() {
        val now = SystemClock.elapsedRealtime()
        if (lastThroughputLogMs == 0L) {
            lastThroughputLogMs = now
            return
        }
        val elapsed = now - lastThroughputLogMs
        if (elapsed < THROUGHPUT_LOG_INTERVAL_MS) return

        val rendered = framesRendered - lastLoggedFramesRendered
        val fed = framesFed - lastLoggedFramesFed
        val dropped = framesDropped - lastLoggedFramesDropped
        val skipped = framesSkippedAtRender - lastLoggedFramesSkippedAtRender
        val inputWait = inputWaitMs - lastLoggedInputWaitMs
        val concealed = framesConcealed - lastLoggedFramesConcealed
        lastLoggedFramesConcealed = framesConcealed
        lastThroughputLogMs = now
        lastLoggedFramesRendered = framesRendered
        lastLoggedFramesFed = framesFed
        lastLoggedFramesDropped = framesDropped
        lastLoggedFramesSkippedAtRender = framesSkippedAtRender
        lastLoggedInputWaitMs = inputWaitMs

        val renderedFps = rendered * 1000 / elapsed
        val fedFps = fed * 1000 / elapsed
        AppLog.i(
            "Throughput over ${elapsed}ms: rendered=$rendered (${renderedFps}fps), " +
                "fed=$fed (${fedFps}fps), dropped=$dropped, skipped=$skipped, " +
                "concealed=$concealed, inputWait=${inputWait}ms, codec=$currentCodecName"
        )
        reportBackpressure(elapsed, inputWait, dropped)
    }

    /**
     * Says once, in the reporter's own log, that the codec is what is losing the frames.
     *
     * Both numbers this reads have been printed on every throughput line for a long time and
     * nothing has ever interpreted them, which left the interpretation to whoever read the log by
     * hand - and that step is where two investigations went wrong. #219 was read as a reassembly
     * fault for five months on a device whose captures show shedding only in windows that also
     * spent up to 2019ms of 5000 waiting for an input buffer.
     *
     * The capability line from configure time is quoted alongside, because "the codec cannot keep
     * up" and "the codec said it could" only mean something together.
     */
    private fun reportBackpressure(elapsedMs: Long, inputWaitMs: Long, dropped: Long) {
        // Early out for cost only; the once-per-session rule itself lives in shouldReport below.
        if (reportedBackpressure) return
        if (!VideoBackpressurePolicy.isBackpressureWindow(elapsedMs, inputWaitMs, dropped)) return
        backpressureWindows++
        if (!VideoBackpressurePolicy.shouldReport(backpressureWindows, reportedBackpressure)) return
        reportedBackpressure = true
        val claim = decoderCapability?.let {
            if (it.adequate) " It claimed it could: $it" else " It said it might not: $it"
        } ?: ""
        AppLog.w(
            "VideoDecoder: the codec is the bottleneck - $backpressureWindows windows shed frames " +
                "while waiting >=${VideoBackpressurePolicy.WAIT_PERCENT}% of the window for an input " +
                "buffer (${mWidth}x$mHeight@${settings.fpsLimit} on $currentCodecName). The negotiated " +
                "size and rate are more than this decoder is keeping up with.$claim"
        )
    }

    /**
     * Dedicated thread to pull decoded frames and render them to the surface.
     */
    private fun outputThreadLoop() {
        AppLog.i("Output thread started")
        var consecutiveErrors = 0
        var lastOutputMs = SystemClock.elapsedRealtime()

        while (running) {
            val currentCodec = codec
            val bufferInfo = codecBufferInfo
            if (currentCodec == null || bufferInfo == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }

            try {
                val outputIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputIndex >= 0) {
                    // The codec produced output, whatever the screen ends up showing. This is the
                    // stall watchdog's clock and it is deliberately not the render stamp: the two
                    // were the same fact only while every dequeued buffer was also displayed, and
                    // anything that legitimately holds a decoded frame off the screen must not
                    // read as a codec that stopped decoding. lastFrameRenderedMs stays the display
                    // watchdogs' instrument and is stamped only when a frame reaches the surface.
                    lastOutputMs = SystemClock.elapsedRealtime()
                    // Catch up to the newest ready frame instead of replaying the backlog. A link
                    // that goes quiet for a few hundred milliseconds delivers what it owed in one
                    // burst; showing every frame of it walks the picture forward in slow motion and
                    // never closes the gap against audio, which does not queue the same way. These
                    // frames are already decoded, so discarding them costs no reference data and
                    // cannot corrupt what follows: the one place load can be shed for free.
                    //
                    // "Another buffer is ready" on its own does NOT mean we are behind.
                    // releaseOutputBuffer(true) hands the frame to the surface and returns without
                    // waiting for vsync, so back-pressure lands a step later, on the codec asking
                    // for a graphic buffer. One decoded frame sitting ahead of the display is
                    // ordinary pipeline depth, and treating that as a backlog would discard half
                    // the frames of a perfectly healthy 60fps stream. So nothing is discarded
                    // until a third frame is ready: at depth two both are rendered back to back
                    // and the display shows whichever it can, and only a genuine burst - the one
                    // that arrives after silence, dozens deep - reaches the discard path.
                    readyIndices[0] = outputIndex
                    var readyCount = 1
                    var passes = 0
                    while (readyCount <= MAX_CATCHUP_SKIPS && passes < MAX_CATCHUP_SKIPS * 2) {
                        passes++
                        val readyIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 0L)
                        if (readyIndex >= 0) {
                            readyIndices[readyCount++] = readyIndex
                        } else if (readyIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            handleOutputFormatChange(currentCodec.outputFormat)
                        } else {
                            break
                        }
                    }

                    val renderIndex = readyIndices[readyCount - 1]

                    // The repair question is answered before anything is released, because whether
                    // this pass's frame repaired the picture decides whether it is shown at all.
                    // bufferInfo carries the last successful dequeue, which is renderIndex. The
                    // frames released ahead of it in this pass decoded earlier and so have smaller
                    // timestamps, and the ones the catch-up branch discarded decoded too - so this
                    // one stamp answers for the whole pass either way.
                    val repaired = keyframeRepair.onFrameRendered(bufferInfo.presentationTimeUs)
                    // The report is consumed whatever the outcome is. One answered with SHOW while
                    // the policy is disarmed must not linger, or it would reopen a window against a
                    // stale fault the moment the next keyframe re-arms.
                    val reportedStamp = corruptionReportedMs
                    val reported = reportedStamp > consumedCorruptionMs
                    if (reported) consumedCorruptionMs = reportedStamp
                    val passNow = SystemClock.elapsedRealtime()
                    val outcome = CorruptionConcealmentPolicy.next(
                        passNow,
                        concealState,
                        concealWindowOpenedMs,
                        corruptionReported = reported,
                        keyframeRepaired = repaired,
                        sessionHasRendered = renderedThisSession,
                    )
                    applyConcealmentTransition(outcome, passNow)

                    var alsoRendered = 0
                    if (!outcome.renders) {
                        // The whole pass is withheld; the surface keeps the last good frame. These
                        // buffers are decoded first, so the codec's reference state stays current -
                        // the feed is never gated, only what the screen shows.
                        for (i in 0 until readyCount - 1) {
                            currentCodec.releaseOutputBuffer(readyIndices[i], false)
                        }
                        framesConcealed += (readyCount - 1).toLong()
                    } else if (readyCount > 2) {
                        for (i in 0 until readyCount - 1) {
                            currentCodec.releaseOutputBuffer(readyIndices[i], false)
                        }
                        framesSkippedAtRender += readyCount - 1
                    } else {
                        for (i in 0 until readyCount - 1) {
                            currentCodec.releaseOutputBuffer(readyIndices[i], true)
                            alsoRendered++
                        }
                    }
                    // These went to the surface too, so they belong in the rendered rate; counting
                    // only the last one would understate it by half whenever the pipeline sits one
                    // frame deep, which is the healthy case this is careful not to disturb.
                    framesRendered += alsoRendered
                    frameCount += alsoRendered
                    framesRenderedThisSession += alsoRendered

                    currentCodec.releaseOutputBuffer(renderIndex, outcome.renders)
                    consecutiveErrors = 0
                    if (outcome.renders) {
                        lastFrameRenderedMs = SystemClock.elapsedRealtime()
                        renderedThisSession = true
                        // repaired implies a rendering outcome (CLOSE_REPAIRED or REARM), so the
                        // repair announcement cannot be lost to a concealed pass.
                        if (repaired) {
                            if (keyframeRepair.timestampsUnusable && !loggedUnusableOutputTimestamps) {
                                loggedUnusableOutputTimestamps = true
                                AppLog.w(
                                    "$currentCodecName never carries a keyframe's timestamp through to its " +
                                        "output, so a repaired picture is read from frames arriving rather " +
                                        "than from the frame that repaired it."
                                )
                            }
                            AppLog.i("VideoDecoder: keyframe decoded - the picture is repaired")
                            onKeyframeObserved?.invoke()
                        }
                        framesRenderedThisSession++
                        // The one landmark that says video actually reached the screen on the path
                        // almost every unit runs. Driven by its own flag rather than the listener
                        // below, which only exists while the projection activity is up — the sessions
                        // worth timing are exactly the ones where it might not be.
                        if (!loggedFirstHardwareFrame) {
                            loggedFirstHardwareFrame = true
                            AppLog.i("First frame rendered (hardware decode)")
                        }
                        onFirstFrameListener?.let { it(); onFirstFrameListener = null }

                        frameCount++
                        framesRendered++

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastFpsLogTime
                        if (elapsed >= 1000) {
                            if (lastFpsLogTime != 0L) {
                                val fps = (frameCount * 1000 / elapsed).toInt()
                                onFpsChanged?.invoke(fps)
                            }
                            frameCount = 0
                            lastFpsLogTime = now
                        }
                    } else {
                        framesConcealed++
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChange(currentCodec.outputFormat)
                }

                logThroughput()

                // A window can only close on an output buffer, and a phone that goes idle
                // mid-window sends none - so the cap is also held on wall clock, once per loop
                // turn (the dequeue above times out in 10ms). On expiry there is no buffer to
                // show; the transition and its line happen now, the next buffer renders.
                if (concealState == CorruptionConcealmentPolicy.State.CONCEALING) {
                    val tickNow = SystemClock.elapsedRealtime()
                    applyConcealmentTransition(
                        CorruptionConcealmentPolicy.next(
                            tickNow,
                            concealState,
                            concealWindowOpenedMs,
                            corruptionReported = false,
                            keyframeRepaired = false,
                            sessionHasRendered = renderedThisSession,
                        ),
                        tickNow
                    )
                }

                // Stall detection: if we rendered at least one frame but haven't
                // produced output in SYNC_STALL_THRESHOLD_MS, check if input bytes
                // are still arriving from the phone. If no input bytes arrived recently,
                // Android Auto has simply paused the stream (idle/static screen), so
                // we update lastOutputMs to prevent false-positive restarts and screen flickering.
                val now = SystemClock.elapsedRealtime()
                val stallGap = now - lastOutputMs
                // A rebuilt codec that has not yet produced its first frame gets the longer
                // warm-up window when this session has already rendered - see the constant.
                val stallThreshold = if (lastFrameRenderedMs == 0L && renderedThisSession) {
                    WARM_RECONFIGURE_FIRST_FRAME_GRACE_MS
                } else {
                    SYNC_STALL_THRESHOLD_MS
                }
                if (stallGap > stallThreshold) {
                    val inputIdleGap = now - lastInputBytesReceivedMs
                    val cause = DecoderStallCausePolicy.classify(
                        stallGapMs = stallGap,
                        inputIdleGapMs = inputIdleGap,
                        inputIdleThresholdMs = SYNC_STALL_THRESHOLD_MS,
                        keyframeDecodedSinceStart = keyframeRepair.keyframeDecoded,
                        sessionHasRendered = renderedThisSession,
                    )
                    if (cause == DecoderStallCausePolicy.Cause.PHONE_IDLE) {
                        // Stream is idle on the phone side (no new video frames arriving).
                        // Deliberately silent: this branch used to fall through to the
                        // "restart suppressed" line below, which read as a decoder fault for a
                        // phone that had simply stopped sending, and misled a whole hardware
                        // round with "0/4 used" printed every 10s for an idle stream.
                        lastOutputMs = now
                    } else if (cause == DecoderStallCausePolicy.Cause.STARVED_OF_KEYFRAME) {
                        // Nothing is wrong with the codec: it has been given no keyframe since it
                        // started, so it has nothing it could render. Rebuilding it here is what
                        // turned one corrupt access unit into a permanent black screen - each
                        // rebuild restarts the wait and spends a restart the real stall path needs.
                        // Ask instead, on the same throttle the suppressed report uses.
                        if (now - lastSyncStallSuppressedLogMs >= SYNC_STALL_SUPPRESSED_LOG_INTERVAL_MS) {
                            lastSyncStallSuppressedLogMs = now
                            AppLog.w("Decoder has had no keyframe since it started ${stallGap}ms ago - waiting for one instead of rebuilding.")
                        }
                        // The ask runs on the shorter of the two clocks. This loop turns over every
                        // 10ms, so it needs its own throttle rather than the report's - the same
                        // one every other keyframe request in the app is held to.
                        if (VideoRecoveryPolicy.canRequestKeyframe(now, lastKeyframeStarvedAskMs)) {
                            lastKeyframeStarvedAskMs = now
                            onKeyframeStarved?.invoke()
                        }
                    } else {
                        // Input bytes ARE arriving, but decoder produces no output -> REAL DECODER STALL!
                        // A device that is merely marginal — renders fine for stretches, then
                        // stalls under load — never trips restartsSinceLastFrame's cap, since that
                        // counts only restarts where no frame at all was rendered. Cap and cooldown
                        // this watchdog the same way rather than rebuilding the MediaCodec every
                        // time it fires.
                        if (syncStallRestartCount > 0 && now - lastSyncStallRestartMs > SYNC_STALL_RESET_MS) {
                            syncStallRestartCount = 0
                        }
                        if (now - lastSyncStallRestartMs >= SYNC_STALL_COOLDOWN_MS &&
                            syncStallRestartCount < MAX_SYNC_STALL_RESTARTS) {
                            syncStallRestartCount++
                            lastSyncStallRestartMs = now
                            AppLog.w("Decoder stall detected (no output for ${stallGap}ms while receiving input). Forcing restart ($syncStallRestartCount/$MAX_SYNC_STALL_RESTARTS).")
                            scheduleRestart("sync_stall")
                            break
                        }
                        // Suppressed by the cooldown or the cap. Report it, throttled: the branch
                        // above is the only thing that ever mentions a stall, so once it stops
                        // firing a decoder that has exhausted its restart budget keeps stalling
                        // with an entirely clean log and reads as healthy.
                        if (now - lastSyncStallSuppressedLogMs >= SYNC_STALL_SUPPRESSED_LOG_INTERVAL_MS) {
                            lastSyncStallSuppressedLogMs = now
                            AppLog.w("Decoder stall detected (no output for ${stallGap}ms) but restart suppressed ($syncStallRestartCount/$MAX_SYNC_STALL_RESTARTS used, ${SYNC_STALL_COOLDOWN_MS}ms cooldown). Still spinning on output.")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    consecutiveErrors++
                    AppLog.w("Codec exception in output thread (attempt $consecutiveErrors): ${e.message}")
                    if (consecutiveErrors >= 3) {
                        AppLog.e("Too many consecutive exceptions in output thread. Forcing restart.")
                        scheduleRestart("sync_consecutive_errors")
                        break
                    }
                    try { Thread.sleep(50) } catch (ignore: Exception) {}
                }
            }
        }
        AppLog.i("Output thread stopped")
    }

    /**
     * Resolves the best available hardware or software decoder for the given mime type.
     */
    private fun findBestCodec(mimeType: String, preferHardware: Boolean): String? {
        val codecInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        } else {
            @Suppress("DEPRECATION")
            val count = MediaCodecList.getCodecCount()
            (0 until count).map { MediaCodecList.getCodecInfoAt(it) }
        }

        val infos = codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val hw = infos.find { isHardwareAccelerated(it) }
        val sw = infos.find { !isHardwareAccelerated(it) }
        val selected = if (preferHardware && hw != null) hw.name else sw?.name ?: hw?.name
        AppLog.i("findBestCodec: hw=${hw?.name}, sw=${sw?.name}, preferHardware=$preferHardware, selected=$selected")
        return selected
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val lower = info.name.lowercase(Locale.ROOT)
        return !(lower.startsWith("omx.google.") || lower.startsWith("c2.android.") ||
                lower.startsWith("omx.ffmpeg.") || lower.contains(".sw.") || lower.contains("software"))
    }
}
