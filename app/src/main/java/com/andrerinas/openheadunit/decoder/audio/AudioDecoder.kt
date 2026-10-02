package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.ConcurrentHashMap

class AudioDecoder {

    /** Retirement can arrive during construction, before the lease has seen this identity. */
    private class PlaybackOwner {
        private var retired = false
        @Synchronized fun publishIfActive(publish: () -> Unit): Boolean {
            if (retired) return false
            publish()
            return true
        }
        @Synchronized fun retire(notify: () -> Unit) {
            if (retired) return
            retired = true
            notify()
        }
    }

    private val audioTracks = ConcurrentHashMap<Int, AudioTrackWrapper>(3)
    private var mixer: AudioMixer? = null
    internal data class PlaybackCallbacks(
        val activity: (Int, Any) -> Unit = { _, _ -> },
        val registered: (Int, Any) -> Unit = { _, _ -> },
        val retired: (Int, Any) -> Unit = { _, _ -> },
        val canRender: () -> Boolean = { true },
        val onSessionClosed: () -> Unit = {}
    )
    class PlaybackSession internal constructor(internal val callbacks: PlaybackCallbacks) {
        internal var closed = false // Accessed only under the decoder lock.
    }
    @Volatile private var activeSession = PlaybackSession(PlaybackCallbacks())
    internal var playbackCallbacks: PlaybackCallbacks
        get() = activeSession.callbacks
        set(value) { openSession(value) }

    @Synchronized internal fun openSession(callbacks: PlaybackCallbacks): PlaybackSession {
        closeSession(activeSession)
        return PlaybackSession(callbacks).also { activeSession = it }
    }
    fun captureSession(): PlaybackSession = activeSession
    private fun accepts(session: PlaybackSession): Boolean = activeSession === session && !session.closed
    @Synchronized internal fun closeSession(session: PlaybackSession) {
        if (session.closed) return
        session.closed = true
        try { session.callbacks.onSessionClosed() }
        catch (e: Exception) { AppLog.e("AudioDecoder: session closure notification failed", e) }
        finally { if (activeSession === session) stopTracks() }
    }
    internal fun isQuiescent(channel: Int, owner: Any, nowMs: Long): Boolean {
        val track = audioTracks[channel] ?: return true
        return track.playbackOwner !== owner || track.isQuiescent(nowMs)
    }
    @Synchronized fun getTrack(channel: Int, session: PlaybackSession = activeSession): AudioTrackWrapper? {
        if (!accepts(session)) return null
        val track = audioTracks[channel] ?: return null
        if (track.isPlaybackHealthy()) return track
        stop(channel, session)
        return null
    }

    /** The codec the live sink on [channel] was built for, or null when there is no sink. */
    fun sinkCodecFor(channel: Int, session: PlaybackSession = activeSession): AudioSinkCodec? =
        synchronized(this) { if (accepts(session)) audioTracks[channel]?.builtCodec() else null }

    fun decode(channel: Int, buffer: ByteArray, offset: Int, size: Int, session: PlaybackSession = activeSession) {
        val audioTrack = synchronized(this) { if (accepts(session)) audioTracks[channel] else null }
        audioTrack?.write(buffer, offset, size)
    }

    fun configure(channel: Int, buffer: ByteArray, offset: Int, size: Int, session: PlaybackSession = activeSession) {
        val track = synchronized(this) { if (accepts(session)) audioTracks[channel] else null }
        track?.configureAac(buffer, offset, size)
    }

    @Synchronized fun stop(session: PlaybackSession = activeSession) {
        if (accepts(session)) stopTracks()
    }
    private fun stopTracks() {
        audioTracks.values.forEach { it.stopPlayback() }
        audioTracks.clear()
        releaseMixer()
    }

    /** Park a channel the phone stopped, keeping its track. See [AudioTrackWrapper.pauseForIdle]. */
    fun pause(chan: Int, session: PlaybackSession = activeSession) {
        val track = synchronized(this) { if (accepts(session)) audioTracks[chan] else null }
        track?.pauseForIdle()
    }

    @Synchronized fun preparePlayback(channel: Int, session: PlaybackSession = activeSession): Any? = getTrack(channel, session)?.let {
        it.preparePlayback()
        it.playbackOwner
    }

    /** Park all channels (e.g. on device sleep/screen-off) so AudioTrack does not block in ALSA. */
    fun pauseAll(session: PlaybackSession = activeSession) {
        val tracks = synchronized(this) { if (accepts(session)) audioTracks.values.toList() else emptyList() }
        tracks.forEach { it.pauseForIdle() }
    }

