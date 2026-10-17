package com.andrerinas.openheadunit.aap.protocol

import android.media.AudioManager
import com.andrerinas.openheadunit.aap.protocol.proto.Media

import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.utils.Settings

object AudioConfigs {
    private val audioTracks: Map<Int, Media.AudioConfiguration>
    private val guidance48k: Media.AudioConfiguration

    /**
     * Which system stream an Android Auto audio channel plays on.
     *
     * With separate streams off every channel shares the media stream — one multimedia output,
     * the behaviour a head unit that only unmutes on a single stream needs. With it on, each of
     * the three channels goes to the stream chosen for it in Audio Stream settings.
     */
    fun stream(
        channel: Int,
        separateAudioStreams: Boolean = true,
        mediaStream: Int = AudioManager.STREAM_MUSIC,
        guidanceStream: Int = AudioManager.STREAM_VOICE_CALL,
        systemStream: Int = AudioManager.STREAM_NOTIFICATION
    ) : Int
    {
        if (!separateAudioStreams) return mediaStream
        return when(channel) {
            Channel.ID_AU1 -> guidanceStream
            Channel.ID_AU2 -> systemStream
            else -> mediaStream
        }
    }

    /** The same mapping, read straight from the user's settings. */
    fun stream(channel: Int, settings: Settings): Int = stream(
        channel,
        settings.separateAudioStreams,
        settings.mediaAudioStream,
        settings.guidanceAudioStream,
        settings.systemAudioStream
    )

    fun get(channel: Int, index: Int = 0): Media.AudioConfiguration {
        require(index == 0 || (channel == Channel.ID_AU1 && index == 1))
        val base = audioTracks.getValue(channel)
        return if (index == 0) base else guidance48k
    }

    init {
        val audioConfig0 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_48
            numberOfBits = 16
            numberOfChannels = 2
        }.build()

        val audioConfig1 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        guidance48k = audioConfig1.toBuilder().setSampleRate(48000).build()

        // 16 kHz mono is required here, not preferred. The phone accepts the SYSTEM sink only if
        // it offers that config, and with the audio sink off this is the only sink left - so
        // anything else empties its endpoint list and the session ends with "No audio/mic".
        val audioConfig2 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        // Index 0 remains the compatibility format; SPEECH may also advertise 48 kHz at index 1.
        audioTracks = mapOf(Channel.ID_AUD to audioConfig0, Channel.ID_AU1 to audioConfig1,
            Channel.ID_AU2 to audioConfig2)
    }
}
