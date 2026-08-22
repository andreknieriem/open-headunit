package com.andrerinas.openheadunit.aap

import android.app.UiModeManager
import android.content.Context
import android.content.Context.UI_MODE_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.KeyCodeEvent
import com.andrerinas.openheadunit.aap.protocol.messages.MediaAck
import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.aap.protocol.messages.ScrollWheelEvent
import com.andrerinas.openheadunit.aap.protocol.messages.SensorEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.utils.LegacyOptimizer
import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.connection.SocketAccessoryConnection
import com.andrerinas.openheadunit.contract.ProjectionActivityRequest
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.decoder.MicRecorder
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import javax.net.ssl.SSLEngineResult

/**
 * Core AAP message pump.
 *
 * Owns two [HandlerThread]s:
 * - **Send** (`AapTransport:Handler::Send`) — encrypts and delivers outbound messages.
 * - **Poll** (`AapTransport:Handler::Poll`) — reads, decrypts, and dispatches inbound messages.
 *
 * Lifecycle: [startHandshake] → [startReading] → message loop → [stop]/[quit].
 *
 * @param audioDecoder Decodes PCM audio received from the phone.
 * @param videoDecoder Decodes H.264/H.265 video received from the phone.
 * @param audioManager Used to request and release audio focus.
 * @param settings User preferences (SSL mode, key mappings, microphone sample rate, …).
 * @param notification Background notification handle; updated as connection state changes.
 * @param context Application context; used for broadcasts and system services.
 * @param onAaMediaMetadata Optional callback when the phone sends media metadata (now-playing).
 * @param onAaPlaybackStatus Optional callback when the phone sends playback status/position.
 * @param externalSsl Optional singleton [AapSslContext] whose internal [javax.net.ssl.SSLContext]
 *   (and its `ClientSessionContext` session cache) survives across [AapTransport] recreations.
 *   When provided, JSSE can resume the previous TLS session on reconnect, skipping 4–6
 *   round-trips and saving 1–3 s of handshake time. Pass `null` to create a fresh
 *   [AapSslContext] per transport (no session resumption).
 */
