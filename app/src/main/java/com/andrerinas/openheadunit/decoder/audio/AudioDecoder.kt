package com.andrerinas.openheadunit.decoder.audio

import android.util.SparseArray
import com.andrerinas.openheadunit.utils.AppLog

class AudioDecoder {

    private val audioTracks = SparseArray<AudioTrackWrapper>(3)
    private var mixer: AudioMixer? = null

    fun getTrack(channel: Int): AudioTrackWrapper? {
        return audioTracks.get(channel)
    }

    /** The codec the live sink on [channel] was built for, or null when there is no sink. */
    fun sinkCodecFor(channel: Int): Boolean? = audioTracks.get(channel)?.builtAsAac()

    fun getMixer(): AudioMixer? {
        return mixer
    }

    fun hasMixer(): Boolean {
        return mixer != null && mixer!!.isRunning()
    }

    fun decode(channel: Int, buffer: ByteArray, offset: Int, size: Int) {
        val audioTrack = audioTracks.get(channel)
        audioTrack?.write(buffer, offset, size)
    }

    fun stop() {
        for (i in 0 until audioTracks.size()) {
            stop(audioTracks.keyAt(i))
        }
        releaseMixer()
    }

    /** Park a channel the phone stopped, keeping its track. See [AudioTrackWrapper.pauseForIdle]. */
    fun pause(chan: Int) {
        audioTracks.get(chan)?.pauseForIdle()
    }

    /** Park all channels (e.g. on device sleep/screen-off) so AudioTrack does not block in ALSA. */
    fun pauseAll() {
        for (i in 0 until audioTracks.size()) {
            audioTracks.valueAt(i)?.pauseForIdle()
        }
    }

    fun stop(chan: Int) {
        val audioTrack = audioTracks.get(chan)
        audioTrack?.stopPlayback()
        audioTracks.put(chan, null)
    }

    fun releaseMixer() {
        synchronized(this) {
            mixer?.stop()
            mixer = null
        }
    }

    /**
     * @param audioLatencyMultiplier this channel's, capped for the prompt channels.
     * @param mixerLatencyMultiplier the user's setting, uncapped. The shared mixer track is sized
     *   from it rather than from whichever channel happened to build the mixer, which was always
     *   the first Media Sink Setup to arrive and so usually a capped one.
     */
    fun start(channel: Int, stream: Int, sampleRate: Int, numberOfBits: Int, numberOfChannels: Int, isAac: Boolean = false, gain: Float = 1.0f, audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER, audioQueueCapacity: Int = 0, staticAudioFocus: Boolean = false, attachHwDspEqualizer: Boolean = false, mixerLatencyMultiplier: Int = audioLatencyMultiplier, preferAAudio: Boolean = false) {
        if (staticAudioFocus) {
            synchronized(this) {
                if (mixer == null) {
                    mixer = AudioMixer(stream, attachHwDspEqualizer, mixerLatencyMultiplier, preferAAudio, keepOutputActive = true)
                    mixer!!.start()
                    AppLog.i(
                        "AudioDecoder: Created and started shared AudioMixer on channel $channel " +
                            "at latencyMultiplier=$mixerLatencyMultiplier"
                    )
                }
            }
        }
        // All PCM16 sinks use the same network bank and 10ms renderer. Independent instances
        // preserve per-stream routing when static focus is off. PCM8 retains its legacy path.
        val sinkMixer = if (staticAudioFocus) mixer else if (numberOfBits == 16) {
            AudioMixer(stream, attachHwDspEqualizer, audioLatencyMultiplier, preferAAudio).also { it.start() }
        } else null
        val thread = AudioTrackWrapper(
            stream = stream,
            sampleRateInHz = sampleRate,
            bitDepth = numberOfBits,
            channelCount = numberOfChannels,
            isAac = isAac,
            gain = gain,
            audioLatencyMultiplier = audioLatencyMultiplier,
            audioQueueCapacity = audioQueueCapacity,
            mixer = sinkMixer,
            channelId = channel,
            attachHwDspEqualizer = attachHwDspEqualizer,
            // Music waits longer than a prompt before starting short. See AudioPrerollPolicy.
            isMediaSink = channel == com.andrerinas.openheadunit.aap.protocol.Channel.ID_AUD,
            ownsMixer = !staticAudioFocus && sinkMixer != null
        )
        audioTracks.put(channel, thread)
    }

    fun setGain(channel: Int, gain: Float) {
        audioTracks.get(channel)?.setGain(gain)
    }

    companion object {
        const val SAMPLE_RATE_HZ_48 = 48000
        const val SAMPLE_RATE_HZ_16 = 16000
    }
}
