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
import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.decoder.audio.AudioSinkSetupPolicy
import com.andrerinas.openheadunit.decoder.audio.AudioStreamCatalog
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.ConcurrentHashMap

internal class AapAudio(
        private val audioDecoder: AudioDecoder,
        private val audioManager: AudioManager,
        private val settings: Settings) {

    private val staticAudioFocus = settings.staticAudioFocus
    private val separateAudioStreams = settings.separateAudioStreams
    // Checked against what this device actually reports, not taken on trust: settings travel
    // between head units, and an id this one does not have would be silence with no message.
    private val mediaAudioStream = AudioStreamCatalog.sanitize(audioManager, settings.mediaAudioStream)
    private val guidanceAudioStream = AudioStreamCatalog.sanitize(audioManager, settings.guidanceAudioStream)
    private val systemAudioStream = AudioStreamCatalog.sanitize(audioManager, settings.systemAudioStream)
    private val mediaVolumeOffset = settings.mediaVolumeOffset
    private val guidanceVolumeOffset = settings.guidanceVolumeOffset
    private val systemVolumeOffset = settings.systemVolumeOffset
    private val audioLatencyMultiplier get() = settings.audioLatencyMultiplier
    private val useAacAudio = settings.useAacAudio
    // The codec each sink actually carries, from the phone's Media Sink Setup. The setting alone
    // used to decide, and the band cap now announces AAC the setting knows nothing about.
    private val sinkIsAac = ConcurrentHashMap<Int, Boolean>()
    private val audioQueueCapacity get() = settings.audioQueueCapacity
    private val enableAudioSink = settings.enableAudioSink
    private val attachHwDspEqualizer = settings.attachHwDspEqualizer
    private val playbackFocusMode = settings.playbackFocusMode

    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

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
    private val activeAudioChannels = mutableSetOf<Int>()
    private val playbackFocusListener = AudioManager.OnAudioFocusChangeListener {
        AppLog.i("AapAudio: playback audio focus changed: $it")
    }

    @Volatile
    private var holdingPlaybackFocus = false
    @Volatile
    private var focusAcquiredAtMs = 0L
    @Volatile
    private var selfDefeatingStops = 0
    @Volatile
    private var selfDefeatingLatched = settings.playbackFocusSelfDefeating

    @Volatile
    private var isDucked = false
    private val handler = Handler(Looper.getMainLooper())
    private val unduckRunnable = Runnable {
        unduckMedia()
    }

    private fun duckMedia() {
        if (!isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val duckedVolume = getMediaGain() * DUCK_VOLUME_FACTOR
                AppLog.i("Static Audio Focus: Ducking media volume to $duckedVolume")
                mediaTrack.setVolume(duckedVolume)
                isDucked = true
            }
        }
    }

    private fun unduckMedia() {
        if (isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val originalVolume = getMediaGain()
                AppLog.i("Static Audio Focus: Restoring media volume to $originalVolume")
                mediaTrack.setVolume(originalVolume)
            }
            isDucked = false
        }
    }

    private fun getMediaGain(): Float {
        return (1.0f + (mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
    }

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

    fun requestFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener): Int {
        AppLog.i("Audio Focus Request: stream=$stream, type=$focusRequest")

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
                        .setOnAudioFocusChangeListener(callback)
                        .build()

                result = audioManager.requestAudioFocus(audioFocusRequest!!)
                AppLog.i("Audio focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
            }
        } else { // API < 26
            @Suppress("DEPRECATION")
            result = when (focusRequest) {
                Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> {
                    audioManager.abandonAudioFocus(callback)
                    legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
                    legacyFocusListener = null
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }
                else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            AppLog.i("Audio focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
        return result
    }

    private fun requestPlaybackFocus() {
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
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener = playbackFocusListener
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(playbackFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            AppLog.i("AapAudio: Playback transient focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
    }

    private fun releasePlaybackFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackFocusRequest?.let {
                AppLog.i("AapAudio: Releasing playback transient audio focus")
                audioManager.abandonAudioFocusRequest(it)
            }
            playbackFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener?.let {
                AppLog.i("AapAudio: Releasing playback transient audio focus (legacy)")
                audioManager.abandonAudioFocus(it)
            }
            legacyPlaybackFocusListener = null
        }
    }

    fun releaseAllFocus() {
        AppLog.i("AapAudio: Releasing all audio focus.")
        synchronized(activeAudioChannels) { activeAudioChannels.clear() }
        // The latch is a property of the head unit, so it comes back from settings rather than
        // clearing: a unit that pauses the phone when we take focus does it on every connection,
        // and re-running the trial each time would cost the user the same interrupted tracks again.
        holdingPlaybackFocus = false
        focusAcquiredAtMs = 0L
        selfDefeatingStops = 0
        selfDefeatingLatched = settings.playbackFocusSelfDefeating
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
                decode(message.channel, offset, message.data, message.size - offset)
            } else {
                // CSD is not playback: do not take focus, duck music or feed the jitter bank.
                if (audioDecoder.getTrack(message.channel) == null) {
                    startAudioTrack(message.channel, announcePlayback = false)
                }
                audioDecoder.configure(message.channel, message.data, offset, message.size - offset)
            }
        }
        return true
    }

    /**
     * [announcePlayback] is false when the sink is only being built. Announcing takes transient
     * system audio focus, which pauses the car radio, and a sink that has been set up is not a
     * sink that is playing: the phone sets all three up at connect and may never send to two.
     */
    private fun startAudioTrack(channel: Int, announcePlayback: Boolean = true) {
        if (audioDecoder.getTrack(channel) != null) return

        val config = AudioConfigs.get(channel)
        val stream = AudioConfigs.stream(
            channel,
            separateAudioStreams,
            mediaAudioStream,
            guidanceAudioStream,
            systemAudioStream
        )

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

        val fromSetup = sinkIsAac[channel]
        val isAac = fromSetup ?: useAacAudio
        val codecSource = if (fromSetup != null) "setup" else "setting"
        AppLog.i("AudioDecoder.start: channel=$channel, stream=$stream, gain=$gain, sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, numberOfChannels=${config.numberOfChannels}, isAac=$isAac, source=$codecSource, latencyMultiplier=$effectiveMultiplier, queueCapacity=$audioQueueCapacity, attachHwDspEqualizer=$attachHwDspEqualizer")
        audioDecoder.start(channel, stream, config.sampleRate, config.numberOfBits, config.numberOfChannels, isAac, gain, effectiveMultiplier, audioQueueCapacity, staticAudioFocus, attachHwDspEqualizer, audioLatencyMultiplier, settings.useAAudioOutput)
        if (announcePlayback) onAudioPlaybackStarted(channel)
    }

    private fun onAudioPlaybackStarted(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return

        // Only the set mutation belongs under the lock. requestPlaybackFocus() is a binder
        // round-trip to AudioService and the Bluetooth probe below is another, and this runs on
        // the transport thread for every track change.
        val wasEmpty = synchronized(activeAudioChannels) {
            val empty = activeAudioChannels.isEmpty()
            activeAudioChannels.add(channel)
            empty
        }
        if (!wasEmpty) return

        val acquire = PlaybackFocusPolicy.shouldAcquire(
                mode = playbackFocusMode,
                staticAudioFocus = staticAudioFocus,
                audioSinkEnabled = enableAudioSink,
                isAudioChannel = true,
                selfDefeatingLatched = selfDefeatingLatched)

        if (!acquire) {
            AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - leaving system audio focus alone " +
                    "(${declineReason()})")
            return
        }

        // Use GAIN_TRANSIENT, not GAIN: a permanent GAIN sends other players (e.g. the car
        // radio) a permanent AUDIOFOCUS_LOSS, so they stop and do NOT resume when we later
        // abandon focus. TRANSIENT sends AUDIOFOCUS_LOSS_TRANSIENT so they pause and resume
        // once AA audio stops and we release focus.
        AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - acquiring transient system audio focus " +
                "(mode=$playbackFocusMode)")
        focusAcquiredAtMs = SystemClock.elapsedRealtime()
        holdingPlaybackFocus = true
        requestPlaybackFocus()
    }

    /**
     * Releases system audio focus once the last active AA audio channel stops (dynamic mode),
     * letting other local sources (e.g. the car radio) resume.
     */
    private fun onAudioPlaybackStopped(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return

        val nowEmpty = synchronized(activeAudioChannels) {
            activeAudioChannels.remove(channel)
            activeAudioChannels.isEmpty()
        }
        if (!nowEmpty || !holdingPlaybackFocus) return

        noteStopWhileHoldingFocus(channel)

        AppLog.i("AapAudio: last AA audio channel stopped - releasing transient system audio focus")
        holdingPlaybackFocus = false
        releasePlaybackFocus()
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
        val aac = AudioSinkCodecPolicy.isAac(setupType)
        if (aac == null) {
            AppLog.w("AapAudio: sink setup type $setupType on ${Channel.name(channel)} is not an audio codec, keeping isAac=$useAacAudio from the setting")
            sinkIsAac.remove(channel)
        } else {
            sinkIsAac[channel] = aac
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
        val hasLiveTrack = audioDecoder.getTrack(channel) != null
        if (!AudioSinkSetupPolicy.rebuilds(hasLiveTrack, audioDecoder.sinkCodecFor(channel), sinkIsAac[channel])) {
            AppLog.i("AapAudio: ${Channel.name(channel)} is already set up, keeping the sink it has")
            return
        }
        if (hasLiveTrack) audioDecoder.stop(channel)
        startAudioTrack(channel, announcePlayback = false)
    }

    /** The sink is ready at Setup; Start can warm its output before the first PCM arrives. */
    fun preparePlayback(channel: Int) {
        if (!enableAudioSink || !Channel.isAudio(channel)) return
        audioDecoder.preparePlayback(channel)
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int) {
        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        if (audioDecoder.getTrack(channel) == null) {
            startAudioTrack(channel)
        } else {
            // Cheap and already guarded: it returns at once unless this is the first channel to
            // carry audio. The track may have been built at setup, so this is where focus is taken.
            onAudioPlaybackStarted(channel)
        }

        audioDecoder.decode(channel, buf, start, length)

        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            duckMedia()
            handler.removeCallbacks(unduckRunnable)
            handler.postDelayed(unduckRunnable, UNDUCK_DELAY_MS)
        }
    }

    fun updateGains() {
        val mediaGain = (1.0f + (settings.mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val guidanceGain = (1.0f + (settings.guidanceVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val systemGain = (1.0f + (settings.systemVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)

        audioDecoder.setGain(Channel.ID_AUD, mediaGain)
        audioDecoder.setGain(Channel.ID_AU1, guidanceGain)
        audioDecoder.setGain(Channel.ID_AU2, systemGain)
    }

    fun restartAudio() {
        AppLog.i("AapAudio: Restarting all audio tracks")
        // sinkIsAac is kept: the phone sets a sink up once per session, and a restarted track
        // still carries the codec that setup named.
        audioDecoder.stop()
    }

    fun stopAudio(channel: Int) {
        AppLog.i("Audio Stop: " + Channel.name(channel))
        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            // Keep the speech wrappers alive to prevent recreate overhead and keep state consistent.
            // Just restore media volume.
            audioDecoder.pause(channel)
            handler.removeCallbacks(unduckRunnable)
            unduckMedia()
        } else {
            // Parked, not destroyed. The phone stops the media sink on every pause and every
            // assistant session, and rebuilding cost a fresh pre-roll plus a drain of up to a
            // second - heard as the skip on resume. The session teardown still stops it.
            audioDecoder.pause(channel)
            onAudioPlaybackStopped(channel)
        }
    }

    /**
     * Park all audio channels and release transient playback focus (e.g. device sleep).
     * Channels will resume cleanly when new audio packets arrive.
     */
    fun pauseAllAudio() {
        AppLog.i("AapAudio: Pausing all audio tracks for sleep")
        audioDecoder.pauseAll()
        synchronized(activeAudioChannels) { activeAudioChannels.clear() }
        holdingPlaybackFocus = false
        releasePlaybackFocus()
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
        private const val DUCK_VOLUME_FACTOR = 0.4f
        private const val UNDUCK_DELAY_MS = 1500L
    }
}