class AapTransport(
        audioDecoder: AudioDecoder,
        private val videoDecoder: VideoDecoder,
        audioManager: AudioManager,
        internal val settings: Settings,
        private val notification: BackgroundNotification,
        private val context: Context,
        private val onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
        private val onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null,
        private val externalSsl: AapSslContext? = null)
    : MicRecorder.Listener {

    val ssl: AapSsl = externalSsl ?: AapSslContext(SingleKeyKeyManager(context))

    internal val aapAudio: AapAudio
    internal val aapVideo: AapVideo
    private var sendThread: HandlerThread? = null
    private var pollThread: HandlerThread? = null
    private val micRecorder: MicRecorder = MicRecorder(settings.micSampleRate, context)
    private val sessionIds = SparseIntArray(4)
    private val startedSensors = HashSet<Int>(4)
    private val droppedSensorEvents = HashMap<Int, Int>(4)
    private var lastSensorDropLogMs = 0L
    private val keyCodes = mutableMapOf<Int, Int>()
    private val modeManager: UiModeManager =
        context.getSystemService(UI_MODE_SERVICE) as UiModeManager
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null
    var isQuittingAllowed: Boolean = false

    val isWireless: Boolean
        get() = connection is com.andrerinas.openheadunit.connection.SocketAccessoryConnection
    var ignoreNextStopRequest: Boolean = false
    /** Why the last [startHandshake] failed, for callers that report it. */
    enum class HandshakeFailure {
        NONE,

        /**
         * The version exchange timed out on every attempt without the peer sending a single
         * byte, and without any transport error of our own. The link is fine and the peer is
         * simply not answering — on the WiFi head unit server path that means it is bound to
         * an earlier connection and will stay that way until it is restarted.
         */
        PEER_SILENT,

        OTHER
    }

    @Volatile var lastHandshakeFailure: HandshakeFailure = HandshakeFailure.NONE
        private set

    /** Set by [AapControl] when VIDEO_FOCUS_NATIVE triggers a stop (user tapped Exit). */
    @Volatile var wasUserExit: Boolean = false
    @Volatile var onQuit: ((Boolean) -> Unit)? = null
    var isAssistantActive = false
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null
    var onUpdateUiConfigReplyReceived: (() -> Unit)? = null
    private var pollHandler: Handler? = null
    private val pollHandlerCallback = Handler.Callback {
        val readInstance = aapRead
        if (readInstance == null) {
            return@Callback false
        }

        val ret = readInstance.read()

        if (ret < 0) {
            AppLog.i("Quitting because ret < 0 ($ret)")
            this.quit(clean = (ret == -2))
            return@Callback true
        }

        pollHandler?.let {
            if (!it.hasMessages(MSG_POLL)) {
                it.sendEmptyMessage(MSG_POLL)
            }
        }

        return@Callback true
    }
    private var sendHandler: Handler? = null
    private val sendHandlerCallback = Handler.Callback {
        // Timed because the media channels are flow-controlled: acks that stay in here stall video
        // and audio together and leave control traffic alone, which is indistinguishable from the
        // phone going quiet unless one end or the other is actually measured. This thread serves one
        // socket, so the write's own duration is the time the uplink refused to drain.
        val startedMs = SystemClock.elapsedRealtime()
        this.sendEncryptedMessage(
            data = it.obj as ByteArray,
            length = it.arg2
        )
        val finishedMs = SystemClock.elapsedRealtime()
        uplinkStallMonitor.onWrite(finishedMs - startedMs, finishedMs)
            ?.let { report -> AppLog.i("AapTransport: %s", report) }
        return@Callback true
    }

    val isAlive: Boolean
        get() = pollThread?.isAlive ?: false

    /**
     * When the phone last said anything at all, on any channel. Zero until it has.
     *
     * The picture stopping and the link dying look identical if the only thing being measured is
     * frames: Android Auto sends no video while nothing on screen animates, so a paused full-screen
     * music player is indistinguishable from a dead socket to anything watching the decoder. This is
     * the signal that separates them - control, sensor, media-playback and audio traffic all keep
     * flowing while the picture is legitimately still.
     *
     * Written from the poll thread, read from the main thread by the projection watchdog.
     */
    @Volatile
    var lastMessageReceivedMs: Long = 0L
        private set

    /**
     * How long the link goes silent, and how often. Fed from the same funnel as
     * [lastMessageReceivedMs], because "the phone said something" is exactly the event it measures.
     */
    private val linkGapMonitor = LinkGapMonitor()

    /**
     * The same measurement again, per media channel.
     *
     * The link series above is deaf to the fault these were added for: the phone pings about once a
     * second on CONTROL for the life of the session, so a session whose picture and sound are both
     * gone still scores a healthy link. Measured across five captures of that fault, the link series
     * printed twice and never named an outage longer than 1.8 s while the picture was dead for six
     * seconds out of every ten. Whether video and audio went quiet *together* is the fact that
     * separates a dead radio from a stalled media path, and it takes three series to see it.
     */
    private val videoGapMonitor = LinkGapMonitor(
        LinkGapMonitor.SUBJECT_VIDEO,
        LinkGapMonitor.MIN_GAPS_MEDIA,
        LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA
    )
    private val audioGapMonitor = LinkGapMonitor(
        LinkGapMonitor.SUBJECT_AUDIO,
        LinkGapMonitor.MIN_GAPS_MEDIA,
        LinkGapMonitor.MAX_DEAD_PERCENT_MEDIA
    )

    /** Whether our own writes are draining. See [UplinkStallMonitor]. */
    private val uplinkStallMonitor = UplinkStallMonitor()

    /** Called for every decrypted inbound message, from [AapMessageHandlerType.handle]. */
    internal fun noteMessageReceived(channel: Int) {
        val now = SystemClock.elapsedRealtime()
        lastMessageReceivedMs = now
        linkGapMonitor.onMessage(now)?.let { AppLog.i("AapTransport: %s", it) }
        when {
            channel == Channel.ID_VID ->
                videoGapMonitor.onMessage(now)?.let { AppLog.i("AapTransport: %s", it) }
            Channel.isAudio(channel) ->
                audioGapMonitor.onMessage(now)?.let { AppLog.i("AapTransport: %s", it) }
        }
    }

    // Escalation state for KeyframeCycleEscalationPolicy - see triggerFocusCycleRecovery().
    // unrepairedSinceMs is when the picture was last known good: set by a shed reference frame, a
    // rebuilt or starved codec, or a corrupt access unit off the wire, and cleared when a keyframe
    // reaches the codec. Zero means nothing is broken.
    private var unrepairedSinceMs = 0L
    private var focusCyclesUsedThisSession = 0
    private var lastFocusCycleMs = 0L

    /**
     * Lifetime spends and the budget's last movement, for
     * [KeyframeCycleEscalationPolicy.cyclesToRefund]. The spend counter is what the per-drive
     * ceiling reads and refunds never touch it; the stamp moves on every spend and every refund,
     * so refunds accrue one per quiet window rather than all against the first spend.
     */
    private var focusCyclesSpentThisSession = 0
    private var lastBudgetChangeMs = 0L

    /**
     * When an access unit last arrived broken on the wire, as opposed to when the decoder last had
     * no picture. Zero until the first one. Session state like the two fields above, and reset the
     * same way they are - by the transport being torn down and rebuilt.
     */
    private var lastWireCorruptionMs = 0L

    /**
     * Print budget for the held-cycle line, in the shape [AuditReportPolicy] already governs.
     *
     * A hold is re-checked every [KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS], which
     * has to stay short so a reserved cycle lands promptly once the wire settles. The last cycle of
     * a session holds without a ceiling, so that check can repeat for minutes - a hundred identical
     * lines across the injection run that motivated the reserve. Reset when the unrepaired clock
     * arms, not when a cycle fires: one broken stretch is one story, and it can hold, cycle and hold
     * again inside itself. What the reset guarantees is that the *first* line of every new stretch
     * prints, which is the one a rig round reads.
     */
    private var quietHoldReports = 0
    private var quietHoldLastLogMs = 0L
    private var quietHoldSuppressed = 0

    /** The one claim on the release/regain cycle, shared with the projection activity's escalation. */
    private val focusCycleLever = FocusCycleLever()

    /** Takes the lever for one cycle. False when the other escalation already holds it. */
    internal fun beginFocusCycle(): Boolean = focusCycleLever.tryClaim()

    /**
     * Sends the first half of a keyframe cycle.
     *
     * Both escalations go through here, so the release and the [ignoreNextStopRequest] that marks
     * its answering sink stop as expected are written once rather than in two places that have to
     * stay in step.
     */
    internal fun sendKeyframeCycleRelease() {
        ignoreNextStopRequest = true
        send(VideoFocusEvent(gain = false, unsolicited = false))
    }

    /** Hands the lever back. Idempotent - see [FocusCycleLever.release]. */
    internal fun endFocusCycle() = focusCycleLever.release()

    /** Second half of an escalated focus cycle - see [WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS]. */
    private val focusCycleGainRunnable = Runnable {
        AppLog.w("AapTransport: retaking video focus to complete the keyframe cycle")
        send(VideoFocusEvent(gain = true, unsolicited = true))
        endFocusCycle()
    }

    /**
     * Armed by the first shed frame of an unrepaired stretch, cancelled by the keyframe that repairs
     * it. This is what makes the escalation reachable at all: recovery requests are throttled to one
     * a second, so a single dropped frame produces exactly one call into
     * [triggerFocusCycleRecovery] - anything that escalated on a *count* of calls could never fire
     * for the one-lost-frame case the fix exists for.
     */
    private val unrepairedCheckRunnable = Runnable { escalateIfStillUnrepaired() }

    /**
     * Asks the phone for a fresh keyframe with a gain-only nudge, and starts the clock that decides
     * whether the nudge was enough.
     *
     * The nudge is measured inert across three hardware rounds - see [KeyframeCycleEscalationPolicy]
     * for the numbers - but it costs one message, does not disturb the running stream the way a
     * release can, and has only ever been tested against one phone. It stays the first response.
     *
     * @param escalatable whether this caller's stream is live enough to earn a focus release if the
     *   nudge goes unanswered. True for the dropped-frame path, for both decoder paths (a rebuilt
     *   or keyframe-starved codec is the picture most certainly gone and least able to come back),
     *   and for [AapVideo]'s corruption path, all behind the same
     *   [VideoDecoder.hasRenderedThisSession] gate. The corruption path used to pass a hard false,
     *   which left a wire truncation with only the inert nudge and a picture that stayed broken
     *   for tens of seconds until the phone's own keyframe, against the escalation's few. Lever
     *   overlap with [WarmRelaunchKeyframePolicy] is handled by the lever's single owner
     *   ([com.andrerinas.openheadunit.connection.CommManager.releaseVideoFocusForKeyframe]), and
     *   the ordering still favours the surface path: 850ms against this one's 2000ms.
     *
     * @param wireCorruption whether the caller is reporting an access unit that arrived broken *on
     *   the wire*, which is what [KeyframeCycleEscalationPolicy]'s quiet gate reads. Its own
     *   parameter rather than `!escalatable`: those were the same bit inverted only because
     *   [AapVideo] was the sole non-escalatable caller, so the stamp could not survive that path
     *   becoming escalatable. Now the decoder paths report a consequence and say so, and the video
     *   path reports a cause and says so.
     */
    @Synchronized
    private fun triggerFocusCycleRecovery(escalatable: Boolean, wireCorruption: Boolean) {
        AppLog.w("AapTransport: Requesting recovery keyframe (unsolicited focus gain).")
        send(VideoFocusEvent(gain = true, unsolicited = true))

        // Settled quiet earns spent cycles back, judged before this fault stamps the corruption
        // clock - the stretch being judged is the one this fault just ended. Granted here, on the
        // fault that needs it, rather than on a timer: escalateIfStillUnrepaired() only runs while
        // the clock is armed, and a repaired picture is exactly when a refund becomes possible.
        val refundNow = SystemClock.elapsedRealtime()
        val refund = KeyframeCycleEscalationPolicy.cyclesToRefund(
            refundNow,
            focusCyclesUsedThisSession,
            focusCyclesSpentThisSession,
            lastBudgetChangeMs,
            lastWireCorruptionMs,
            unrepairedSinceMs != 0L,
        )
        if (refund > 0) {
            focusCyclesUsedThisSession -= refund
            lastBudgetChangeMs = refundNow
            AppLog.w(
                "AapTransport: quiet stream earned back $refund focus cycle(s) " +
                    "($focusCyclesUsedThisSession/${KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_SESSION} " +
                    "used, $focusCyclesSpentThisSession/${KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_DRIVE} " +
                    "spent this drive)"
            )
        }

        // Stamped before the returns below, and deliberately also when this call goes on to arm
        // nothing: a stream that is still losing frames is exactly the case where the clock is
        // already running, and that is the case the stamp exists to let the escalation see.
        if (wireCorruption) lastWireCorruptionMs = SystemClock.elapsedRealtime()

        if (!escalatable || unrepairedSinceMs != 0L) return
        // Stamped only once the check is actually armed. Setting it without a handler to run the
        // check on would latch the clock with nothing able to clear it but a keyframe, and every
        // later drop would return early on it - the stuck-latch failure this design exists to avoid.
        val handler = sendHandler ?: return
        unrepairedSinceMs = SystemClock.elapsedRealtime()
        quietHoldReports = 0
        quietHoldLastLogMs = 0L
        quietHoldSuppressed = 0
        handler.removeCallbacks(unrepairedCheckRunnable)
        handler.postDelayed(
            unrepairedCheckRunnable,
            KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS
        )
    }

    /**
     * The picture has been broken for [KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS]
     * and no keyframe has arrived to repair it. Spend a cycle if the budget allows and the wire has
     * settled - a cycle spent while frames are still arriving broken buys a keyframe that arrives
     * broken too, and stamps the cooldown that then holds off the one that would have worked.
     */
    @Synchronized
    private fun escalateIfStillUnrepaired() {
        val since = unrepairedSinceMs
        if (since == 0L) return

        val now = SystemClock.elapsedRealtime()
        val spent = "$focusCyclesUsedThisSession/${KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_SESSION}"

        val action = KeyframeCycleEscalationPolicy.decide(
            now, since, focusCyclesUsedThisSession, lastFocusCycleMs, lastWireCorruptionMs
        )
        when (action) {
            KeyframeCycleEscalationPolicy.Action.NUDGE -> AppLog.w(
                "AapTransport: picture unrepaired for ${now - since}ms, no cycle available now " +
                    "($spent spent) - waiting for the phone's own keyframe"
            )

            KeyframeCycleEscalationPolicy.Action.WAIT_FOR_QUIET -> {
                if (AuditReportPolicy.shouldReport(quietHoldReports, quietHoldLastLogMs, now)) {
                    val suppressed = quietHoldSuppressed
                    quietHoldSuppressed = 0
                    quietHoldReports++
                    quietHoldLastLogMs = now
                    val suffix =
                        if (suppressed > 0) " (and $suppressed more checks since the last report)" else ""
                    AppLog.w(
                        "AapTransport: picture unrepaired for ${now - since}ms but the stream is " +
                            "still losing frames (last ${now - lastWireCorruptionMs}ms ago) - " +
                            "holding the cycle until it settles ($spent spent)$suffix"
                    )
                } else {
                    quietHoldSuppressed++
                }
            }

            KeyframeCycleEscalationPolicy.Action.CYCLE_FOCUS -> {
                // Resolved before the claim, not after: a release whose regain can never be posted
                // is the one outcome worse than not cycling at all, and it would strand the lever.
                val handler = sendHandler ?: return
                if (!beginFocusCycle()) {
                    // The other escalation holds the lever. Its release will bring the keyframe this
                    // one wanted, so leave the clock running and let that keyframe clear it; spending
                    // the budget on a cycle we are not going to send would lose it for nothing.
                    AppLog.w("AapTransport: picture unrepaired for ${now - since}ms - a focus cycle is already in flight, waiting for it")
                    // Look again once that cycle has had its regain and the keyframe it brings has
                    // had time to land. Returning without re-arming would leave unrepairedSinceMs set
                    // with nothing pending to clear it but the phone's own keyframe a GOP away, and
                    // triggerFocusCycleRecovery() returns early on that latch - so every later drop
                    // would be ignored too. If the other cycle worked, the keyframe has already
                    // zeroed the clock and this check returns at its first line.
                    handler.removeCallbacks(unrepairedCheckRunnable)
                    handler.postDelayed(
                        unrepairedCheckRunnable,
                        KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS
                    )
                    return
                }
                focusCyclesUsedThisSession++
                focusCyclesSpentThisSession++
                lastFocusCycleMs = now
                lastBudgetChangeMs = now
                AppLog.w(
                    "AapTransport: picture unrepaired for ${now - since}ms - cycling video focus " +
                        "($focusCyclesUsedThisSession/${KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_SESSION})"
                )
                sendKeyframeCycleRelease()
                // One shared regain runnable, replaced rather than tracked per cycle. Sound because
                // CYCLE_COOLDOWN_MS keeps two cycles sixty seconds apart against a 400ms regain gap,
                // so two can never be in flight together - see that constant before shortening it.
                handler.removeCallbacks(focusCycleGainRunnable)
                handler.postDelayed(focusCycleGainRunnable, WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS)
            }
        }

        // Look again after the cooldown while the picture is still broken and the budget still has
        // something in it. Without this, a cycle the cooldown refused - or one that fired and did not
        // work - would be the end of it for the session, because a drop arriving while the clock is
        // already running deliberately does not re-arm anything. Once the budget is gone nothing is
        // re-armed and the line above has said so.
        if (focusCyclesUsedThisSession < KeyframeCycleEscalationPolicy.MAX_CYCLES_PER_SESSION) {
            // A refused cycle and a held one clear on different clocks. The budget and the cooldown
            // are minute-scale, but a wire that has gone quiet does so in a couple of seconds, and
            // looking again in sixty would hand back everything holding the cycle was meant to save.
            val retryIn = if (action == KeyframeCycleEscalationPolicy.Action.WAIT_FOR_QUIET) {
                KeyframeCycleEscalationPolicy.ESCALATE_AFTER_UNREPAIRED_MS
            } else {
                KeyframeCycleEscalationPolicy.CYCLE_COOLDOWN_MS
            }
            sendHandler?.removeCallbacks(unrepairedCheckRunnable)
            sendHandler?.postDelayed(unrepairedCheckRunnable, retryIn)
        }
    }

    /**
     * A keyframe produced a picture, so whatever was broken is repaired. Stops the clock and
     * disarms the escalation.
     *
     * The signal is the decoder's output, not its input: an access unit that lost a fragment in the
     * middle still scans as a keyframe and is fed like one, and cancelling the escalation on that
     * left a sustained-loss stream with nothing able to ask for a keyframe it could actually decode.
     *
     * Runs on the decoder's output thread. Everything it touches is this object's own state and it
     * never waits on the decoder, which is what keeps it clear of [VideoDecoder.stop] - that holds
     * the decoder's monitor while joining the very thread this runs on.
     */
    @Synchronized
    private fun onKeyframeRepairedPicture() {
        if (unrepairedSinceMs == 0L) return
        unrepairedSinceMs = 0L
        sendHandler?.removeCallbacks(unrepairedCheckRunnable)
    }

    init {
        micRecorder.listener = this
        aapAudio = AapAudio(audioDecoder, audioManager, settings, context)
        // A corrupt access unit is the one fault the phone cannot heal for us inside a GOP, and
        // hasRenderedThisSession is the gate that keeps this clear of the warm-up window
        // [WarmRelaunchKeyframePolicy] owns - the same gate VideoDecoder.notifyFrameDropped uses.
        aapVideo = AapVideo(videoDecoder, settings) {
            triggerFocusCycleRecovery(
                escalatable = videoDecoder.hasRenderedThisSession,
                wireCorruption = true,
            )
        }

        // A rebuilt codec resumes on a P-frame and can render nothing until an IDR arrives, which
        // the phone sends on its own ~69s cadence and which nothing in AAP can request. So this is
        // the moment the picture is most certainly broken, and it used to be the moment the only
        // lever that can repair it was switched off: the old wiring abandoned the escalation clock
        // and armed nothing, so a decoder restarting every ten seconds could never reach a cycle.
        videoDecoder.onDecoderError = { _ ->
            triggerFocusCycleRecovery(escalatable = true, wireCorruption = false)
        }

        // Same ask, from a decoder that is deliberately *not* rebuilding while it waits.
        videoDecoder.onKeyframeStarved = {
            triggerFocusCycleRecovery(escalatable = true, wireCorruption = false)
        }

        videoDecoder.onFrameDropped = {
            triggerFocusCycleRecovery(escalatable = true, wireCorruption = false)
        }

        videoDecoder.onKeyframeObserved = {
            onKeyframeRepairedPicture()
        }
    }

    // Synchronized against noteDroppedSensorEvent, whose log line iterates startedSensors from the
    // main thread while this adds from the poll thread.
    @Synchronized
    internal fun startSensor(type: Int) {
        startedSensors.add(type)
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int): Int {
        val ba =
            ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data) ?: return -1

        ba.data[0] = data[0]
        ba.data[1] = data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)

        val size = connection?.sendBlocking(ba.data, ba.limit, 250) ?: -1

        // Silent until it matters. A failed write here is how "the ByeBye went out" and "the link
        // was already gone" tell themselves apart, which is the whole question for a teardown
        // racing an interface going down.
        if (size < 0) AppLog.w("AapTransport: send failed (ret=$size); the link is already gone")

        if (AppLog.LOG_VERBOSE) {
            AppLog.v("Sent size: %d", size)
            // AapDump.logvHex("US", 0, ba.data, ba.limit) // AapDump might be removed or changed
        }
        return 0
    }

    internal fun stop() {
        AppLog.i("AapTransport stopping and sending byebye")
        val byebye = Control.ByeByeRequest.newBuilder()
            .setReason(Control.ByeByeReason.USER_SELECTION)
            .build()
        val msg =
            AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, byebye)
        send(msg)
        SystemClock.sleep(150)
        quit()
    }

    internal fun quit(clean: Boolean = false) {
        val cb = onQuit ?: return
        onQuit = null

        AppLog.i("AapTransport quitting (clean=$clean)")
        cb.invoke(clean)
        micRecorder.stop()
        micRecorder.listener = null
        sendHandler?.removeCallbacks(focusCycleGainRunnable)
        sendHandler?.removeCallbacks(unrepairedCheckRunnable)
        pollThread?.quit()
        sendThread?.quit()
        aapAudio.releaseAllFocus()
        aapVideo.release()

        // Never let a half-finished cycle outlive the transport that owed the regain: the claim is
        // session state, and a stuck one would refuse every cycle of the next session.
        endFocusCycle()

        videoDecoder.onDecoderError = null
        videoDecoder.onKeyframeStarved = null
        videoDecoder.onFrameDropped = null
        videoDecoder.onKeyframeObserved = null

        try {            // Don't join the poll thread from within itself — it would block for the full
            // timeout since the thread can't finish while it's waiting for itself to finish.
            if (Thread.currentThread() != pollThread) pollThread?.join(1000)
            sendThread?.join(1000)
        } catch (e: InterruptedException) {
            AppLog.e("Failed to join threads", e)
        }

        aapRead = null
        ssl.release()
        pollHandler = null
        sendHandler = null
        pollThread = null
        sendThread = null
    }

    /**
     * Phase 1 of startup: creates the send/poll threads and runs the SSL handshake.
     *
     * Returns `true` on success. On failure, threads are stopped via [quit] before returning.
     * Must be followed by [startReading] (called after the projection surface is ready)
     * to actually start the message loop.
     */
    internal fun startHandshake(connection: AccessoryConnection): Boolean {
        AppLog.i("Start Aap transport handshake for $connection")
        this.connection = connection
        wasUserExit = false
        // This object outlives a session and is re-armed for the next one, so a stamp left by the
        // previous phone would read as a live link for the first seconds of this one.
        lastMessageReceivedMs = 0L
        linkGapMonitor.reset()
        videoGapMonitor.reset()
        audioGapMonitor.reset()
        uplinkStallMonitor.reset()

        sendThread = HandlerThread("AapTransport:Handler::Send", Process.THREAD_PRIORITY_AUDIO)
        sendThread!!.start()
        sendHandler = Handler(sendThread!!.looper, sendHandlerCallback)
        sendHandler?.post { LegacyOptimizer.setHighPriority() }

        pollThread = HandlerThread("AapTransport:Handler::Poll", Process.THREAD_PRIORITY_AUDIO)
        pollThread!!.start()
        pollHandler = Handler(pollThread!!.looper, pollHandlerCallback)
        pollHandler?.post { LegacyOptimizer.setHighPriority() }

        // No sleep needed here: Handler(thread.looper, ...) already blocks internally until the
        // HandlerThread's Looper is ready (via HandlerThread.getLooper() → wait/notifyAll).

        if (!handshake(connection)) {
            quit()
            AppLog.e("Handshake failed")
            return false
        }

        return true
    }

    /**
     * Phase 2 of startup: creates [AapRead] and posts the first [MSG_POLL] to begin the
     * inbound message loop.
     *
     * Must only be called after [startHandshake] has returned `true` **and** after the
     * projection surface has been set on the [VideoDecoder]. This guarantees that no video
     * frame is ever decoded before a render target exists.
     */
    internal fun startReading() {
        AppLog.i("Start Aap transport read loop")
        aapRead = AapRead.Factory.create(
            connection!!,
            this,
            micRecorder,
            aapAudio,
            aapVideo,
            settings,
            context,
            onAaMediaMetadata,
            onAaPlaybackStatus
        )
        pollHandler?.sendEmptyMessage(MSG_POLL)
    }

    private fun handshake(connection: AccessoryConnection): Boolean {
        lastHandshakeFailure = HandshakeFailure.OTHER
        try {
            val isUsb = connection !is SocketAccessoryConnection && !connection.isSingleMessage
            // Increased delay for AA 16.4+ stability on USB - skip for Sockets
            if (isUsb) {
                SystemClock.sleep(500)
            }

            val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

            // Drain any stale data left in the USB pipe from a previous session.
            // MUST ONLY be run on USB; on TCP Sockets, draining discards initial response bytes!
            var drained = 0
            if (isUsb) {
                while (true) {
                    val n = try { connection.recvBlocking(buffer, buffer.size, 50, false) } catch (e: Exception) { -1 }
                    if (n <= 0) break
                    drained += n
                }
                if (drained > 0) {
                    AppLog.i("Handshake: Drained $drained bytes of stale USB data before version request")
                }
            }

            AppLog.d("Handshake: Starting version request. TS: ${SystemClock.elapsedRealtime()}")
            val version = Messages.versionRequest
            var ret = -1
            var attempt = 0
            var received = false
            // Separates "the peer is not answering" from "our own link broke". Only the first
            // is worth reporting upwards: it is the one the user can do something about.
            var peerSentBytes = false
            var transportError = false
            // Outer deadline prevents the loop from running for minutes on an unresponsive device.
            // Each send+recv pair uses 2 s per operation; 3 attempts × 4 s ≈ 12 s worst-case,
            // capped here at HANDSHAKE_TIMEOUT_MS so a stuck device fails fast.
            val versionDeadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
            while (attempt < 3 && connection.isConnected) {
                if (SystemClock.elapsedRealtime() >= versionDeadline) {
                    AppLog.e("Handshake: Version exchange timed out after $attempt attempt(s).")
                    return false
                }
                attempt++
                ret = connection.sendBlocking(version, version.size, 2000)
                AppLog.d("Handshake: Version request sent. ret: $ret. attempt: $attempt. TS: ${SystemClock.elapsedRealtime()}")
                if (ret < 0) {
                    AppLog.w("Handshake: Version request send failed (ret=$ret), attempt $attempt")
                    transportError = true
                    SystemClock.sleep(200)
                    continue
                }

                AppLog.d("Handshake: Waiting for version response. TS: ${SystemClock.elapsedRealtime()}")
                // Inner loop: drain messages until we see channel=0 type=2 (VERSION_RESPONSE).
                // On first connection the phone may send a proactive message (e.g. a ping or a
                // status) before the version response arrives. Accepting any non-empty read as
                // "version response received" would hand a random payload to the SSL layer and
                // cause a 15 s timeout. Instead, discard unexpected messages and keep reading
                // until the deadline expires.
                val recvDeadline = SystemClock.elapsedRealtime() + 2000
                while (SystemClock.elapsedRealtime() < recvDeadline) {
                    val remaining = (recvDeadline - SystemClock.elapsedRealtime())
                        .toInt().coerceAtLeast(100)
                    ret = connection.recvBlocking(buffer, buffer.size, remaining, false)
                    if (ret < 0) transportError = true   // EOF or IOException, not a timeout
                    if (ret > 0) peerSentBytes = true
                    if (ret <= 0) break  // timeout or error — fall through to outer retry
                    if (ret >= 6
                        && buffer[0] == 0.toByte()
                        && buffer[4] == 0.toByte()
                        && buffer[5] == 2.toByte()) {
                        AppLog.i("Handshake: Version response received (ret=$ret, attempt=$attempt).")
                        received = true
                        break
                    }
                    // Wrong message — log and keep draining.
                    val ch   = buffer[0].toInt() and 0xFF
                    val type = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                    AppLog.w("Handshake: Ignoring unexpected message " +
                             "(ch=$ch, type=0x${type.toString(16)}, len=$ret). " +
                             "Waiting for VERSION_RESPONSE.")
                }
                if (received) break
                AppLog.w("Handshake: No VERSION_RESPONSE within 2s (attempt $attempt), ret=$ret")
                SystemClock.sleep(200)
            }

            if (!received) {
                AppLog.e("Handshake: Version request/response failed after $attempt attempt(s). last ret: $ret")
                if (!peerSentBytes && !transportError) {
                    lastHandshakeFailure = HandshakeFailure.PEER_SILENT
                    AppLog.e(
                        "Handshake: the peer accepted the connection and then sent nothing at all. " +
                            "Our link is fine — every read timed out rather than failing. On the WiFi " +
                            "head unit server path this means Android Auto's server on the phone is " +
                            "still bound to an earlier connection; it does not recover on its own and " +
                            "has to be stopped and started again in Android Auto's developer settings."
                    )
                }
                return false
            }
            AppLog.i("Handshake: Version response recv ret: %d", ret)

            AppLog.d("Handshake: Starting SSL handshake via performHandshake(). TS: ${SystemClock.elapsedRealtime()}")
            if (!ssl.performHandshake(connection)) {
                AppLog.e("Handshake: SSL performHandshake failed.")
                return false
            }

            ssl.postHandshakeReset()
            AppLog.d("Handshake: SSL buffers reset after handshake.")

            AppLog.d("Handshake: SSL handshake complete. TS: ${SystemClock.elapsedRealtime()}")
            // Status = OK
            val status = Messages.statusOk
            ret = connection.sendBlocking(status, status.size, 2000)
            AppLog.d("Handshake: Status OK sent. ret: $ret. TS: ${SystemClock.elapsedRealtime()}")
            if (ret < 0) {
                AppLog.e("Handshake: Status request sendEncrypted ret: $ret")
                return false
            }

            AppLog.i("Handshake: Status OK sent: %d", ret)
            AppLog.d("Handshake: Handshake successful. TS: ${SystemClock.elapsedRealtime()}")

            lastHandshakeFailure = HandshakeFailure.NONE
            return true
        } catch (e: Exception) {
            AppLog.e("Handshake failed with exception", e)
            return false
        }
    }

    fun send(keyCode: Int, isPress: Boolean) {
        val aapKeyCode = KeyCode.convert(keyCode)

        if (keyCode == KeyEvent.KEYCODE_N) {
            val intent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            return
        }

        if (aapKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            AppLog.i("Unknown: $keyCode")
        }

        val ts = SystemClock.elapsedRealtime()
        if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT || aapKeyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (isPress) {
                val delta = if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT) -1 else 1
                send(ScrollWheelEvent(ts, delta))
            }
            return
        }

        send(KeyCodeEvent(ts, aapKeyCode, isPress))
    }

    fun send(sensor: SensorEvent): Boolean {
        if (isAlive && startedSensors.contains(sensor.sensorType)) {
            send(sensor as AapMessage)
            return true
        }
        noteDroppedSensorEvent(sensor.sensorType)
        return false
    }

    /**
     * A dropped sensor event is worth knowing about once, not once per event. LOCATION resends on
     * a timer for the whole session, so logging every drop would bury everything else in a log a
     * reporter attaches to an issue. Report the first drop of a type straight away, then a running
     * count at most every [SENSOR_DROP_LOG_INTERVAL_MS] for as long as it keeps happening.
     */
    @Synchronized
    private fun noteDroppedSensorEvent(sensorType: Int) {
        val previous = droppedSensorEvents[sensorType] ?: 0
        droppedSensorEvents[sensorType] = previous + 1

        val now = SystemClock.elapsedRealtime()
        if (previous > 0 && now - lastSensorDropLogMs < SENSOR_DROP_LOG_INTERVAL_MS) {
            return
        }
        lastSensorDropLogMs = now
        AppLog.i("AapTransport: dropping sensor events, isAlive=$isAlive startedSensors=$startedSensors droppedByType=$droppedSensorEvents")
    }

    fun send(message: AapMessage) {
        val handler = sendHandler
        if (handler == null) {
            AppLog.i("Cannot send message, handler is null (quitting?)")
        } else {
            if (AppLog.LOG_VERBOSE) {
                AppLog.v(message.toString())
            }
            val msg = handler.obtainMessage(MSG_SEND, 0, message.size, message.data)
            handler.sendMessage(msg)
        }
    }

    internal fun gainVideoFocus() {
        context.sendBroadcast(ProjectionActivityRequest())
    }

    internal fun sendMediaAck(channel: Int) {
        send(MediaAck(channel, sessionIds.get(channel)))
    }

    internal fun setSessionId(channel: Int, sessionId: Int) {
        sessionIds.put(channel, sessionId)
    }

    override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int) {
        if (mic_audio_len > 64) {  // If we read at least 64 bytes of audio data
            val length = mic_audio_len + 12  // 4 header + 8 timestamp + PCM
            val data = ByteArray(length)
            data[0] = Channel.ID_MIC.toByte()
            data[1] = 0x0b
            // Timestamp at byte 4 so the full 8 bytes are inside the encrypted payload (HEADER_SIZE=4)
            Utils.put_time(4, data, SystemClock.elapsedRealtime())
            System.arraycopy(mic_buf, 0, data, 12, mic_audio_len)
            send(AapMessage(Channel.ID_MIC, 0x0b.toByte(), -1, 2, length, data))
        }
    }

    companion object {
        private const val MSG_POLL = 1
        private const val MSG_SEND = 2
        // Maximum wall-clock time allowed for the version-exchange phase of the AAP handshake.
        // Prevents the retry loop from blocking for minutes on an unresponsive USB device.
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val SENSOR_DROP_LOG_INTERVAL_MS = 30_000L
    }
}
