package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AudioSessionConfig
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** Builds the audio part of ServiceDiscovery, including the system-only compatibility case. */
internal object AudioSinkServices {
    fun create(config: AudioSessionConfig, mediaAac: Boolean, selfMode: Boolean,
               supports48kGuidance: Boolean = false): List<Control.Service> {
        // SYSTEM stays present even with playback disabled or in Self Mode. The phone requires
        // its 16 kHz mono configuration to keep an audio endpoint in those sessions.
        val services = mutableListOf(sink(Channel.ID_AU2, Media.AudioStreamType.SYSTEM, config, mediaAac))
        if (AudioSinkAnnouncementPolicy.announcesMediaAndSpeech(config.enabled, selfMode)) {
            services.add(sink(Channel.ID_AU1, Media.AudioStreamType.SPEECH, config, mediaAac, supports48kGuidance))
            services.add(sink(Channel.ID_AUD, Media.AudioStreamType.MEDIA, config, mediaAac))
        }
        return services
    }

    private fun sink(
        channel: Int,
        stream: Media.AudioStreamType,
        config: AudioSessionConfig,
        mediaAac: Boolean,
        supports48kGuidance: Boolean = false
    ): Control.Service = Control.Service.newBuilder().apply {
        id = channel
        mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
            // One codec per sink; each configuration index is kept stable through Setup/Start.
            availableType = if (config.codecFor(channel, mediaAac).isAac) {
                Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC
            } else {
                Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            }
            audioType = stream
            config.configurationIndicesFor(channel, supports48kGuidance, config.codecFor(channel, mediaAac))
                .forEach { addAudioConfigs(AudioConfigs.get(channel, it)) }
        }.build()
    }.build()
}
