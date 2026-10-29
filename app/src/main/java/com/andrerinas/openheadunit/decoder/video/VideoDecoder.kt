package com.andrerinas.openheadunit.decoder.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.openheadunit.utils.AuditReportPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.LegacyOptimizer
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

/**
 * Main video decoding engine.
 * Handles H.264/H.265 streams via MediaCodec or the optional bundled FFmpeg backend.
 *
 * [readMemory] is called at every session start rather than once, and its answer is reported with
 * the codec configuration so that every bug report carries what the device actually has. It is a
 * function and not a value because this object is built once per process: a reading taken in the
 * constructor could never see the debug override change.
 */
class VideoDecoder(
    private val settings: Settings,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val setThreadPriority: (Int) -> Unit = android.os.Process::setThreadPriority,
    private val bundledDecoderAvailable: (CodecType) -> Boolean = { FfmpegVideoDecoder.isAvailable(it) },
    private val readMemory: () -> DeviceMemoryReading = {
        DeviceMemoryReading(
            DeviceMemoryProfile.NORMAL, totalRamMb = 0, heapLimitMb = 0, memoryClassMb = 0, systemLowRamFlag = false
        )
    },
) {
    companion object {
        private const val TIMEOUT_US = 10000L
        // A ready output wakes the codec wait immediately; this is not a render delay.
        // Bound idle polling well below the 2s stall watchdog.
        private const val OUTPUT_TIMEOUT_US = 50_000L
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

        fun isBundledDecoderAvailable(type: CodecType): Boolean {
            return FfmpegVideoDecoder.isAvailable(type)
        }
    }

    private var codec: MediaCodec? = null
    private var softwareVideoDecoder: FfmpegVideoDecoder? = null
    private var codecBufferInfo: MediaCodec.BufferInfo? = null
    // decode() is a transport/preflight operation. Native creation and software decoding are
    // serialized here, never under the UI-facing decoder or output-publication monitors.
    private val nativeWorkLock = ReentrantLock()
    private class NativeResources(var codec: MediaCodec? = null, var software: FfmpegVideoDecoder? = null)
    private var nativeResources: NativeResources? = null
    private var pendingRetirements = 0
    private var retirementCompleteListener: (() -> Unit)? = null
    private class RecoveryInput(
        val generation: Long,
        val surface: Surface,
        var bytes: ByteArray,
        var size: Int,
        var keyframe: Boolean,
        var forceSoftware: Boolean,
        var announcedCodec: CodecType,
        var configurationRevision: Long,
        var continuityLost: Boolean = false,
    )
    private var recoveryInput: RecoveryInput? = null
    @Volatile private var recoveryContinuityLost = false
    private class StartRequest(
        val resources: NativeResources,
        val generation: Long,
        val surface: Surface,
        val type: CodecType,
        val width: Int,
        val height: Int,
        val forceSoftware: Boolean,
    )

    /** Physical retirement is asynchronous; a new owner cannot allocate until it finishes. */
    internal val hasPendingRetirement: Boolean get() = synchronized(this) { pendingRetirements != 0 }

    /** One lifecycle observer, invoked outside decoder/native locks, including already-retired owners. */
    internal fun whenRetired(listener: () -> Unit) {
        val immediate = synchronized(this) {
            if (pendingRetirements == 0) true else {
                retirementCompleteListener = listener
                false
            }
        }
        if (immediate) listener()
    }
    @Volatile private var mSurface: Surface? = null
    // The surface last detached, so a teardown callback arriving after the detach is not mistaken for a stale one.
    private var detachedSurface: Surface? = null
    // Native calls ignore interrupt. Revoke publication before retiring an owner, then wait for
    // its actual workers/operations off-thread before releasing its resources. Output publication
    // never takes the decoder monitor; callbacks and native calls stay outside both monitors.
    // Codec selection and format-specific recovery policies remain independent of this boundary.
    private val outputPublicationLock = Any()
    @Volatile private var outputThread: Thread? = null
    @Volatile private var running = false
    private var startTime = 0L

    private var mWidth = 0
    private var mHeight = 0
    private val parameterSets = ParameterSetCache()
    @Volatile private var currentCodecType = CodecType.H264
    private var currentCodecName: String? = null

    // Decoder restarts and surface changes do not renegotiate the stream. Retain the codec
    // selected from valid SPS evidence or the session's announcement until disconnect.
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
    // Strong stream evidence corrected the announced codec, not a decoder-failure guess.
    @Volatile private var codecFallbackUsed = false
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
    // Frames that reached the panel, counted once per output pass rather than once per buffer.
    //
    // framesRendered counts every buffer released with render=true, and at a queue depth of two
    // this path releases both back to back in one pass while the surface latches one. So the two
    // agree while the queue runs one deep, and `rendered` runs ahead when it does not - about 1.6x,
    // measured against a TextureView's own displayed-frame line over 619 samples. What was wrong is
    // that `rendered` has been read as the user-visible rate on two open issues. This is that rate.
    @Volatile private var framesPresented = 0L
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
    // Milliseconds decode() spent waiting for space in the feed queue, on the transport's read
    // thread. Non-zero is the backpressure working: the read thread is being paced at the codec's
    // real drain rate, closing the phone's unacked-message window, instead of frames being shed.
    // Every wait counts, including one that ran out the budget and shed the frame anyway - the
    // thread was held either way, and those are the longest waits the path can produce. Read it
    // beside `dropped`: high here with dropped=0 is pacing, both high is a wedge.
    // See offerWithBackpressure().
    @Volatile private var enqueueWaitMs = 0L
    private var lastThroughputLogMs = 0L
    private var lastLoggedFramesFed = 0L
    private var lastLoggedFramesDropped = 0L
    private var lastLoggedFramesRendered = 0L
    private var lastLoggedFramesPresented = 0L
    private var lastLoggedFramesSkippedAtRender = 0L
    private var lastLoggedInputWaitMs = 0L
    private var lastLoggedEnqueueWaitMs = 0L

    // Encoded frames waiting to be handed to the codec, and the thread that hands them over.
    //
    // This queue exists to keep the wait for a free codec input buffer off the transport's read
    // thread. That thread carries every channel - video, audio, microphone, control - so while it
    // sat inside dequeueInputBuffer nothing else on the link was dispatched and the socket went
    // undrained, which turned a busy decoder into stalled audio and late keepalives. The one wait
    // the read thread still takes is deliberate and different in kind: when this queue is FULL,
    // offerWithBackpressure() paces that thread by one queue slot - one frame period in steady
    // state - so the phone's ack window throttles it instead of frames being shed. Frames are
    // copied on the way in because the transport reuses the buffer it hands us.
    private class PendingFrame(
        var data: ByteArray, var size: Int, var arrivalNanos: Long, var generation: Long = 0L,
        var repairEligible: Boolean = true,
    )
    private class FeedOwner(
        val thread: Thread,
        val codec: MediaCodec,
        val inputBuffers: Array<ByteBuffer>?,
        val startTime: Long,
        val type: CodecType,
        val queue: ArrayBlockingQueue<PendingFrame>,
        val generation: Long,
    )
    // Re-derived per session by refreshPipelineSizing(), not fixed here. This object is built once
    // in AppComponent and lives for the whole process, so a depth chosen in the constructor
    // outlasted every settings change: a unit switched to 30fps kept the 60fps queue, and a log
    // could report "queue holds 15 frames, 500ms at 60fps" - two numbers that cannot both be right.
    @Volatile private var memoryReading: DeviceMemoryReading = readMemory()
    @Volatile private var frameQueueCapacity = VideoFeedQueuePolicy.capacityFor(settings.fpsLimit)
    @Volatile private var frameQueue = ArrayBlockingQueue<PendingFrame>(frameQueueCapacity)
    @Volatile private var framePool = ArrayBlockingQueue<PendingFrame>(frameQueueCapacity + 2)
    @Volatile private var decoderGeneration = 0L

    @Volatile private var constrained = memoryReading.profile == DeviceMemoryProfile.CONSTRAINED
    @Volatile private var minPooledFrameBytes =
        if (constrained) MIN_POOLED_FRAME_BYTES_CONSTRAINED else MIN_POOLED_FRAME_BYTES
    @Volatile private var pooledByteBudget =
        if (constrained) CONSTRAINED_POOL_BUDGET_BYTES else Int.MAX_VALUE

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
    // Same shape as the feed-drop pair above, for the pacing line, but owned by the transport's
    // read thread - the two threads must not share unsynchronised counters.
    private var lastFeedPacingLogMs = 0L
    private var suppressedFeedPacingLogs = 0
    // Identity remains necessary after revocation: a native call may finish much later, and
    // neither its result nor its queued callbacks belong to a subsequent decoder owner.
    @Volatile private var feedThread: Thread? = null

    // Reuse buffers for older API levels to minimize GC pressure
    private var inputBuffers: Array<ByteBuffer>? = null

    var dimensionsListener: VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    var softwareYuvFrameSink: SoftwareYuvFrameSink? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    private var loggedFirstSoftwareFrame = false
    private var loggedFirstHardwareFrame = false
    var onFirstFrameListener: (() -> Unit)? = null
        get() = synchronized(outputPublicationLock) { field }
        set(value) = synchronized(outputPublicationLock) { field = value }
    @Volatile var lastFrameRenderedMs: Long = 0L

    // Frames rendered since whoever owns the session last zeroed this. Deliberately *not* cleared
    // by stop(), which is what separates it from framesRendered above: that one is a throughput
    // counter and must not straddle a restart, this one must survive every restart the session
    // contains. The surface goes away and comes back within a single session (leaving projection,
    // screen off, a config change), and both stop() and setSurface() zero lastFrameRenderedMs when
    // it does, so reading that to ask "did this session ever show video" answers no for a session
    // that showed plenty. See CommManager.noteSessionEnded.
    @Volatile var framesRenderedThisSession: Long = 0L

    /** What one finished session did, for the home screen's repeated-problem suggestion. */
    data class SessionHealth(
        val framesFed: Long,
        val framesDropped: Long,
        val framesSkipped: Long,
        val framesRendered: Long,
        val codecFallbackUsed: Boolean,
        val finalCodec: CodecType,
    )

    // elapsedRealtime() of the last encoded video bytes received from the phone (input side),
    // as opposed to lastFrameRenderedMs (output side). Lets the projection watchdog tell a
    // phone-side pause (no input) apart from a local display stall (input flowing, nothing drawn).
    @Volatile var lastInputBytesReceivedMs: Long = 0L

    // The bundled decoder can render directly to a surface or through the GLES YUV sink.
    // Bundled output has no MediaCodec presentation-timestamp accounting.
    val usingBundledSoftwareVideo: Boolean get() = softwareVideoDecoder != null

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

    // Fired, throttled, when a frame is shed with the picture live - see recordFrameDropped().
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

    /** When the newest keyframe was queued, for the grace in [PictureCredibilityPolicy]. */
    @Volatile
    private var lastKeyframeFedMs = 0L

    // --- Corruption concealment - see CorruptionConcealmentPolicy for the design and its bounds.

    /**
     * When the transport last reported a lost or damaged access unit, and what it called it.
     * Single writer (the transport's poll thread raises every reassembly anomaly and every
     * framing-audit finding) and single reader (the output thread), so a volatile stamp is the
     * whole synchronisation. If a second reporting thread ever appears, this needs more.
     */
    @Volatile private var corruptionReportedMs = 0L
    @Volatile private var lastCorruptionReason = ""

    // Output updates and lifecycle reset are fenced by the publication lock.
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
        corruptionReportedMs = elapsedRealtime()
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
        report: (String) -> Unit = { AppLog.w(it) },
    ) {
        when (outcome) {
            CorruptionConcealmentPolicy.Outcome.OPEN -> {
                concealState = CorruptionConcealmentPolicy.State.CONCEALING
                concealWindowOpenedMs = nowMs
                logConcealment(
                    nowMs,
                    "VideoDecoder: holding the picture after $lastCorruptionReason - the last " +
                        "good frame stays up until a keyframe", report
                )
            }
            CorruptionConcealmentPolicy.Outcome.CLOSE_REPAIRED -> {
                concealState = CorruptionConcealmentPolicy.State.ARMED
                logConcealment(
                    nowMs,
                    "VideoDecoder: picture restored ${nowMs - concealWindowOpenedMs}ms after " +
                        "$lastCorruptionReason (keyframe decoded)", report
                )
                concealWindowOpenedMs = 0L
            }
            CorruptionConcealmentPolicy.Outcome.CLOSE_EXPIRED -> {
                concealState = CorruptionConcealmentPolicy.State.DISARMED
                logConcealment(
                    nowMs,
                    "VideoDecoder: no keyframe within " +
                        "${CorruptionConcealmentPolicy.CONCEAL_MAX_MS}ms of " +
                        "$lastCorruptionReason - resuming on the damaged stream", report
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
    private fun logConcealment(nowMs: Long, message: String, report: (String) -> Unit) {
        if (AuditReportPolicy.shouldReport(concealReports, concealLastLogMs, nowMs)) {
            val suppressed = concealSuppressed
            concealSuppressed = 0
            concealReports++
            concealLastLogMs = nowMs
            val suffix =
                if (suppressed > 0) " (and $suppressed window events since the last report)" else ""
            report(message + suffix)
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

    /**
     * Reported on the same window as the throughput line, so the two figures are read together: the
     * point is comparing one drive against another, and a latency without the frame rate beside it
     * does not support that.
     *
     * Sampled and reported from the output thread. [stop] resets it from whichever thread is
     * stopping, which can only garble the window being torn down - every field it touches stays in
     * range whatever the interleaving, so a diagnostic is not worth a lock on the render path.
     */
    private val decodeLatency = DecodeLatencyMonitor()

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

    /**
     * Whether what is on the surface is a picture a keyframe accounts for, rather than output
     * decoded from P-frames alone. See [PictureCredibilityPolicy].
     */
    val hasCrediblePicture: Boolean
        get() = !recoveryContinuityLost && PictureCredibilityPolicy.hasCrediblePicture(
            renderedSinceCodecStart = lastFrameRenderedMs != 0L,
            keyframeAccountingAvailable = !usingBundledSoftwareVideo,
            keyframeDecodedSinceCodecStart = keyframeRepair.keyframeDecoded,
            msSincePendingKeyframeFed = pendingKeyframeAgeMs(),
        )

    private fun pendingKeyframeAgeMs(): Long {
        val fedAtMs = lastKeyframeFedMs
        if (!keyframeRepair.awaitingOutput || fedAtMs == 0L) return Long.MAX_VALUE
        return elapsedRealtime() - fedAtMs
    }

    enum class CodecType(val mimeType: String, val displayName: String, val settingsValue: String, val ffmpegCodecId: Int) {
        // Stable JNI IDs; never use enum ordinals at the native boundary.
        H264("video/avc", "H.264/AVC", "H.264", 1),
        H265("video/hevc", "H.265/HEVC", "H.265", 2)
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
    private inline fun <T> publishOutput(owner: Thread, update: () -> T): T? =
        synchronized(outputPublicationLock) {
            if (!running || outputThread !== owner) null else update()
        }

    private fun isCurrentFeed(owner: FeedOwner): Boolean =
        running && feedThread === owner.thread && decoderGeneration == owner.generation

    private inline fun <T> publishFeed(owner: FeedOwner, update: () -> T): T? =
        synchronized(outputPublicationLock) {
            if (isCurrentFeed(owner)) update() else null
        }

    internal class OutputEvents(private val origin: String = "VideoDecoder.outputThreadLoop") {
        private data class Event(val callback: Boolean, val action: () -> Unit)
        private val pending = ArrayList<Event>()
        fun info(message: String, source: String = origin) { log(android.util.Log.INFO, source, message) }
        fun warn(message: String, source: String = origin) { log(android.util.Log.WARN, source, message) }
        fun error(message: String) { log(android.util.Log.ERROR, origin, message) }
        private fun log(priority: Int, source: String, message: String) {
            pending.add(Event(false) { AppLog.tagged(priority, source, message) })
        }
        fun callback(action: (() -> Unit)?) { if (action != null) pending.add(Event(true, action)) }
        fun dispatch(isCurrent: () -> Boolean) {
            try {
                for (event in pending) {
                    // Logs describe work already completed. Only callbacks can mutate a new owner.
                    if (!event.callback || isCurrent()) event.action()
                }
            } finally { pending.clear() }
        }
    }

    private fun handleOutputFormatChange(owner: MediaCodec, format: MediaFormat) {
        val self = Thread.currentThread()
        val (newWidth, newHeight) = displaySizeOf(format)
        val events = OutputEvents("VideoDecoder.handleOutputFormatChange")
        publishOutput(self) {
            events.info("Output Format Changed: $format")
            if (mWidth != newWidth || mHeight != newHeight) {
                mWidth = newWidth
                mHeight = newHeight
                events.info("Video dimensions changed via format: ${newWidth}x$newHeight")
            }
            // Every codec publishes its own output format, even when its size matches the
            // previous one. That previous worker may have retired after storing the size but
            // before dispatching its UI notification. Reannounce from the current owner so a
            // retained surface learns the size without allowing retired callbacks through.
            if (newWidth > 0 && newHeight > 0) {
                dimensionsListener?.let { listener ->
                    events.callback { listener.onVideoDimensionsChanged(newWidth, newHeight) }
                }
            }
        } ?: return
        events.dispatch { running && outputThread === self }
        if (!running || outputThread !== self) return
        try {
            owner.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
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
            detachedSurface = null
            lastFrameRenderedMs = 0L
            keyframeRepair.reset()
            lastKeyframeFedMs = 0L
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
    fun isCurrentSurface(surface: Surface): Boolean = synchronized(this) {
        mSurface === surface || (mSurface == null && detachedSurface === surface)
    }

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

    /** Retire the target before stopping, so incoming video cannot reopen it during teardown. */
    // View retirement precedes the Activity callback, which must still recognize its target.
    fun detachSurfaceIfCurrent(surface: Surface, reason: String): Boolean = synchronized(this) {
        if (mSurface == null && detachedSurface === surface) return true
        if (mSurface !== surface) {
            AppLog.i("Decoder detach ($reason) skipped: surface is no longer current")
            return false
        }
        detachedSurface = surface
        mSurface = null
        stop(reason)
        true
    }

    /** Changes only when lifecycle teardown loses references while a Surface can survive. */
    @Volatile internal var surfaceStopGeneration = 0L
        private set

    /** What this session fed, dropped, skipped and rendered, plus whether it had to fall back. */
    fun sessionHealth(): SessionHealth = SessionHealth(
        framesFed = framesFed,
        framesDropped = framesDropped,
        framesSkipped = framesSkippedAtRender,
        framesRendered = framesRenderedThisSession,
        codecFallbackUsed = codecFallbackUsed,
        finalCodec = currentCodecType,
    )

    /** Clears the session-scoped health counters before a new projection starts feeding frames. */
    fun beginSession() {
        framesRenderedThisSession = 0L
        framesFed = 0L
        framesDropped = 0L
        framesSkippedAtRender = 0L
        codecFallbackUsed = false
        restartsSinceLastFrame = 0
    }

    /** Revokes the owner immediately; physical shutdown waits off-thread for its native work. */
    fun stop(reason: String = "unknown") {
        synchronized(this) {
            if (reason == DecoderStopPolicy.REASON_ACTIVITY_STOPPED ||
                reason == DecoderStopPolicy.REASON_SCREEN_OFF_SLEEP) {
                surfaceStopGeneration++
            }
            var retiredFeed: Thread? = null
            val retiredQueue = frameQueue
            val retiredOutput = synchronized(outputPublicationLock) {
                running = false
                decoderNeedsRestart = false
                decoderRestartReason = null
                decoderGeneration++
                recoveryInput = null
                recoveryContinuityLost = false
                retiredFeed = feedThread
                feedThread = null
                // A retired poll must never receive replacement input, even when the
                // configured capacity has not changed.
                if (retiredFeed != null || frameQueue.isNotEmpty()) {
                    frameQueue = ArrayBlockingQueue(frameQueueCapacity)
                }
                outputThread.also { outputThread = null }
            }
            // Pooled frames grow to the largest ever seen and never shrink, so a 4K session would
            // otherwise pin that size for the life of the process.
            synchronized(outputPublicationLock) {
                framePool.clear()
                pooledBytes.set(0)
            }

            val resources = nativeResources ?: if (codec != null || softwareVideoDecoder != null ||
                retiredFeed != null || retiredOutput != null) NativeResources(codec, softwareVideoDecoder) else null
            nativeResources = null
            if (resources != null) {
                pendingRetirements++
                // Admission stays closed until this single owner's retirement finishes. Repeated
                // stops cannot create cleanup threads or allocate replacement codecs behind it.
                Thread {
                    // Wait for a configure/software decode already admitted before revocation.
                    // Drop this lock before joining: a worker callback may reenter decode().
                    nativeWorkLock.withLock {}
                    try {
                        retiredFeed?.interrupt()
                        retiredOutput?.interrupt()
                        joinRetiredWorker(retiredFeed)
                        joinRetiredWorker(retiredOutput)
                        retiredQueue.clear()
                        try { resources.codec?.stop() } catch (_: Exception) {}
                        try { resources.codec?.release() }
                        catch (e: Exception) { AppLog.e("Error releasing decoder", e) }
                        try { resources.software?.stop() }
                        catch (e: Exception) { AppLog.e("Error releasing software video decoder", e) }
                    } finally {
                        val completion = synchronized(this) {
                            pendingRetirements--
                            if (pendingRetirements == 0) {
                                retirementCompleteListener.also { retirementCompleteListener = null }
                            } else null
                        }
                        try { completion?.invoke() } finally {
                            nativeWorkLock.withLock { replayRecoveryInput() }
                        }
                    }
                }.apply { name = "VideoDecoder-Retire"; isDaemon = true }.start()
            }

            codec = null
            softwareVideoDecoder = null
            inputBuffers = null
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
                parameterSets.clear()
                mWidth = 0
                mHeight = 0
                loggedParameterSet = false
                // Session-scoped, like everything else in this block: a restart does not change what
                // the phone is sending, so a change reported across one would be reporting the
                // tracker's own amnesia.
                parameterSetTracker.reset()
                loggedContentKinds.clear()
            }
            lastFrameRenderedMs = 0L
            // Presentation timestamps restart near zero on the next configure, so a stamp left
            // pending here would be confirmed by the new codec's very first frame. The same restart
            // is why the latency samples go: they were measured against the old session clock.
            keyframeRepair.reset()
            lastKeyframeFedMs = 0L
            decodeLatency.reset()
            resetConcealment()
            lastKeyframeStarvedAskMs = 0L
            loggedFirstSoftwareFrame = false
            loggedFirstHardwareFrame = false
            loggedInputBufferCapacity = false
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
            framesPresented = 0L
            framesSkippedAtRender = 0L
            inputWaitMs = 0L
            enqueueWaitMs = 0L
            lastDropKeyframeRequestMs = 0L
            lastThroughputLogMs = 0L
            lastLoggedFramesFed = 0L
            lastLoggedFramesDropped = 0L
            lastLoggedFramesRendered = 0L
            lastLoggedFramesPresented = 0L
            lastLoggedFramesSkippedAtRender = 0L
            lastLoggedInputWaitMs = 0L
            lastLoggedEnqueueWaitMs = 0L
            AppLog.i("Decoder stopped: $reason")
        }
    }

    private fun joinRetiredWorker(worker: Thread?) {
        if (worker == null) return
        while (worker.isAlive) {
            try { worker.join() } catch (_: InterruptedException) { /* Native work is not cancelled. */ }
        }
    }

    private fun scheduleRestart(reason: String) {
        decoderRestartReason = reason
        decoderNeedsRestart = true
    }

    /**
     * Main entry point for decoding a video/control packet.
     *
     * Reports nothing back to the caller, but may pace it: with the feed queue full this blocks
     * the calling thread for up to [VideoFeedThrottlePolicy.WAIT_BUDGET_MS], which is what
     * throttles the phone through its ack window - see [offerWithBackpressure]. A frame that
     * outlasts even that wait is lost here, and once the picture is live the loss asks the phone
     * for a keyframe - see [recordFrameDropped] for the shape of that ask and why it stays silent
     * before the first rendered frame.
     */
    fun decode(buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, announcedCodec: CodecType) {
        nativeWorkLock.withLock {
            // Live input must not overtake the retained restart picture when retirement finishes.
            replayRecoveryInput()
            decodeLocked(buffer, offset, size, forceSoftware, announcedCodec)
        }
    }

    private fun decodeLocked(
        buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, announcedCodec: CodecType,
        recovery: RecoveryInput? = null,
    ) {
        var request: StartRequest? = null
        val generation = synchronized(this) {
            if (recovery != null &&
                (decoderGeneration != recovery.generation || mSurface !== recovery.surface)) return
            if (recovery?.continuityLost == true) recoveryContinuityLost = true
            // Input-side liveness: bytes are arriving from the phone right now.
            if (recovery == null) lastInputBytesReceivedMs = elapsedRealtime()

            // Check if a restart was requested by output thread
            if (decoderNeedsRestart) {
                AppLog.w("Decoder restart requested: $decoderRestartReason")

                // Bound repeated cold-start failures, but never change the stream's codec to
                // recover a decoder. That would feed the same bytes to an incompatible decoder.
                // A session that already rendered is excluded from this cold-start limit.
                if (DecoderRestartPolicy.countsTowardFailure(
                        renderedSinceLastStart = lastFrameRenderedMs != 0L,
                        renderedThisSession = renderedThisSession
                    )
                ) {
                    restartsSinceLastFrame++
                    if (restartsSinceLastFrame >= MAX_RESTARTS_WITHOUT_FRAME) {
                        AppLog.e("${currentCodecType.displayName} failed $restartsSinceLastFrame times without rendering a frame. Stopping initialization retries for this codec.")
                        decoderPermanentlyFailed = true
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

            // Strong stream evidence belongs to the session, not to a successfully created owner.
            // Keep it while a surface is absent or the first decoder creation fails.
            if (codec == null && softwareVideoDecoder == null && !codecTypePinned) {
                val detectedType = CodecTypeSelectionPolicy.detect(buffer, offset, size)
                val selected = CodecTypeSelectionPolicy.select(detectedType, announcedCodec)
                if (selected != currentCodecType) {
                    if (detectedType != null) {
                        // A proven different codec has not spent the previous codec's budget.
                        restartsSinceLastFrame = 0
                        decoderPermanentlyFailed = false
                    }
                    parameterSets.clear()
                    mWidth = 0
                    mHeight = 0
                }
                currentCodecType = selected
                codecTypePinned = detectedType != null
                if (selected != announcedCodec) {
                    codecFallbackUsed = true
                    AppLog.w(
                        "Stream parameter sets identify ${selected.displayName}, but this session " +
                            "announced ${announcedCodec.displayName}; using the stream codec"
                    )
                }
            }

            // Capture the entire access unit on every backend, including while an owner is
            // running or absent. Recovery must retain all configurations the live decoder saw.
            scanAndApplyConfig(buffer, offset, size, currentCodecType)
            if (decoderPermanentlyFailed) return

            if (codec == null && softwareVideoDecoder == null) {
                if (mWidth == 0) {
                    val negotiatedW = HeadUnitScreenConfig.getNegotiatedWidth()
                    val negotiatedH = HeadUnitScreenConfig.getNegotiatedHeight()
                    if (negotiatedW > 0 && negotiatedH > 0) {
                        AppLog.i("Fallback to negotiated dimensions: ${negotiatedW}x${negotiatedH}")
                        mWidth = negotiatedW
                        mHeight = negotiatedH
                        dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                    }
                }

                if (mSurface == null || !mSurface!!.isValid) return
                if (mWidth == 0 || mHeight == 0) return

                // Retain one owned AU while disposal stalls. Prefer a decodable keyframe to
                // dependent slices, so the restart-triggering IDR (or its requested replacement)
                // still produces a picture if the phone goes idle before retirement finishes.
                if (pendingRetirements != 0) {
                    retainRecoveryInput(buffer, offset, size, forceSoftware, announcedCodec)
                    return
                }
                refreshPipelineSizing()
                val resources = NativeResources()
                nativeResources = resources
                request = StartRequest(resources, decoderGeneration, mSurface!!, currentCodecType,
                    mWidth, mHeight, settings.forceSoftwareDecoding || forceSoftware)
            }

            decoderGeneration
        }
        request?.let {
            if (VideoDecoderBackendPolicy.useBundled(
                    it.type, it.forceSoftware, settings.softwareVideoDecoder, bundledDecoderAvailable
                )) {
                startBundledVideo(it)
            } else {
                start(it)
            }
        }
        val software: FfmpegVideoDecoder?
        val frame = synchronized(this) {
            if (generation != decoderGeneration) return
            software = softwareVideoDecoder
            if (software != null) null else {
                if (codec == null) return
                if (recovery != null) PendingFrame(buffer, size, System.nanoTime(), generation,
                    repairEligible = !recovery.continuityLost)
                else prepareFrame(buffer, offset, size)
            }
        }
        software?.let { decoder ->
            val events = OutputEvents("VideoDecoder.softwareDecode")
            val isCurrent = { generation == decoderGeneration && softwareVideoDecoder === decoder }
            val renderedFrames = try {
                decoder.decode(buffer, offset, size, repairEligible = recovery?.continuityLost != true)
            } catch (e: Exception) {
                synchronized(outputPublicationLock) {
                    if (isCurrent()) scheduleRestart("software_video_output_exception")
                }
                AppLog.e("Bundled software output failed", e)
                return
            }
            synchronized(outputPublicationLock) {
                if (!isCurrent()) return
                if (renderedFrames > 0) {
                    if (decoder.outputWidth > 0 && decoder.outputHeight > 0 &&
                        (mWidth != decoder.outputWidth || mHeight != decoder.outputHeight)) {
                        mWidth = decoder.outputWidth
                        mHeight = decoder.outputHeight
                        val width = mWidth
                        val height = mHeight
                        dimensionsListener?.let { listener ->
                            events.callback { listener.onVideoDimensionsChanged(width, height) }
                        }
                    }
                    onSoftwareFramesRendered(renderedFrames, events, generation, decoder)
                    if (decoder.keyframeRendered) {
                        recoveryContinuityLost = false
                        events.callback(onKeyframeObserved)
                    }
                } else if (renderedFrames < 0) {
                    events.error("Bundled software decoder failed with code $renderedFrames")
                    scheduleRestart("software_video_error_$renderedFrames")
                }
            }
            events.dispatch(isCurrent)
        }

        // Outside the monitor on purpose: stop() synchronizes on this object, and a wait held
        // inside it would block the teardown for up to the whole budget. Out here stop() clears
        // running and the wait aborts within one slice.
        frame?.let { offerWithBackpressure(it) }
    }

    // Both methods run under nativeWorkLock; state ownership is still checked under the decoder
    // monitor because a UI stop never waits for that native lock.
    private fun retainRecoveryInput(
        buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, announcedCodec: CodecType,
    ) {
        if (size <= 0) return
        val surface = mSurface ?: return
        val keyframe = VideoKeyframeScanner.containsKeyframe(buffer, offset, size, currentCodecType == CodecType.H265)
        val previous = recoveryInput
        if (previous?.keyframe == true && !keyframe) {
            if (CodecConfigScanner.classify(buffer, offset, size, currentCodecType == CodecType.H265) !=
                CodecConfigScanner.Content.PARAMETER_SETS_ONLY) {
                previous.continuityLost = true
                synchronized(outputPublicationLock) { framesDropped++ }
            }
            return
        }
        val pending = previous ?: RecoveryInput(decoderGeneration, surface, ByteArray(size),
            size, keyframe, forceSoftware, announcedCodec, parameterSets.revision).also { recoveryInput = it }
        if (pending.bytes.size < size) pending.bytes = ByteArray(size)
        System.arraycopy(buffer, offset, pending.bytes, 0, size)
        pending.size = size
        pending.keyframe = keyframe
        pending.continuityLost = false
        pending.forceSoftware = forceSoftware
        pending.announcedCodec = announcedCodec
        pending.configurationRevision = parameterSets.revision
    }

    private fun replayRecoveryInput() {
        var configurationChanged = false
        val pending = synchronized(this) {
            if (pendingRetirements != 0) return
            val pending = recoveryInput.also { recoveryInput = null } ?: return
            if (decoderGeneration != pending.generation || mSurface !== pending.surface ||
                !pending.surface.isValid) return
            if (pending.configurationRevision != parameterSets.revision) {
                // A newer configuration can reuse an SPS/PPS ID. Replaying the older AU would
                // overwrite that session cache (and the native decoder's in-band state). Keep
                // the newest configuration and recover from a fresh keyframe instead. Even a
                // new, independent ID invalidates conservatively; no slice dependency guess.
                configurationChanged = true
                pending.continuityLost = true
                synchronized(outputPublicationLock) {
                    recoveryContinuityLost = true
                    framesDropped++
                }
            }
            pending
        }
        if (!configurationChanged) {
            decodeLocked(pending.bytes, 0, pending.size, pending.forceSoftware, pending.announcedCodec, pending)
        }
        if (pending.continuityLost) {
            // An older IDR restores a still picture, not references discarded after it. It must
            // not acknowledge transport repair. Keep normal live admission and ask for a newer
            // keyframe through the existing lost-reference recovery path.
            val events = OutputEvents("VideoDecoder.retirementGap")
            events.callback(onFrameDropped)
            events.dispatch {
                decoderGeneration == pending.generation && mSurface === pending.surface &&
                    pending.surface.isValid && recoveryContinuityLost
            }
        }
    }

    /**
     * Copies a frame into a pooled buffer while the transport's bytes are still valid - the
     * caller reuses the buffer it handed us the moment decode() returns, so the copy has to
     * happen before the monitor is released.
     */
    private fun prepareFrame(frameData: ByteArray, frameOffset: Int, size: Int): PendingFrame? {
        // An empty frame reaches the codec as nothing at all, so queueing it would only inflate
        // the fed count that the throughput line uses to say whether frames arrived.
        if (size <= 0) return null

        val frame = borrowFrame(size)

        System.arraycopy(frameData, frameOffset, frame.data, 0, size)
        frame.size = size
        frame.generation = decoderGeneration
        frame.repairEligible = true
        // Stamped here, not where the frame is handed over: the feed thread drains a backlog in
        // a few milliseconds, so timestamps taken there would give a dozen frames near-identical
        // values and flatten the cadence the codec sees.
        frame.arrivalNanos = System.nanoTime()
        return frame
    }

    /**
     * Places the frame on the feed queue, pacing the transport's read thread when it is full.
     *
     * The bounded stall is the point, not a hazard. While this thread waits, no further messages
     * are read or acked, so the phone's unacked-message window closes and the phone slows to the
     * rate the codec actually drains - the protocol's own flow control, doing the throttling that
     * shedding used to fake. A full queue plus an arriving frame only ever happens when the phone
     * is outrunning the codec, so in steady state the wait per frame is one frame period, and a
     * link-stall backlog (phone silent, queue draining) never blocks here at all.
     *
     * Every frame shed by the old immediate drop was a reference some later frame predicted from,
     * so each one cost a washed-out, blocky picture until the phone's own keyframe - and the phone
     * runs a fixed keyframe period measured at ~69s, beyond anything the focus-cycle ladder can
     * repair when the drops keep coming.
     *
     * Budget expiry still sheds the frame: the feed thread's own give-up frees a slot every
     * [VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS], so outlasting the budget means the codec
     * took nothing for the whole wait - a wedge, which belongs to the sync_stall watchdog, with
     * [recordFrameDropped]'s keyframe ask covering the picture that frame cost.
     */
    private fun offerWithBackpressure(frame: PendingFrame) {
        val generation = frame.generation
        // Read once: this runs outside the monitor, and a session start on the same thread can
        // have replaced the queue between two reads of the field.
        val queue = synchronized(outputPublicationLock) {
            if (!running || generation != decoderGeneration) return
            if (frameQueue.offer(frame)) return
            frameQueue
        }

        val waitStart = elapsedRealtime()
        var accepted = false
        var elapsed = 0L
        while (!accepted && generation == decoderGeneration &&
            VideoFeedThrottlePolicy.shouldKeepWaiting(elapsed, running)) {
            accepted = try {
                queue.offer(frame, VideoFeedThrottlePolicy.OFFER_SLICE_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            elapsed = elapsedRealtime() - waitStart
        }

        // Counted on both outcomes. The question this field answers is how long the transport was
        // paced, and a wait that ran out the budget paced it for the whole budget - counting only
        // the admitted ones would zero the largest waits there are and make a wedge read as a
        // session that never waited at all.
        val events = if (accepted) null else OutputEvents("VideoDecoder.offerWithBackpressure")
        val pacingMessage = synchronized(outputPublicationLock) {
            if (generation != decoderGeneration || !running) {
                if (accepted) queue.remove(frame)
                return
            }
            enqueueWaitMs += elapsedRealtime() - waitStart
            if (accepted) {
                feedPacingMessage()
            } else {
                recycleFrame(frame)
                recordFrameDropped(checkNotNull(events))
                null
            }
        }
        pacingMessage?.let { AppLog.i(it) }
        events?.dispatch { running && generation == decoderGeneration }
    }

    /**
     * Reports that the read thread is being paced, at most once per [FEED_DROP_LOG_INTERVAL_MS].
     *
     * Called from the transport's read thread only. The throughput line's `enqueueWait` carries
     * the magnitude; this line only says it began.
     */
    private fun feedPacingMessage(): String? {
        val now = elapsedRealtime()
        if (lastFeedPacingLogMs != 0L && now - lastFeedPacingLogMs < FEED_DROP_LOG_INTERVAL_MS) {
            suppressedFeedPacingLogs++
            return null
        }
        lastFeedPacingLogMs = now
        val alsoSuppressed =
            if (suppressedFeedPacingLogs > 0) " (+$suppressedFeedPacingLogs more since the last line)" else ""
        suppressedFeedPacingLogs = 0
        return "Feed queue full - pacing the transport thread instead of shedding frames$alsoSuppressed"
    }

    /** Takes a buffer from the pool, growing it if this frame does not fit, or allocates one. */
    private fun borrowFrame(size: Int): PendingFrame {
        val pooled = synchronized(outputPublicationLock) {
            framePool.poll()?.also { pooledBytes.addAndGet(-it.data.size) }
        }
        if (pooled != null) {
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
        synchronized(outputPublicationLock) {
            if (frame.generation != decoderGeneration) return
            val bytes = frame.data.size
            if (pooledBytes.get().toLong() + bytes > pooledByteBudget.toLong()) return
            if (framePool.offer(frame)) pooledBytes.addAndGet(bytes)
        }
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
     * that [com.andrerinas.openheadunit.decoder.video.KeyframeCycleEscalationPolicy] uses to decide whether
     * that was enough. There is still no P-frame latch here and never will be: gating the feed on
     * keyframe detection is what once made recovering from a drop more expensive than the drop.
     *
     * Before the first rendered frame this stays silent - a warming codec sheds frames while
     * perfectly healthy, and that window belongs to
     * [com.andrerinas.openheadunit.decoder.video.WarmRelaunchKeyframePolicy], which must not have a second
     * decider reaching for the same lever underneath it.
     */
    private fun recordFrameDropped(events: OutputEvents) {
        framesDropped++
        val now = elapsedRealtime()
        if (VideoRecoveryPolicy.shouldRequestOnDroppedFrame(lastFrameRenderedMs != 0L, now, lastDropKeyframeRequestMs)) {
            lastDropKeyframeRequestMs = now
            events.warn("VideoDecoder: dropped a reference frame, requesting keyframe")
            events.callback(onFrameDropped)
        }
    }

    /**
     * Drains the feed queue into its captured codec without the decoder monitor. Retirement
     * revokes publication first and waits for this entire loop before native disposal.
     */
    private fun feedThreadLoop() {
        val self = Thread.currentThread()
        val owner = synchronized(outputPublicationLock) {
            if (!running || feedThread !== self) return
            FeedOwner(self, codec ?: return, inputBuffers, startTime, currentCodecType, frameQueue, decoderGeneration)
        }
        AppLog.i(
            "Feed thread started (queue holds $frameQueueCapacity frames, " +
                "${VideoFeedQueuePolicy.heldMsAt(settings.fpsLimit)}ms at ${settings.fpsLimit}fps)"
        )
        // Read once, not per frame: avoid SharedPreferences on the hot path and do not silently
        // change a running session's debug hold after its startup diagnostic has been emitted.
        val feedHoldMs = settings.debugVideoFeedHoldMs
        if (feedHoldMs > 0) {
            AppLog.w("Feed thread: DEBUG hold ${feedHoldMs}ms per frame - simulating a decoder slower " +
                "than the stream. No real fault looks like this line.")
        }
        val events = OutputEvents("VideoDecoder.feedThreadLoop")
        val isCurrent = { isCurrentFeed(owner) }
        while (isCurrentFeed(owner)) {
            val frame = try {
                owner.queue.poll(FEED_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            try {
                if (frame.generation != owner.generation || !FeedLoopPolicy.shouldFeed(
                        running, feedThread === owner.thread, codec === owner.codec, decoderNeedsRestart
                    )) continue
                val result = feedInputBuffer(owner, events, ByteBuffer.wrap(frame.data, 0, frame.size),
                    frame.arrivalNanos, frame.repairEligible)
                publishFeed(owner) {
                    when (result) {
                        FeedResult.FED -> framesFed++
                        // Errors and oversized inputs already have their own diagnostics. Calling
                        // logFeedDrop here would incorrectly relabel them as a full input buffer.
                        FeedResult.DROPPED_TOO_LARGE, FeedResult.ERROR -> recordFrameDropped(events)
                        FeedResult.NO_INPUT_BUFFER -> if (!decoderNeedsRestart) {
                            // Only a current, non-restarting worker reports saturation; shutdown
                            // also interrupts admission but must not request a keyframe on every stop.
                            // recordFrameDropped stays quiet before the first rendered picture.
                            // Persistent lack of progress still belongs to the output watchdog.
                            logFeedDrop(events)
                            recordFrameDropped(events)
                        }
                    }
                }
                events.dispatch(isCurrent)
                // Hold only after accepted input, so the knob models a slow consumer without
                // consuming the codec's own input-dequeue patience budget.
                if (result == FeedResult.FED && feedHoldMs > 0 && isCurrentFeed(owner)) {
                    try { Thread.sleep(feedHoldMs.toLong()) }
                    catch (_: InterruptedException) { break }
                }
            } catch (e: Exception) {
                AppLog.w("Feed thread error: ${e.message}")
            } finally {
                recycleFrame(frame)
            }
        }
        events.dispatch(isCurrent)
        AppLog.i("Feed thread stopped")
    }

    /**
     * Reports a frame the codec had no room for, at most once per [FEED_DROP_LOG_INTERVAL_MS].
     *
     * Called by the current feed worker under the publication lock; diagnostics dispatch afterward.
     */
    private fun logFeedDrop(events: OutputEvents) {
        val now = elapsedRealtime()
        if (lastFeedDropLogMs != 0L && now - lastFeedDropLogMs < FEED_DROP_LOG_INTERVAL_MS) {
            suppressedFeedDropLogs++
            return
        }
        lastFeedDropLogMs = now
        val alsoSuppressed = if (suppressedFeedDropLogs > 0) " (+$suppressedFeedDropLogs more since the last line)" else ""
        suppressedFeedDropLogs = 0
        events.warn("Input buffer full. Dropping frame.$alsoSuppressed")
    }

    /**
     * Re-reads what sizes the pipeline: the user's fps cap and the device's memory.
     *
     * Called while reserving a new owner under the decoder monitor, after the previous owner's
     * physical retirement. Swapping the empty queue cannot race an old producer. A changed depth
     * throws the pooled buffers away with the old queue, which is why pooledBytes is reset with it.
     */
    private fun refreshPipelineSizing() {
        memoryReading = readMemory()
        constrained = memoryReading.profile == DeviceMemoryProfile.CONSTRAINED
        minPooledFrameBytes = if (constrained) MIN_POOLED_FRAME_BYTES_CONSTRAINED else MIN_POOLED_FRAME_BYTES
        pooledByteBudget = if (constrained) CONSTRAINED_POOL_BUDGET_BYTES else Int.MAX_VALUE

        val capacity = VideoFeedQueuePolicy.capacityFor(settings.fpsLimit)
        if (capacity == frameQueueCapacity) return
        AppLog.i(
            "VideoDecoder: feed queue resized $frameQueueCapacity -> $capacity frames " +
                "(${VideoFeedQueuePolicy.heldMsAt(settings.fpsLimit)}ms at ${settings.fpsLimit}fps)"
        )
        frameQueueCapacity = capacity
        frameQueue = ArrayBlockingQueue<PendingFrame>(capacity)
        framePool = ArrayBlockingQueue<PendingFrame>(capacity + 2)
        pooledBytes.set(0)
    }

    /** Empties the feed queue back into the pool. Anything still waiting is stale after a stop. */
    private fun clearFrameQueue() {
        while (true) {
            val frame = frameQueue.poll() ?: break
            recycleFrame(frame)
        }
    }

    private fun isCurrentStart(request: StartRequest): Boolean =
        decoderGeneration == request.generation && nativeResources === request.resources

    private fun failStart(request: StartRequest, reason: String) {
        synchronized(this) {
            if (!isCurrentStart(request)) return
            // The same retirement path owns partial initialization; no native cleanup under
            // the decoder monitor, and no retry until that cleanup really completes.
            stop("restart: $reason")
            if (mSurface?.isValid == true) scheduleRestart(reason)
        }
    }

    private fun startBundledVideo(request: StartRequest) {
        val type = request.type
        try {
            val cachedSets = synchronized(this) {
                if (!isCurrentStart(request)) return
                Array(parameterSets.lastIndex + 1) { parameterSets[it] }
            }
            AppLog.i("Configuring bundled FFmpeg ${type.displayName} decoder for ${request.width}x${request.height}")
            val yuvFrameSink = softwareYuvFrameSink
            val decoder = FfmpegVideoDecoder(
                codecType = type,
                surface = if (yuvFrameSink == null) request.surface else null,
                yuvFrameSink = yuvFrameSink,
                width = request.width,
                height = request.height,
                isCurrent = { decoderGeneration == request.generation },
            )
            request.resources.software = decoder
            if (!decoder.start()) throw IllegalStateException("bundled decoder failed to initialize")
            for (data in cachedSets) {
                if (request.generation != decoderGeneration) return
                if (data != null && decoder.decode(data, 0, data.size) < 0) {
                    throw IllegalStateException("cached parameter sets rejected")
                }
            }
            synchronized(this) {
                if (!isCurrentStart(request)) return
                softwareVideoDecoder = decoder
                currentCodecName = if (type == CodecType.H264) "ffmpeg-h264" else "ffmpeg-hevc"
                running = true
                startTime = System.nanoTime()
                codecTypePinned = true
            }
            AppLog.i("Bundled FFmpeg ${type.displayName} decoder initialized")
        } catch (e: Exception) {
            AppLog.e("Failed to start bundled FFmpeg ${type.displayName} decoder", e)
            failStart(request, "bundled_video_start_failed: ${e.message}")
        }
    }


    private fun onSoftwareFramesRendered(
        renderedFrames: Int, events: OutputEvents, generation: Long, decoder: FfmpegVideoDecoder,
    ) {
        lastFrameRenderedMs = elapsedRealtime()
        renderedThisSession = true
        framesRenderedThisSession += renderedFrames
        if (!loggedFirstSoftwareFrame) {
            loggedFirstSoftwareFrame = true
            events.info("First bundled software ${currentCodecType.displayName} frame rendered")
        }
        val expected = onFirstFrameListener
        if (expected != null) events.callback {
            // Claim only at dispatch; retirement leaves the notification for the next owner.
            val claimed = synchronized(outputPublicationLock) {
                if (generation == decoderGeneration && softwareVideoDecoder === decoder &&
                    onFirstFrameListener === expected) {
                    onFirstFrameListener = null
                    expected
                } else null
            }
            claimed?.invoke()
        }

        frameCount += renderedFrames
        // The bundled decoder has no output thread, so drive the throughput tick from here. It
        // hands the sink one frame at a time, so nothing is batched and presented equals rendered
        // on this path.
        framesRendered += renderedFrames
        framesPresented += renderedFrames
        logThroughput({ events.info(it) }, { events.warn(it) })
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsLogTime
        if (elapsed >= 1000) {
            if (lastFpsLogTime != 0L) {
                val fps = (frameCount * 1000 / elapsed).toInt()
                onFpsChanged?.let { listener -> events.callback { listener(fps) } }
            }
            frameCount = 0
            lastFpsLogTime = now
        }
    }

    /**
     * Walks the full access unit and exposes only parameter-set ranges, without copying picture data.
     */
    private inline fun forEachParameterSet(buffer: ByteArray, offset: Int, size: Int, type: CodecType, callback: (Int, Int, Int) -> Unit) {
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

                val header = nalStart + startCodeLen
                if (header < nalEnd) {
                    val first = buffer[header].toInt() and 0xFF
                    val kind = if (type == CodecType.H265) (first and 0x7E) shr 1 else first and 0x1F
                    val parameterSet = if (type == CodecType.H265) kind in 32..34 else kind == 7 || kind == 8
                    if (parameterSet) {
                        callback(header, nalEnd - header, kind)
                    }
                }
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
        val seedDimensions = codec == null && softwareVideoDecoder == null && !renderedThisSession
        if (!seedDimensions && loggedParameterSet) return
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

        if (seedDimensions) applyStreamDimensions(parsedWidth, parsedHeight, type)

        if (loggedParameterSet) return
        loggedParameterSet = true
        if (summary != null) {
            AppLog.i("Stream SPS (${type.settingsValue}): $summary")
        } else {
            AppLog.w("Stream SPS (${type.settingsValue}): could not be parsed ($length bytes)")
        }
    }

    /**
     * Seeds initial configuration from an SPS; it does not identify the active picture's SPS.
     *
     * H.265 had no parser at all, so `mWidth` stayed zero and every H.265 session configured its
     * codec from `HeadUnitScreenConfig`'s negotiated size - which is what the phone was *asked* for,
     * not necessarily what it sent. That is what the "Fallback to negotiated dimensions" line reports,
     * and on an H.265 session it was reporting every time.
     *
     * Bounded rather than trusted, in the same spirit as the crop-rectangle check: a parse that comes
     * back with something outside any resolution a head unit projects is more likely a desync than a
     * discovery, and the negotiated size is the better guess in that case. Actual MediaCodec
     * output format or FFmpeg frame dimensions remain authoritative. Receiving another SPS
     * while playing must not change the displayed size before a picture uses it.
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
        forEachParameterSet(buffer, offset, size, type) { header, length, kind ->
            val previous = parameterSets.latest(kind)
            val data = parameterSets.store(buffer, header, length, type == CodecType.H265, kind)
                ?: return@forEachParameterSet
            if ((kind == 7 || kind == 33) && data !== previous) {
                applyParameterSet(data, 4, type)
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

        // Deliberately no KEY_FRAME_RATE / KEY_MAX_B_FRAMES here. The frame-rate keys are inert at
        // best, because the only frame rate this class knows is settings.fpsLimit - the user's cap,
        // not the rate the phone negotiated - and KEY_MAX_B_FRAMES is an encoder key.
        //
        // KEY_PRIORITY and KEY_OPERATING_RATE were removed from here for the same reason and it did
        // not hold; they are on the ladder's optional rungs now. See DecoderConfigLadder.

        // Apply Codec Specific Data (CSD) from parsed SPS/PPS/VPS
        if (mimeType == CodecType.H265.mimeType) {
            parameterSets.codecSpecificData(hevc = true, index = 0)?.let {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
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
            val sequenceData = parameterSets.codecSpecificData(hevc = false, index = 0)
            val pictureData = parameterSets.codecSpecificData(hevc = false, index = 1)
            if (sequenceData != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sequenceData))
            if (pictureData != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pictureData))

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
    private fun logDecoderCapability(codecName: String, mimeType: String, width: Int, height: Int): DecoderCapabilityReport.Capability? {
        // Silent below Lollipop, as before: there is no capability API to ask, and a WARN every
        // session on a device that can never answer is noise rather than a finding.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        try {
            // Held for the throughput line: when the codec turns out to be the bottleneck, what it
            // claimed beforehand is the other half of the finding.
            val capability = DecoderCapabilityReport.forCodec(
                codecName, mimeType, width, height, settings.fpsLimit
            )
            if (capability == null) {
                AppLog.w("Decoder $codecName reported no video capabilities for $mimeType")
                return null
            }
            if (capability.adequate) {
                AppLog.i("Decoder capability: $capability")
            } else {
                AppLog.w("Decoder may not manage this stream: $capability")
            }
            return capability
        } catch (e: Exception) {
            AppLog.w("Decoder capability check failed for $codecName: ${e.message}")
            return null
        }
    }

    /**
     * Configures and starts the native MediaCodec.
     */
    private fun start(request: StartRequest) {
        val mimeType = request.type.mimeType
        val width = request.width
        val height = request.height
        val resources = request.resources
        try {
            val bestCodec = findBestCodec(mimeType, !request.forceSoftware)
                ?: throw IllegalStateException("No decoder available for $mimeType")
            val capability = logDecoderCapability(bestCodec, mimeType, width, height)

            // Richest key set first, always ending in none. The last rung is exactly the format this
            // built before the ladder existed, so a component that rejects everything optional lands
            // on the previous behaviour rather than on a failed session.
            val tiers = DecoderConfigLadder.tiers(
                codecName = bestCodec,
                sdkInt = Build.VERSION.SDK_INT,
                // From the same report the capability line printed, so the ladder and that line can
                // no longer disagree about what the component advertises.
                // Unknown counts as not advertised, which routes to the vendor rung - the right
                // answer for every device too old to have the official feature at all.
                advertisesLowLatencyFeature = (capability?.lowLatency
                    ?: DecoderCapabilityReport.advertisesLowLatency(bestCodec, mimeType)) == true,
                lowLatencyRequested = settings.debugVideoLowLatency,
                // The same rate the capability line was asked about, so a component that said it
                // could sustain a rate is then asked to clock itself for it.
                operatingRate = settings.fpsLimit,
            )

            var started = false
            var lastFailure: Exception? = null
            for ((attempt, tier) in tiers.withIndex()) {
                // A configure() that throws leaves the component unusable, so each rung needs its own
                // instance - and the old one has to be released rather than dropped, because on head
                // units with a single hardware decoder instance every leaked one makes the next create
                // fail until the process dies.
                // This synchronous ladder runs on the transport/preflight thread, outside both
                // lifecycle monitors. Retirement waits for it before touching this resource.
                try { resources.codec?.release() } catch (_: Exception) {}
                resources.codec = null
                if (request.generation != decoderGeneration) return
                if (!request.surface.isValid) throw IllegalStateException("Surface not valid")

                val candidate = MediaCodec.createByCodecName(bestCodec)
                resources.codec = candidate
                val format = synchronized(this) {
                    if (!isCurrentStart(request)) return
                    buildFormat(mimeType, width, height, bestCodec)
                }
                for ((key, value) in tier.integerKeys) format.setInteger(key, value)

                val keyDetail = if (tier.integerKeys.isEmpty()) "" else " ${tier.integerKeys.keys}"
                AppLog.i(
                    "Configuring decoder: $bestCodec for ${width}x${height}, " +
                        "max-input-size=${requestedMaxInputSize / 1024}KB, memory=$memoryReading, " +
                        "queue=$frameQueueCapacity frames, optionalKeys=${tier.label}$keyDetail"
                )
                try {
                    candidate.configure(format, request.surface, null, 0)
                    if (request.generation != decoderGeneration) return
                    try { candidate.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (e: Exception) {}
                    if (request.generation != decoderGeneration) return
                    candidate.start()
                    started = true
                    if (attempt > 0) {
                        AppLog.w("Decoder accepted the format only with optionalKeys=${tier.label}")
                    }
                    break
                } catch (e: Exception) {
                    lastFailure = e
                    // A surface torn down mid-configure is not a verdict on the keys. It was logged
                    // as one, and on a shutdown race that WARN was the only "rejected" line in a
                    // reporter's whole capture - so the one piece of evidence about a vendor key
                    // that a reader would find said the opposite of what the other configures said.
                    // Partial initialization is still owned, including when the surface dies.
                    // The common retirement path releases the final rung off-thread.
                    if (!request.surface.isValid || request.generation != decoderGeneration) {
                        AppLog.w(
                            "Decoder configure abandoned: the surface went away mid-configure. " +
                                "This is not a rejection of optionalKeys=${tier.label}."
                        )
                        break
                    }
                    AppLog.w("Decoder rejected optionalKeys=${tier.label}: ${e.message}")
                }
            }
            if (!started) {
                throw lastFailure ?: IllegalStateException("Decoder configure failed for $bestCodec")
            }

            val candidate = checkNotNull(resources.codec)
            @Suppress("DEPRECATION")
            val buffers = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) candidate.inputBuffers else null
            synchronized(this) {
                if (!isCurrentStart(request)) return
                codec = candidate
                codecBufferInfo = MediaCodec.BufferInfo()
                inputBuffers = buffers
                currentCodecName = bestCodec
                decoderCapability = capability
                startTime = System.nanoTime()
                running = true
                clearFrameQueue()
                val newOutputThread = Thread {
                    setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                    LegacyOptimizer.setHighPriority()
                    outputThreadLoop()
                }.apply { name = "VideoDecoder-Output" }
                synchronized(outputPublicationLock) { outputThread = newOutputThread }
                newOutputThread.start()
                // Published before start(): the loop's guard reads this field to prove its own
                // identity, and a thread that starts before the assignment lands can read the null a
                // prior stop() left and exit at birth - after which every frame queues into a feed
                // queue nobody drains.
                val newFeedThread = Thread {
                    setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                    feedThreadLoop()
                }.apply { name = "VideoDecoder-Feed" }
                feedThread = newFeedThread
                newFeedThread.start()

                codecTypePinned = true
            }
            AppLog.i("Codec initialized: $bestCodec")
        } catch (e: Exception) {
            AppLog.e("Failed to start decoder", e)
            // Retain partial initialization until the common retirement worker disposes it.
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
            failStart(request, "decoder_start_failed: ${e.message}")
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
    private fun reportContentOnce(content: CodecConfigScanner.Content, events: OutputEvents) {
        if (!loggedContentKinds.add(content)) return
        val consequence = when (content) {
            CodecConfigScanner.Content.PARAMETER_SETS_ONLY -> "flagged to the codec as configuration"
            CodecConfigScanner.Content.PARAMETER_SETS_WITH_PICTURE -> "carries a picture too, so not flagged"
            CodecConfigScanner.Content.NO_PARAMETER_SETS -> "an ordinary frame"
        }
        events.info("VideoDecoder: access unit classified $content - $consequence (first this session)")
    }

    /**
     * Reads the parameter sets of an access unit that carries them and reports a genuine change.
     *
     * Diagnostic only - see [ParameterSetTracker] for why nothing here writes to the stored CSD or
     * the configured size. Runs only on units the config scanner says carry parameter sets, which is
     * keyframes and config messages; [forEachParameterSet] never copies their picture payload.
     */
    private fun trackParameterSets(data: ByteArray, offset: Int, size: Int, events: OutputEvents) {
        val hevc = currentCodecType == CodecType.H265
        var spsOffset = -1
        var spsLength = 0
        forEachParameterSet(data, offset, size, currentCodecType) { header, length, nalKind ->
            val kind = when (nalKind) {
                32 -> ParameterSetTracker.Kind.VPS
                7, 33 -> ParameterSetTracker.Kind.SPS
                8, 34 -> ParameterSetTracker.Kind.PPS
                else -> return@forEachParameterSet
            }
            parameterSetTracker.offer(kind, data, header, length)
            if (kind == ParameterSetTracker.Kind.SPS) {
                spsOffset = header
                spsLength = length
            }
        }

        val change = parameterSetTracker.takeChange() ?: return
        val kinds = change.kinds.joinToString(" ")
        val sizeNote = if (spsOffset >= 0) describeSizeChange(data, spsOffset, spsLength, hevc) else "size unread"
        events.warn(
            "VideoDecoder: parameter sets changed mid-session ($kinds, $sizeNote) - change #${change.ordinal}"
        )
    }

    /**
     * The size the new SPS describes, against the one the codec is configured for. Read-only: the
     * configured size is corrected by the component's own output format, and second-guessing it from
     * here is the behaviour change this measurement exists to justify or rule out.
     */
    private fun describeSizeChange(nalData: ByteArray, headerLen: Int, length: Int, hevc: Boolean): String {
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

        /** Frame exceeds the codec's input buffer; the caller records the dropped reference. */
        DROPPED_TOO_LARGE,

        /** The codec threw mid-feed; return any held slot, or request restart if return also fails. */
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
    private fun feedInputBuffer(
        owner: FeedOwner, events: OutputEvents, buffer: ByteBuffer, arrivalNanos: Long, repairEligible: Boolean
    ): FeedResult {
        // Capture the codec and its legacy input-buffer array together in FeedOwner. A late native
        // completion must never switch to the replacement codec's buffers or timestamp origin.
        val currentCodec = owner.codec
        // Keep these outside try: catch must distinguish a dequeued slot from accepted input.
        var inputIndex = -1
        var queued = false
        var keyframeToken = 0L
        try {
            // ~300ms of patience. A much shorter budget used to report a full queue within a second
            // of startup on hardware that subsequently decoded at full rate; busy is not wedged.
            // Time the entire dequeue sequence, including the first blocking call, not just retries.
            //
            // interrupt() cannot abort a native MediaCodec call. Check worker ownership on every
            // iteration so shutdown waits for at most the current dequeue, not the remaining budget.
            // A vendor call can still overrun the join; publication checks exclude its late result.
            val waitStart = elapsedRealtime()
            while (isCurrentFeed(owner) &&
                elapsedRealtime() - waitStart < VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS) {
                inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0 || decoderNeedsRestart) break
            }
            publishFeed(owner) { inputWaitMs += elapsedRealtime() - waitStart }
                ?: return FeedResult.NO_INPUT_BUFFER
            // The caller throttles genuine saturation reports. A retired worker or an already
            // requested restart must not produce a second "input full" diagnostic for the same event.
            if (inputIndex < 0 || decoderNeedsRestart) return FeedResult.NO_INPUT_BUFFER
            val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                currentCodec.getInputBuffer(inputIndex)
            } else {
                owner.inputBuffers?.get(inputIndex)
            }
            if (!isCurrentFeed(owner)) return FeedResult.NO_INPUT_BUFFER
            if (inputBuffer == null) {
                // A component can return a null view during a flush. The index was still acquired:
                // return it empty or the codec's available input pool becomes one slot shallower.
                currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                return FeedResult.NO_INPUT_BUFFER
            }
            inputBuffer.clear()
            val capacity = inputBuffer.capacity()
            val content = if (buffer.hasArray()) {
                CodecConfigScanner.classify(
                    buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), owner.type == CodecType.H265
                )
            } else CodecConfigScanner.Content.NO_PARAMETER_SETS
            val isKeyframe = repairEligible && buffer.hasArray() && VideoKeyframeScanner.containsKeyframe(
                buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), owner.type == CodecType.H265
            )
            publishFeed(owner) {
                if (!loggedInputBufferCapacity) {
                    loggedInputBufferCapacity = true
                    events.info("Codec input buffer: requested ${requestedMaxInputSize / 1024}KB, " +
                        "got ${capacity / 1024}KB per buffer")
                }
                reportContentOnce(content, events)
                if (content != CodecConfigScanner.Content.NO_PARAMETER_SETS && buffer.hasArray()) {
                    trackParameterSets(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), events)
                }
            } ?: return FeedResult.NO_INPUT_BUFFER
            // BUFFER_FLAG_CODEC_CONFIG marks configuration rather than picture data. A component
            // may consume it without producing output, so SPS/PPS followed by an IDR slice must
            // remain ordinary input; flagging the whole unit risks losing the repair picture.
            //
            // Classification, not chipset name, decides this. The old Rockchip/Allwinner/Telechips
            // allowlist flagged mid-stream config but could also flag a fused keyframe because it
            // only checked the first NAL. Inspecting the whole unit preserves genuine configuration
            // delivery on all devices without misclassifying pictures on the formerly listed ones.
            val flags = if (content == CodecConfigScanner.Content.PARAMETER_SETS_ONLY) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else 0
            if (buffer.remaining() > capacity) {
                // Check the actual component buffer, not KEY_MAX_INPUT_SIZE: the codec may allocate
                // less than requested. Return this acquired slot empty instead of truncating a frame
                // or depleting the codec's pool. The caller records the lost reference frame.
                events.warn("Frame larger than the codec input buffer: ${buffer.remaining()} > $capacity bytes. Dropping frame.")
                if (isCurrentFeed(owner)) currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                return FeedResult.DROPPED_TOO_LARGE
            }
            inputBuffer.put(buffer)
            inputBuffer.flip()
            val pts = ((if (arrivalNanos > 0L) arrivalNanos else System.nanoTime()) - owner.startTime) / 1000
            publishFeed(owner) {
                if (isKeyframe) keyframeToken = keyframeRepair.beginKeyframe(pts)
            } ?: return FeedResult.NO_INPUT_BUFFER
            // Output may already be released before this call returns. Reserve first, but do not
            // let provisional output acknowledge a keyframe whose input ultimately throws.
            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, flags)
            queued = true
            publishFeed(owner) {
                if (isKeyframe) {
                    keyframeRepair.commitKeyframe(keyframeToken)
                    lastKeyframeFedMs = elapsedRealtime()
                    events.info("VideoDecoder: keyframe reached the codec (${inputBuffer.limit()} bytes)")
                }
            }
            return FeedResult.FED
        } catch (e: Exception) {
            if (queued) {
                // The codec already accepted the frame; only later bookkeeping failed. Reporting a
                // drop here would request repair for input that is still owned by the codec.
                AppLog.e("Error after feeding input buffer", e)
                return FeedResult.FED
            }
            publishFeed(owner) { keyframeRepair.cancelKeyframe(keyframeToken) }
            if (inputIndex >= 0 && isCurrentFeed(owner)) {
                // A failed feed does not necessarily return its acquired slot. Return it empty;
                // repeated forgotten slots would otherwise look like permanent input starvation.
                // If this return fails too, let the existing restart path retire the uncertain pool.
                try {
                    currentCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                } catch (returnError: Exception) {
                    publishFeed(owner) { scheduleRestart("input_buffer_return_failed") }
                    AppLog.e("Could not return the input buffer after a failed feed", returnError)
                }
            }
            AppLog.e("Error feeding input buffer", e)
            return FeedResult.ERROR
        }
    }

    private fun reportKeyframeRepair(events: OutputEvents) {
        recoveryContinuityLost = false
        if (keyframeRepair.timestampsUnusable && !loggedUnusableOutputTimestamps) {
            loggedUnusableOutputTimestamps = true
            events.warn(
                "$currentCodecName never carries a keyframe's timestamp through to its " +
                    "output, so a repaired picture is read from frames arriving rather " +
                    "than from the frame that repaired it."
            )
        }
        events.info("VideoDecoder: keyframe decoded - the picture is repaired")
        events.callback(onKeyframeObserved)
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
     * `presented` is the one of these numbers that is the rate the user sees. `rendered` also counts
     * the earlier buffers a pass releases, so the two are equal while the pipeline runs one deep and
     * diverge toward its depth when it does not - about 1.6x measured on a loaded unit. Read
     * `rendered` against `fed` to judge the codec, and `presented` against nothing else at all when
     * the complaint is that the picture is not smooth.
     *
     * Called on every output-loop iteration, including bounded dequeue timeouts while the phone
     * is idle or the codec has stopped producing frames.
     */
    private fun logThroughput(
        info: (String) -> Unit = { AppLog.i(it) },
        warn: (String) -> Unit = { AppLog.w(it) },
    ) {
        val now = elapsedRealtime()
        if (lastThroughputLogMs == 0L) {
            lastThroughputLogMs = now
            return
        }
        val elapsed = now - lastThroughputLogMs
        if (elapsed < THROUGHPUT_LOG_INTERVAL_MS) return

        val rendered = framesRendered - lastLoggedFramesRendered
        val presented = framesPresented - lastLoggedFramesPresented
        val fed = framesFed - lastLoggedFramesFed
        val dropped = framesDropped - lastLoggedFramesDropped
        val skipped = framesSkippedAtRender - lastLoggedFramesSkippedAtRender
        val inputWait = inputWaitMs - lastLoggedInputWaitMs
        val enqueueWait = enqueueWaitMs - lastLoggedEnqueueWaitMs
        val concealed = framesConcealed - lastLoggedFramesConcealed
        lastLoggedFramesConcealed = framesConcealed
        lastThroughputLogMs = now
        lastLoggedFramesRendered = framesRendered
        lastLoggedFramesPresented = framesPresented
        lastLoggedFramesFed = framesFed
        lastLoggedFramesDropped = framesDropped
        lastLoggedFramesSkippedAtRender = framesSkippedAtRender
        lastLoggedInputWaitMs = inputWaitMs
        lastLoggedEnqueueWaitMs = enqueueWaitMs

        val renderedFps = rendered * 1000 / elapsed
        val fedFps = fed * 1000 / elapsed
        val presentedFps = presented * 1000 / elapsed
        val latency = decodeLatency.report()
        // New fields are appended after the pre-existing ones, never between them: this line is
        // read by diagnostics and compared across releases, so existing fields keep name and order.
        info(
            "Throughput over ${elapsed}ms: rendered=$rendered (${renderedFps}fps), " +
                "fed=$fed (${fedFps}fps), dropped=$dropped, skipped=$skipped, " +
                "concealed=$concealed, inputWait=${inputWait}ms, enqueueWait=${enqueueWait}ms, " +
                "codec=$currentCodecName, presented=$presented (${presentedFps}fps)" +
                (if (latency == null) "" else ", $latency")
        )
        reportBackpressure(elapsed, inputWait, dropped, warn)
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
    private fun reportBackpressure(elapsedMs: Long, inputWaitMs: Long, dropped: Long, warn: (String) -> Unit) {
        // Early out for cost only; the once-per-session rule itself lives in shouldReport below.
        if (reportedBackpressure) return
        if (!VideoBackpressurePolicy.isBackpressureWindow(elapsedMs, inputWaitMs, dropped)) return
        backpressureWindows++
        if (!VideoBackpressurePolicy.shouldReport(backpressureWindows, reportedBackpressure)) return
        reportedBackpressure = true
        val claim = decoderCapability?.let {
            if (it.adequate) " It claimed it could: $it" else " It said it might not: $it"
        } ?: ""
        warn(
            "VideoDecoder: the codec is the bottleneck - $backpressureWindows windows shed frames " +
                "while waiting >=${VideoBackpressurePolicy.WAIT_PERCENT}% of the window for an input " +
                "buffer (${mWidth}x$mHeight@${settings.fpsLimit} on $currentCodecName). The negotiated " +
                "size and rate are more than this decoder is keeping up with.$claim"
        )
    }

    /**
     * Asks the exception what it is, where the platform can answer.
     *
     * `CodecException` arrived in Lollipop, so the type reference itself is guarded rather than only
     * the calls on it: below that the class is not present to cast against. Everything unclassified
     * takes the path that was already shipping.
     */
    private fun classifyOutputException(e: Exception): DecoderExceptionPolicy.Response {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return DecoderExceptionPolicy.Response.COUNT_STRIKE
        }
        val codecException = e as? MediaCodec.CodecException
            ?: return DecoderExceptionPolicy.Response.COUNT_STRIKE
        return DecoderExceptionPolicy.responseTo(
            isCodecException = true,
            isTransient = codecException.isTransient,
            isRecoverable = codecException.isRecoverable,
        )
    }

    /**
     * Dedicated thread to pull decoded frames and render them to the surface.
     */
    private fun outputThreadLoop() {
        AppLog.i("Output thread started")
        val self = Thread.currentThread()
        val currentCodec = codec ?: return
        val bufferInfo = codecBufferInfo ?: return
        // Scratch belongs to this worker, not the decoder instance. A retired worker may
        // still return from a native call while its replacement is collecting output; sharing
        // indices would let one worker release the other's buffers against the wrong codec.
        val readyIndices = IntArray(MAX_CATCHUP_SKIPS + 2)
        val readyTimestamps = LongArray(readyIndices.size)
        val events = OutputEvents()
        val info: (String) -> Unit = { events.info(it, "VideoDecoder.logThroughput") }
        val warn: (String) -> Unit = { events.warn(it, "VideoDecoder.logThroughput") }
        val concealmentWarning: (String) -> Unit = { events.warn(it, "VideoDecoder.applyConcealmentTransition") }
        val isCurrent = { running && outputThread === self }
        var consecutiveErrors = 0
        var lastOutputMs = elapsedRealtime()

        // A vendor dequeue may outlive stop's join budget. It must never consume a newer
        // codec after that replacement sets running=true again.
        outputLoop@ while (running && outputThread === self) {
            var readyCount = 0
            var releasedCount = 0
            try {
                val outputIndex = currentCodec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
                if (!running || outputThread !== self) break
                if (outputIndex >= 0) {
                    readyIndices[readyCount] = outputIndex
                    readyTimestamps[readyCount++] = bufferInfo.presentationTimeUs
                    // The codec produced output, whatever the screen ends up showing. This is the
                    // stall watchdog's clock and it is deliberately not the render stamp: the two
                    // were the same fact only while every dequeued buffer was also displayed, and
                    // anything that legitimately holds a decoded frame off the screen must not
                    // read as a codec that stopped decoding. lastFrameRenderedMs stays the display
                    // watchdogs' instrument and is stamped only when a frame reaches the surface.
                    lastOutputMs = elapsedRealtime()
                    // The frame's input stamp is its arrival time on the same session clock, so the
                    // difference is what the component held it for. Sampled on the first dequeue of
                    // the pass because the catch-up drain below reuses bufferInfo.
                    publishOutput(self) {
                        decodeLatency.onFrameDecoded(
                            (System.nanoTime() - startTime) / 1000 - bufferInfo.presentationTimeUs
                        )
                    } ?: break
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
                    var passes = 0
                    while (readyCount <= MAX_CATCHUP_SKIPS && passes < MAX_CATCHUP_SKIPS * 2) {
                        passes++
                        val readyIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 0L)
                        if (!running || outputThread !== self) break
                        if (readyIndex >= 0) {
                            readyIndices[readyCount] = readyIndex
                            readyTimestamps[readyCount++] = bufferInfo.presentationTimeUs
                        } else if (readyIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            handleOutputFormatChange(currentCodec, currentCodec.outputFormat)
                        } else {
                            break
                        }
                    }

                    if (!running || outputThread !== self) break

                    // Keep candidate ownership fixed across native releases. Recapturing after a
                    // release could credit a newer input that was reserved while it blocked.
                    var repairToken = 0L
                    var acceptedRepairToken = 0L
                    var reportedStamp = 0L
                    publishOutput(self) {
                        repairToken = keyframeRepair.outputToken()
                        acceptedRepairToken = keyframeRepair.acceptedOutputToken()
                        reportedStamp = corruptionReportedMs
                    } ?: break

                    var presented = false
                    var queuedFirstFrameListener: (() -> Unit)? = null
                    for (i in 0 until readyCount) {
                        val outputPts = readyTimestamps[i]
                        // A later repair picture must not authorize an earlier damaged one.
                        // Preview per slot; only a successful release supplies actual proof.
                        val outcome = publishOutput(self) {
                            CorruptionConcealmentPolicy.next(
                                elapsedRealtime(), concealState, concealWindowOpenedMs,
                                corruptionReported = reportedStamp > consumedCorruptionMs,
                                keyframeRepaired = keyframeRepair.canRepair(
                                    repairToken, acceptedRepairToken, outputPts, countOutput = i == 0
                                ),
                                sessionHasRendered = renderedThisSession,
                            )
                        } ?: break@outputLoop
                        val render = outcome.renders && (readyCount <= 2 || i == readyCount - 1)
                        currentCodec.releaseOutputBuffer(readyIndices[i], render)
                        releasedCount++
                        // Each native completion is independent. A later failure must not erase
                        // the picture, repair evidence or notification already delivered here.
                        publishOutput(self) {
                            val now = if (i == 0 || render) elapsedRealtime() else 0L
                            if (i == 0 || render) {
                                // Intermediate discarded slots neither advance this pass again
                                // nor supply picture proof.
                                keyframeRepair.onOutputReleased(
                                    repairToken, acceptedRepairToken, outputPts, render, countOutput = i == 0
                                )
                                val repaired = keyframeRepair.consumeRepair()
                                applyConcealmentTransition(
                                    CorruptionConcealmentPolicy.next(
                                        now, concealState, concealWindowOpenedMs,
                                        corruptionReported = reportedStamp > consumedCorruptionMs,
                                        keyframeRepaired = repaired,
                                        sessionHasRendered = renderedThisSession,
                                    ), now, concealmentWarning
                                )
                                if (reportedStamp > consumedCorruptionMs) consumedCorruptionMs = reportedStamp
                                if (repaired) reportKeyframeRepair(events)
                            }
                            if (render) {
                                lastFrameRenderedMs = now
                                renderedThisSession = true
                                framesRenderedThisSession++
                                // Report the first actual render, even if a later slot fails.
                                if (!loggedFirstHardwareFrame) {
                                    loggedFirstHardwareFrame = true
                                    events.info(
                                        if (keyframeRepair.keyframeDecoded) "First frame rendered (hardware decode)"
                                        else "First frame rendered (hardware decode) - no keyframe has decoded " +
                                            "yet, so this is output, not a picture"
                                    )
                                }
                                val expected = onFirstFrameListener
                                if (expected != null && expected !== queuedFirstFrameListener) {
                                    queuedFirstFrameListener = expected
                                    events.callback {
                                        // Claim at dispatch so retirement cannot consume a replacement listener.
                                        val claimed = publishOutput(self) {
                                            if (onFirstFrameListener === expected) {
                                                onFirstFrameListener = null
                                                expected
                                            } else null
                                        }
                                        claimed?.invoke()
                                    }
                                }
                                frameCount++
                                framesRendered++
                                // The surface keeps one picture per pass, including a partial pass.
                                if (!presented) {
                                    framesPresented++
                                    presented = true
                                }
                            } else if (!outcome.renders) {
                                framesConcealed++
                            } else {
                                framesSkippedAtRender++
                            }
                            Unit
                        } ?: break@outputLoop
                    }
                    consecutiveErrors = 0
                    publishOutput(self) {
                        if (presented) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastFpsLogTime
                            if (elapsed >= 1000) {
                                if (lastFpsLogTime != 0L) {
                                    val fps = (frameCount * 1000 / elapsed).toInt()
                                    onFpsChanged?.let { listener -> events.callback { listener(fps) } }
                                }
                                frameCount = 0
                                lastFpsLogTime = now
                            }
                        }
                        Unit
                    } ?: break
                    events.dispatch(isCurrent)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChange(currentCodec, currentCodec.outputFormat)
                }

                if (!running || outputThread !== self) break

                val restart = publishOutput(self) {
                    logThroughput(info, warn)
                    if (keyframeRepair.consumeRepair()) {
                        // Input acceptance can finish after its sole output has already rendered.
                        // Finish on a bounded idle tick; a static phone screen need not send more.
                        val now = elapsedRealtime()
                        applyConcealmentTransition(
                            CorruptionConcealmentPolicy.next(
                                now, concealState, concealWindowOpenedMs,
                                corruptionReported = false, keyframeRepaired = true,
                                sessionHasRendered = renderedThisSession,
                            ), now, concealmentWarning
                        )
                        reportKeyframeRepair(events)
                    }

                    // A window can only close on an output buffer, and a phone that goes idle
                    // mid-window sends none. Check the wall-clock cap on each bounded dequeue
                    // timeout too; the next buffer renders even if none arrived at expiry.
                    if (concealState == CorruptionConcealmentPolicy.State.CONCEALING) {
                        val tickNow = elapsedRealtime()
                        applyConcealmentTransition(
                            CorruptionConcealmentPolicy.next(
                                tickNow,
                                concealState,
                                concealWindowOpenedMs,
                                corruptionReported = false,
                                keyframeRepaired = false,
                                sessionHasRendered = renderedThisSession,
                            ),
                            tickNow, concealmentWarning
                        )
                    }

                    // Stall detection: if we rendered at least one frame but haven't
                    // produced output in SYNC_STALL_THRESHOLD_MS, check if input bytes
                    // are still arriving from the phone. If no input bytes arrived recently,
                    // Android Auto has simply paused the stream (idle/static screen), so
                    // we update lastOutputMs to prevent false-positive restarts and screen flickering.
                    val now = elapsedRealtime()
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
                                events.warn("Decoder has had no keyframe since it started ${stallGap}ms ago - waiting for one instead of rebuilding.")
                            }
                            // The ask runs on the shorter of the two clocks. Output polling is
                            // faster than the request interval, so keep the same throttle as
                            // every other keyframe request in the app.
                            if (VideoRecoveryPolicy.canRequestKeyframe(now, lastKeyframeStarvedAskMs)) {
                                lastKeyframeStarvedAskMs = now
                                events.callback(onKeyframeStarved)
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
                                events.warn("Decoder stall detected (no output for ${stallGap}ms while receiving input). Forcing restart ($syncStallRestartCount/$MAX_SYNC_STALL_RESTARTS).")
                                scheduleRestart("sync_stall")
                                return@publishOutput true
                            }
                            // Suppressed by the cooldown or the cap. Report it, throttled: the branch
                            // above is the only thing that ever mentions a stall, so once it stops
                            // firing a decoder that has exhausted its restart budget keeps stalling
                            // with an entirely clean log and reads as healthy.
                            if (now - lastSyncStallSuppressedLogMs >= SYNC_STALL_SUPPRESSED_LOG_INTERVAL_MS) {
                                lastSyncStallSuppressedLogMs = now
                                events.warn("Decoder stall detected (no output for ${stallGap}ms) but restart suppressed ($syncStallRestartCount/$MAX_SYNC_STALL_RESTARTS used, ${SYNC_STALL_COOLDOWN_MS}ms cooldown). Still spinning on output.")
                            }
                        }
                    }
                    false
                } ?: break
                events.dispatch(isCurrent)
                if (restart) break
            } catch (e: Exception) {
                // A failed catch-up dequeue or format callback can leave earlier output indices
                // in our hands. Return those slots before retrying, or a busy codec can stall
                // permanently waiting for buffers this worker has forgotten.
                var returnFailed = false
                while (releasedCount < readyCount && running && outputThread === self) {
                    try {
                        currentCodec.releaseOutputBuffer(readyIndices[releasedCount], false)
                    } catch (_: Exception) {
                        returnFailed = true
                    }
                    releasedCount++
                }
                val restart = publishOutput(self) {
                    // MediaCodec has classified its own failures since API 21 and this read them all
                    // as the same thing: a component saying it was busy spent a strike, and one
                    // saying it was gone waited out two more strikes and 100ms before the restart it
                    // was always going to need.
                    val response = classifyOutputException(e)
                    events.warn(
                        "Codec exception in output thread - ${DecoderExceptionPolicy.describe(response)}: " +
                            "${e.message}"
                    )
                    if (returnFailed) {
                        events.error("Could not return held codec output. Forcing restart.")
                        scheduleRestart("output_buffer_return_failed")
                        return@publishOutput true
                    }
                    when (response) {
                        DecoderExceptionPolicy.Response.CONTINUE -> {}
                        DecoderExceptionPolicy.Response.RESTART_NOW -> {
                            scheduleRestart("codec_unrecoverable")
                            return@publishOutput true
                        }
                        DecoderExceptionPolicy.Response.COUNT_STRIKE -> {
                            consecutiveErrors++
                            if (consecutiveErrors >= 3) {
                                events.error("Too many consecutive exceptions in output thread. Forcing restart.")
                                scheduleRestart("sync_consecutive_errors")
                                return@publishOutput true
                            }
                        }
                    }
                    false
                } ?: break
                events.dispatch(isCurrent)
                if (restart) break
                try { Thread.sleep(50) } catch (ignore: Exception) {}
            }
        }
        // A stop between publication and dispatch must not erase the last diagnostic window.
        events.dispatch(isCurrent)
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