    @Synchronized fun stop(chan: Int, session: PlaybackSession = activeSession) {
        if (!accepts(session)) return
        audioTracks.remove(chan)?.stopPlayback()
    }

    private fun releaseMixer() {
        mixer?.stop()
        mixer = null
    }

    /**
     * Each channel's bank uses [audioLatencyMultiplier], capped by the caller for short prompts.
     * A shared static-focus output is tuned independently rather than inheriting the preference
     * of whichever sink was set up first. Otherwise the guidance cap can silently size music's
     * output too. [session] also fences replacement connections: an old setup must not rebuild
     * or register a sink in a newer connection's mixer.
     */
    @Synchronized fun start(
        channel: Int,
        stream: Int,
        sampleRate: Int,
        numberOfBits: Int,
        numberOfChannels: Int,
        isAac: Boolean = false,
        gain: Float = 1.0f,
        audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
        audioQueueCapacity: Int = 0,
        staticAudioFocus: Boolean = false,
        attachHwDspEqualizer: Boolean = false,
        preferAAudio: Boolean = false,
        isAdts: Boolean = false,
        session: PlaybackSession = activeSession
    ) {
        if (!accepts(session)) return
        // This decoder is reused across connections. Retiring owners must keep their session's
        // closed lease instead of resolving mutable callbacks belonging to the next connection.
        val callbacks = session.callbacks
        val owner = PlaybackOwner()
        val retireOwner = { owner.retire { callbacks.retired(channel, owner) } }
        var sinkMixer: AudioMixer? = null
        var createdSharedMixer = false
        var candidate: AudioTrackWrapper? = null
        try {
            require(numberOfBits == 16) { "The advertised audio sinks require PCM16" }
            if (staticAudioFocus && mixer?.isRunning() == false) {
                audioTracks.values.forEach { it.stopPlayback() }
                audioTracks.clear()
                releaseMixer()
            }
            if (staticAudioFocus && mixer == null) {
                mixer = AudioMixer(stream, attachHwDspEqualizer, preferAAudio = preferAAudio,
                    keepOutputActive = true, duckMediaForSpeech = true, canRender = callbacks.canRender)
                    .also { it.start() }
                createdSharedMixer = true
                AppLog.i("AudioDecoder: Created shared AudioMixer on channel $channel")
            }
            // Independent instances preserve per-stream routing when static focus is off.
            sinkMixer = if (staticAudioFocus) checkNotNull(mixer) else {
                AudioMixer(stream, attachHwDspEqualizer, audioLatencyMultiplier, preferAAudio,
                    canRender = callbacks.canRender,
                    onRecoveryActivity = { callbacks.activity(channel, owner) }).also { it.start() }
            }
            val track = AudioTrackWrapper(
                sampleRateInHz = sampleRate,
                bitDepth = numberOfBits,
                channelCount = numberOfChannels,
                isAac = isAac,
                gain = gain,
                audioLatencyMultiplier = audioLatencyMultiplier,
                audioQueueCapacity = audioQueueCapacity,
                mixer = sinkMixer,
                channelId = channel,
                // Music waits longer than a prompt before starting short. See AdaptivePcmBuffer.
                isMediaSink = channel == com.andrerinas.openheadunit.aap.protocol.Channel.ID_AUD,
                ownsMixer = !staticAudioFocus,
                onPcmActivity = { callbacks.activity(channel, owner) },
                playbackOwner = owner,
                onOwnerRetired = retireOwner,
                isAdts = isAdts
            ).also { candidate = it }
            if (!track.isPlaybackHealthy() || !owner.publishIfActive {
                callbacks.registered(channel, owner)
                audioTracks.put(channel, track)?.stopPlayback()
            }) {
                track.stopPlayback()
                if (!staticAudioFocus) sinkMixer.stop()
                else if (createdSharedMixer) releaseMixer()
                return
            }
            candidate = null
        } catch (e: Exception) {
            candidate?.stopPlayback()
            if (!staticAudioFocus) sinkMixer?.stop()
            else if (createdSharedMixer) releaseMixer()
            AppLog.e("AudioDecoder: cannot construct channel $channel", e)
        }
    }

    fun setGain(channel: Int, gain: Float, session: PlaybackSession = activeSession) {
        val track = synchronized(this) { if (accepts(session)) audioTracks[channel] else null }
        track?.setGain(gain)
    }

    companion object {
        const val SAMPLE_RATE_HZ_48 = 48000
        const val SAMPLE_RATE_HZ_16 = 16000
    }
}
