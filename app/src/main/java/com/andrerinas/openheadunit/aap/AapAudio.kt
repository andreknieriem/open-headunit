package com.andrerinas.openheadunit.aap

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.decoder.audio.AudioSinkCodec
import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.decoder.audio.AudioDiagnostics
import com.andrerinas.openheadunit.decoder.audio.AudioSinkSetupPolicy
import com.andrerinas.openheadunit.decoder.audio.AudioStreamCatalog
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusLease
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.ConcurrentHashMap

internal class AapAudio(
        private val audioDecoder: AudioDecoder,
        private val audioManager: AudioManager,
        private val settings: Settings) {

    internal val sessionConfig = AudioSessionConfig.from(settings)
    private val staticAudioFocus = sessionConfig.staticFocus
    private val separateAudioStreams = sessionConfig.separateStreams
    // Checked against what this device actually reports, not taken on trust: settings travel
    // between head units, and an id this one does not have would be silence with no message.
    private val mediaAudioStream = AudioStreamCatalog.sanitize(audioManager, sessionConfig.mediaStream)
    private val guidanceAudioStream = AudioStreamCatalog.sanitize(audioManager, sessionConfig.guidanceStream)
    private val systemAudioStream = AudioStreamCatalog.sanitize(audioManager, sessionConfig.systemStream)
    private val mediaVolumeOffset get() = settings.mediaVolumeOffset
    private val guidanceVolumeOffset get() = settings.guidanceVolumeOffset
    private val systemVolumeOffset get() = settings.systemVolumeOffset
    private val audioLatencyMultiplier get() = settings.audioLatencyMultiplier
    // The codec each sink actually carries, from the phone's Media Sink Setup. The setting alone
    // used to decide, and the band cap now announces AAC the setting knows nothing about.
    private val sinkCodecs = ConcurrentHashMap<Int, AudioSinkCodec>()
    @Volatile private var announcedMediaAac: Boolean? = null
    private fun defaultCodec(channel: Int) = sessionConfig.codecFor(channel, announcedMediaAac ?: sessionConfig.aac)

    /**
     * Captured before ServiceDiscovery is sent. The band cap can choose AAC even when its
     * preference is off, so data or an unrecognized Setup must fall back to what we advertised.
     * Valid Setup still wins per sink; local output restarts retain both negotiation records.
     */
    fun noteAnnouncedAudioCodecs(mediaAac: Boolean) {
        announcedMediaAac = mediaAac
    }
    private val pcmTiming = mapOf(
        Channel.ID_AUD to AudioTimestampMonitor(),
        Channel.ID_AU1 to AudioTimestampMonitor(),
        Channel.ID_AU2 to AudioTimestampMonitor()
    )
    private val audioQueueCapacity get() = settings.audioQueueCapacity
    private val enableAudioSink = sessionConfig.enabled
    private val attachHwDspEqualizer = sessionConfig.attachHwDspEqualizer
    private val playbackFocusMode = sessionConfig.focusMode

    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var protocolFocusCallback: AudioManager.OnAudioFocusChangeListener? = null
    // AudioManager identifies focus by listener, not by AudioFocusRequest object. Each session
    // uses one explicit instance so requests replace its client without sharing another session's.
    private val protocolFocusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(change: Int) {
            protocolFocusCallback?.onAudioFocusChange(change)
        }
    }

    @Volatile
    private var playbackFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var legacyPlaybackFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    // Dynamic-mode playback focus: some phones never send an AudioFocusRequestNotification(GAIN)
    // when media starts (the always-grant handshake makes them assume the head unit always holds
    // focus), so relying on the protocol notification alone leaves the car radio playing alongside
    // AA audio. Instead we hold system audio focus for as long as any AA audio channel is actively
    // playing and release it when the last one stops. Static mode manages focus permanently and is
    // left untouched.
    //
    // Whether we take it at all is PlaybackFocusPolicy's call: on a head unit that is also the
    // phone's Bluetooth A2DP sink, the focus grab makes the sink service AVRCP-pause that same
    // phone, silencing the stream we are playing. AUTO finds that out by trying and watching, and
    // the answer is remembered in settings so the trial happens once per head unit.
    private val playbackLease = PlaybackFocusLease()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drainPollScheduled = java.util.concurrent.atomic.AtomicBoolean()
    private val drainPoll = object : Runnable {
        override fun run() {
            releaseDrainedPlaybackFocus(SystemClock.elapsedRealtime())
            drainPollScheduled.set(false)
            if (playbackLease.snapshot().isNotEmpty()) scheduleDrainPoll()
        }
    }

    private fun releaseDrainedPlaybackFocus(nowMs: Long) {
        for ((channel, activity) in playbackLease.snapshot()) {
            if (audioDecoder.isQuiescent(channel, activity.owner, nowMs) && playbackLease.release(channel, activity)) {
                holdingPlaybackFocus = false
                releasePlaybackFocus()
            }
        }
    }

    private fun scheduleDrainPoll() {
        if (drainPollScheduled.compareAndSet(false, true)) mainHandler.postDelayed(drainPoll, 50)
    }

    private val decoderSession: AudioDecoder.PlaybackSession
    init {
        decoderSession = audioDecoder.openSession(AudioDecoder.PlaybackCallbacks(
            activity = ::onAudioPlaybackStarted,
            registered = { channel, owner -> playbackLease.register(channel, owner)?.let(::postPlaybackRelease) },
            retired = { channel, owner -> playbackLease.retire(channel, owner)?.let(::postPlaybackRelease) },
            canRender = { staticAudioFocus || !enableAudioSink ||
                playbackLease.canRender(SystemClock.elapsedRealtime()) },
            onSessionClosed = {
                playbackLease.close()
                // Post before the shared output's bounded join; all resources belong to this AapAudio.
                mainHandler.post { releaseAllFocusOnMain() }
            }
        ))
    }
    private val playbackFocusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(change: Int) {
            AppLog.i("AapAudio: playback audio focus changed: $change")
        }
    }

    @Volatile
    private var holdingPlaybackFocus = false
    @Volatile
    private var focusAcquiredAtMs = 0L
    @Volatile
    private var selfDefeatingStops = 0
    @Volatile
    private var selfDefeatingLatched = settings.playbackFocusSelfDefeating

    /** Which of the gates said no, so a reporter log names it instead of leaving it to be inferred. */
    private fun declineReason(): String = when {
        staticAudioFocus -> "static audio focus holds it instead"
        !enableAudioSink -> "the audio sink is off"
        // The mode is asked first because only AUTO consults the latch. Asking the latch first read
        // a persisted flag out to every mode, so a NEVER decline reported a reason it never used.
        playbackFocusMode != PlaybackFocusPolicy.Mode.AUTO -> "mode=$playbackFocusMode"
        selfDefeatingLatched -> "taking it stops this phone's own playback (mode=AUTO, learned)"
        else -> "mode=$playbackFocusMode"
    }

    /**
     * Whether a focus request the phone asked for over the protocol should reach the system.
     *
     * [AapControl] answers every AudioFocusRequestNotification with an always-grant reply, which is
     * what keeps AA routing audio to us; separately it asks the system for the focus the phone
     * wanted, so other apps on the head unit duck. That second half is the same act that makes an
     * A2DP sink AVRCP-pause the phone we project, and it honours GAIN as a *permanent* grab, so it
     * needs the same rule as the playback path.
     *
     * RELEASE is never gated: abandoning focus must always work, or a grab from before the link
     * came up would be stranded for the rest of the session.
     *
     * The latch gates this path but is never armed by it: arming happens in [onAudioPlaybackStopped],
     * which only runs while the playback path holds focus. So this path follows what that one
     * learned rather than learning anything itself.
     */
    fun shouldHonourProtocolFocusRequest(isRelease: Boolean): Boolean {
        if (isRelease) return true

        // isAudioChannel: the notification arrives on the control channel, but the question being
        // asked is about audio focus. The flag means "this is an audio-focus decision", not "this
        // message came in on an audio channel".
        val honour = PlaybackFocusPolicy.shouldAcquire(
                mode = playbackFocusMode,
                staticAudioFocus = staticAudioFocus,
                audioSinkEnabled = enableAudioSink,
                isAudioChannel = true,
                selfDefeatingLatched = selfDefeatingLatched)

        if (!honour) {
            AppLog.i("AapAudio: phone asked for audio focus - leaving system audio focus alone " +
                    "(${declineReason()})")
        }
        return honour
    }

    /** AapControl replies synchronously; system focus, including static startup, runs on main. */
    fun postProtocolFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener) {
        mainHandler.post {
            if (!playbackLease.isOpen()) return@post
            try { requestFocusChange(stream, focusRequest, callback) }
            catch (e: Exception) { AppLog.e("AapAudio: protocol focus request failed", e) }
            // releaseAllFocus also posts cleanup after invalidating this session. A Binder call
            // already in flight is followed by that cleanup on the same main queue.
        }
    }

    /** Called on main for protocol requests and CommManager's permanent static-focus request. */
    private fun requestFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener): Int {
        AppLog.i("Audio Focus Request: stream=$stream, type=$focusRequest")
        val isRelease = focusRequest == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE
        if (isRelease) retainPlaybackFocusBeforeProtocolRelease()
        else protocolFocusCallback = callback

        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            if (focusRequest == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE) {
                AppLog.i("Releasing audio focus")
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
                result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val usage = when (stream) {
                    AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    AudioManager.STREAM_VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else -> AudioAttributes.USAGE_MEDIA
                }

                val audioAttributes = AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                audioFocusRequest = AudioFocusRequest.Builder(focusRequest)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(protocolFocusListener)
                        .build()

                result = audioManager.requestAudioFocus(audioFocusRequest!!)
                AppLog.i("Audio focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
            }
        } else { // API < 26
            @Suppress("DEPRECATION")
            result = when (focusRequest) {
                Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> {
                    legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
                    legacyFocusListener = null
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> {
                    legacyFocusListener = protocolFocusListener
                    audioManager.requestAudioFocus(protocolFocusListener, stream, AudioManager.AUDIOFOCUS_GAIN)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> {
                    legacyFocusListener = protocolFocusListener
                    audioManager.requestAudioFocus(protocolFocusListener, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> {
                    legacyFocusListener = protocolFocusListener
                    audioManager.requestAudioFocus(protocolFocusListener, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }
                else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            AppLog.i("Audio focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
        if (!isRelease && focusRequest == AudioManager.AUDIOFOCUS_GAIN &&
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) holdingPlaybackFocus = false
        if (isRelease) protocolFocusCallback = null
        return result
    }

    private fun retainPlaybackFocusBeforeProtocolRelease() {
        releaseDrainedPlaybackFocus(SystemClock.elapsedRealtime())
        if (playbackLease.snapshot().isEmpty() || !PlaybackFocusPolicy.shouldAcquire(
                playbackFocusMode, staticAudioFocus, enableAudioSink, true, selfDefeatingLatched)) return
        // A protocol permanent GAIN removes the separate playback client from Android's stack.
        // Refresh it before abandoning protocol focus so pending PCM has no focus-free interval.
        // holdingPlaybackFocus cannot prove that the client survived that permanent GAIN.
        focusAcquiredAtMs = SystemClock.elapsedRealtime()
        try { holdingPlaybackFocus = requestPlaybackFocus() }
        catch (e: Exception) {
            holdingPlaybackFocus = false
            AppLog.e("AapAudio: cannot retain playback focus at protocol release", e)
        }
        // Retirement can post a release before this request is published, capturing no resource.
        // Recheck after Binder returns and abandon the current request if demand disappeared.
        if (!playbackLease.isOpen() || playbackLease.snapshot().isEmpty()) {
            holdingPlaybackFocus = false
            releasePlaybackFocus()
        }
    }

    /**
     * Use transient gain for dynamic playback: permanent GAIN sends other players (for example
     * the car radio) a permanent loss, so they may not resume when we abandon it. Transient loss
     * lets them pause and resume once all AA output drains. Static focus uses its separate path.
     */
    private fun requestPlaybackFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(playbackFocusListener)
                    .build()
            playbackFocusRequest = request

            val result = audioManager.requestAudioFocus(request)
            AppLog.i("AapAudio: Playback transient focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener = playbackFocusListener
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(playbackFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            AppLog.i("AapAudio: Playback transient focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releasePlaybackFocus(
        expectedRequest: AudioFocusRequest? = playbackFocusRequest,
        expectedLegacyListener: AudioManager.OnAudioFocusChangeListener? = legacyPlaybackFocusListener
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            expectedRequest?.let {
                AppLog.i("AapAudio: Releasing playback transient audio focus")
                audioManager.abandonAudioFocusRequest(it)
            }
            if (playbackFocusRequest === expectedRequest) playbackFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            expectedLegacyListener?.let {
                AppLog.i("AapAudio: Releasing playback transient audio focus (legacy)")
                audioManager.abandonAudioFocus(it)
            }
            if (legacyPlaybackFocusListener === expectedLegacyListener) legacyPlaybackFocusListener = null
        }
    }

    /** Registration retirement uses the same revision and resource checks as sleep release. */
    private fun postPlaybackRelease(version: Long) {
        val request = playbackFocusRequest
        val listener = legacyPlaybackFocusListener
        mainHandler.post {
            if (!playbackLease.acceptsRelease(version)) return@post
            holdingPlaybackFocus = false
            releasePlaybackFocus(request, listener)
        }
    }

    fun releaseAllFocus() {
        AppLog.i("AapAudio: Releasing all audio focus.")
        audioDecoder.closeSession(decoderSession)
    }

    private fun releaseAllFocusOnMain() {
        // The latch is a property of the head unit, so it comes back from settings rather than
        // clearing: a unit that pauses the phone when we take focus does it on every connection,
        // and re-running the trial each time would cost the user the same interrupted tracks again.
        holdingPlaybackFocus = false
        focusAcquiredAtMs = 0L
        selfDefeatingStops = 0
        selfDefeatingLatched = settings.playbackFocusSelfDefeating
        protocolFocusCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            playbackFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            playbackFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyFocusListener = null
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyPlaybackFocusListener = null
        }
    }

    /**
     * Processes a message as an audio stream packet.
     * Returns true if the packet was identified and processed as audio data, false otherwise.
     */
    fun process(message: AapMessage): Boolean {
        if (!AudioMediaPayload.isMedia(message.type)) return false
        val offset = AudioMediaPayload.offset(message)
        if (offset >= 0) {
            if (AudioMediaPayload.requiresAck(message.type)) {
                notePcmTiming(message, message.size - offset)
                decode(message.channel, offset, message.data, message.size - offset)
            } else {
                // CSD is not playback: do not take focus, duck music or feed the jitter bank.
                if (audioDecoder.getTrack(message.channel, decoderSession) == null) {
                    startAudioTrack(message.channel, announcePlayback = false)
                }
                audioDecoder.configure(message.channel, message.data, offset, message.size - offset, decoderSession)
            }
        }
        return true
    }

    private fun notePcmTiming(message: AapMessage, size: Int) {
        val monitor = pcmTiming[message.channel] ?: return
        // AAC timestamps can be repeated for several access units from the same capture batch.
        if ((audioDecoder.sinkCodecFor(message.channel, decoderSession) ?: sinkCodecs[message.channel] ?: defaultCodec(message.channel)).isAac) return
        val format = AudioConfigs.get(message.channel)
        val bytesPerFrame = format.numberOfChannels * format.numberOfBits / 8
        if (bytesPerFrame <= 0 || format.sampleRate <= 0) return
        val durationUs = (size / bytesPerFrame).toLong() * 1_000_000L / format.sampleRate
        val arrivalUs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            SystemClock.elapsedRealtimeNanos() / 1000L
        } else {
            SystemClock.elapsedRealtime() * 1000L
        }
        val report = monitor.onPacket(AudioMediaPayload.timestampUs(message), arrivalUs, durationUs) ?: return
        val line = "AapAudio: ${Channel.name(message.channel)} $report"
        AppLog.i(line)
        if (report.hasGap) AudioDiagnostics.record(arrivalUs / 1000L, line)
    }

    /**
     * [announcePlayback] is false when the sink is only being built. Announcing takes transient
     * system audio focus, which pauses the car radio, and a sink that has been set up is not a
     * sink that is playing: the phone sets all three up at connect and may never send to two.
     */
    private fun startAudioTrack(channel: Int, announcePlayback: Boolean = true) {
        if (audioDecoder.getTrack(channel, decoderSession) != null) return

        val config = AudioConfigs.get(channel)
        val stream = streamFor(channel)

        val offset = when (channel) {
            Channel.ID_AUD -> mediaVolumeOffset
            Channel.ID_AU1 -> guidanceVolumeOffset
            Channel.ID_AU2 -> systemVolumeOffset
            else -> 0
        }
        val gain = (1.0f + (offset / 100.0f)).coerceIn(0.0f, 2.0f)

        // Voice and Navigation benefit from lower latency. Cap the multiplier for those channels.
        val effectiveMultiplier = if (channel == Channel.ID_AUD) {
            audioLatencyMultiplier
        } else {
            audioLatencyMultiplier.coerceAtMost(4)
        }

        val fromSetup = sinkCodecs[channel]
        val codec = fromSetup ?: defaultCodec(channel)
        val codecSource = if (fromSetup != null) "setup" else if (announcedMediaAac != null) "discovery" else "setting"
        AppLog.i("AudioDecoder.start: channel=$channel, stream=$stream, gain=$gain, sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, numberOfChannels=${config.numberOfChannels}, codec=$codec, source=$codecSource, latencyMultiplier=$effectiveMultiplier, queueCapacity=$audioQueueCapacity, attachHwDspEqualizer=$attachHwDspEqualizer")
        audioDecoder.start(channel, stream, config.sampleRate, config.numberOfBits, config.numberOfChannels,
            codec.isAac, gain, effectiveMultiplier, audioQueueCapacity, staticAudioFocus, attachHwDspEqualizer,
            preferAAudio = settings.useAAudioOutput, isAdts = codec == AudioSinkCodec.AAC_LC_ADTS, session = decoderSession)
        if (announcePlayback) audioDecoder.getTrack(channel, decoderSession)?.let {
            onAudioPlaybackStarted(channel, it.playbackOwner)
        }
    }

    private fun onAudioPlaybackStarted(channel: Int, owner: Any) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return
        // Keep ownership bookkeeping separate from AudioService Binder work. This entry point
        // can run on the receive path; requesting focus inline would stall incoming audio while
        // Android or a vendor service is slow. The posted request rechecks its lease so an old
        // channel cannot acquire focus after teardown or release a replacement's playback.
        val request = playbackLease.activity(channel, owner, SystemClock.elapsedRealtime())
        scheduleDrainPoll()
        if (request == null) return
        mainHandler.post {
            if (!playbackLease.accepts(request)) return@post
            val acquire = PlaybackFocusPolicy.shouldAcquire(playbackFocusMode, staticAudioFocus,
                enableAudioSink, true, selfDefeatingLatched)
            var granted = false
            if (acquire) {
                // A new lease can invalidate a queued sleep release while the system focus
                // resource is still held. Reuse it instead of overwriting/leaking its identity.
                if (holdingPlaybackFocus) granted = true
                else {
                    focusAcquiredAtMs = SystemClock.elapsedRealtime()
                    try { granted = requestPlaybackFocus() }
                    catch (e: Exception) { AppLog.e("AapAudio: playback focus request failed", e) }
                }
            } else {
                releasePlaybackFocus()
                AppLog.i("AapAudio: playback without system focus (${declineReason()})")
            }
            if (playbackLease.complete(request)) holdingPlaybackFocus = granted
            else {
                // Teardown, quiet drain or timeout invalidated an in-flight Binder request.
                holdingPlaybackFocus = false
                releasePlaybackFocus()
            }
        }
    }

    /**
     * Stop diagnostics use the protocol clock; focus release waits for actual output progress.
     * Releasing on the last wire Stop can let the radio resume over buffered prompt tails.
     * The drain poll releases dynamic focus only after the current owners become quiescent;
     * stale owners cannot release a replacement channel's claim.
     */
    private fun onAudioPlaybackStopped(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return
        if (holdingPlaybackFocus) noteStopWhileHoldingFocus(channel)
        scheduleDrainPoll()
    }

    /**
     * Watches for the pathology AUTO is trying out: the media channel closing again almost as soon
     * as we took focus, because the phone stopped its own playback in response. Two of those in a
     * row and we stop asking for focus, so the session settles instead of cycling every few seconds.
     *
     * The answer is written to settings because it describes the head unit, not this connection.
     * Re-picking the focus mode clears it, which is the way back if the two stops were a coincidence.
     */
    private fun noteStopWhileHoldingFocus(channel: Int) {
        if (selfDefeatingLatched || focusAcquiredAtMs == 0L) return

        val elapsedMs = SystemClock.elapsedRealtime() - focusAcquiredAtMs
        if (!PlaybackFocusPolicy.countsAsSelfDefeating(channel == Channel.ID_AUD, elapsedMs)) {
            selfDefeatingStops = 0
            return
        }

        selfDefeatingStops++
        AppLog.d("AapAudio: media stopped ${elapsedMs}ms after taking audio focus " +
                "($selfDefeatingStops/${PlaybackFocusPolicy.SELF_DEFEATING_LIMIT})")
        if (selfDefeatingStops >= PlaybackFocusPolicy.SELF_DEFEATING_LIMIT) {
            selfDefeatingLatched = true
            settings.playbackFocusSelfDefeating = true
            AppLog.w("AapAudio: taking system audio focus is stopping the phone's own playback " +
                    "(the head unit is most likely its Bluetooth audio sink) - not acquiring it again, " +
                    "on this or a later connection, until the focus mode is re-picked")
        }
    }

    /** Records the codec the phone named for [channel] in its Media Sink Setup. */
    fun noteSinkCodec(channel: Int, setupType: Int) {
        if (!Channel.isAudio(channel)) return
        pcmTiming[channel]?.reset()
        val codec = AudioSinkCodecPolicy.codecFor(setupType)
        if (codec == null) {
            AppLog.w("AapAudio: sink setup type $setupType on ${Channel.name(channel)} is not an audio codec, keeping codec=${defaultCodec(channel)} from ${if (announcedMediaAac != null) "service discovery" else "the session settings"}")
            sinkCodecs.remove(channel)
        } else {
            sinkCodecs[channel] = codec
        }
    }

    /**
     * Build a sink when the phone sets it up, rather than on the first byte of audio.
     *
     * Both paths run on the transport's read thread, but setup happens before the phone streams
     * anything, where building an AudioTrack and, on the AAC path, a whole MediaCodec costs nobody
     * a gap. On the first byte of audio it cost the stream one. Setup is also the only point where
     * this sink's codec is known, so a track kept from a previous session is replaced here.
     *
     * A setup is not always the first of a session though, and a live sink is not replaced on one:
     * [AudioSinkSetupPolicy] carries what that cost.
     */
    fun precreateAudioTrack(channel: Int) {
        if (!Channel.isAudio(channel)) return
        val hasLiveTrack = audioDecoder.getTrack(channel, decoderSession) != null
        if (!AudioSinkSetupPolicy.rebuilds(hasLiveTrack, audioDecoder.sinkCodecFor(channel, decoderSession), sinkCodecs[channel])) {
            AppLog.i("AapAudio: ${Channel.name(channel)} is already set up, keeping the sink it has")
            return
        }
        if (hasLiveTrack) audioDecoder.stop(channel, decoderSession)
        startAudioTrack(channel, announcePlayback = false)
    }

    /** The sink is ready at Setup; Start can warm its output before the first PCM arrives. */
    fun preparePlayback(channel: Int) {
        pcmTiming[channel]?.reset()
        if (!enableAudioSink || !Channel.isAudio(channel)) return
        audioDecoder.preparePlayback(channel, decoderSession)?.let { onAudioPlaybackStarted(channel, it) }
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int) {
        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        val track = audioDecoder.getTrack(channel, decoderSession)
        if (track == null) {
            startAudioTrack(channel)
        } else {
            // Cheap and already guarded: it returns at once unless this is the first channel to
            // carry audio. The track may have been built at setup, so this is where focus is taken.
            onAudioPlaybackStarted(channel, track.playbackOwner)
        }

        audioDecoder.decode(channel, buf, start, length, decoderSession)
    }

    fun updateGains() {
        val mediaGain = (1.0f + (settings.mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val guidanceGain = (1.0f + (settings.guidanceVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val systemGain = (1.0f + (settings.systemVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)

        audioDecoder.setGain(Channel.ID_AUD, mediaGain, decoderSession)
        audioDecoder.setGain(Channel.ID_AU1, guidanceGain, decoderSession)
        audioDecoder.setGain(Channel.ID_AU2, systemGain, decoderSession)
    }

    internal fun streamFor(channel: Int): Int = AudioConfigs.stream(
        channel, separateAudioStreams, mediaAudioStream, guidanceAudioStream, systemAudioStream
    )

    internal fun needsSessionRestart(): Boolean = sessionConfig != AudioSessionConfig.from(settings)

    fun restartAudio() {
        AppLog.i("AapAudio: Restarting all audio tracks")
        pcmTiming.values.forEach { it.reset() }
        // sinkCodecs is kept: the phone sets a sink up once per session, and a restarted track
        // still carries the codec that setup named.
        audioDecoder.stop(decoderSession)
    }

    fun stopAudio(channel: Int) {
        pcmTiming[channel]?.reset()
        AppLog.i("Audio Stop: " + Channel.name(channel))
        // The mixer drains buffered speech and controls media ducking on its render clock.
        // Keep the decoder/output alive for the next prompt or media resume.
        audioDecoder.pause(channel, decoderSession)
        onAudioPlaybackStopped(channel)
    }

    /**
     * Park all audio channels and release transient playback focus (e.g. device sleep).
     * Channels will resume cleanly when new audio packets arrive.
     */
    fun pauseAllAudio() {
        AppLog.i("AapAudio: Pausing all audio tracks for sleep")
        pcmTiming.values.forEach { it.reset() }
        audioDecoder.pauseAll(decoderSession)
        postPlaybackRelease(playbackLease.clear())
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
    }
}
