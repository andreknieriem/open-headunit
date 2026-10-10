package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.audio.AudioSinkCodec
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.utils.Settings

/**
 * Settings which need a fresh projection session, not just new output tracks. Codec changes
 * require ServiceDiscovery/Setup again; focus and routing must change together with the mixer.
 * Keep this snapshot until disconnect so saving preferences cannot change half a live session.
 * Local gain, latency and backend changes deliberately remain outside this contract.
 */
internal data class AudioSessionConfig(
    val enabled: Boolean,
    val staticFocus: Boolean,
    val focusMode: PlaybackFocusPolicy.Mode,
    val separateStreams: Boolean,
    val mediaStream: Int,
    val guidanceStream: Int,
    val systemStream: Int,
    val aac: Boolean,
    val pcmGuidance: Boolean,
    val attachHwDspEqualizer: Boolean,
    val prefer48kGuidance: Boolean = false
) {
    /**
     * One codec per sink: MEDIA retains the user's choice (or the wireless band cap), while
     * the opt-in keeps SPEECH and SYSTEM in PCM. Android Auto's sender selects a codec for
     * each advertised stream independently; offering duplicate sinks is unnecessary.
     * PCM removes the guidance codec stage, not the phone's capture batching (64 ms in the
     * examined 16 kHz path). The optional 48 kHz format is negotiated separately.
     * A received Setup still takes precedence over this preference-based fallback.
     */
    fun codecFor(channel: Int, mediaAac: Boolean = aac): AudioSinkCodec =
        if (mediaAac && !(pcmGuidance && (channel == Channel.ID_AU1 || channel == Channel.ID_AU2))) {
            AudioSinkCodec.AAC_LC
        } else {
            AudioSinkCodec.PCM
        }

    /**
     * Indices refer to the ordered AudioConfiguration list in ServiceDiscovery, not sample rates.
     * Keep 16 kHz at index 0 for compatibility. In the examined Android Auto 17.4 sender, SPEECH
     * searches for 48 kHz mono only with GAL > 1.5, otherwise choosing 16 kHz mono. Use the actual
     * version response, never the version we requested. SYSTEM keeps its required 16 kHz format.
     * The extra format is PCM-only; even a peer choosing AAC must retain the index-0 contract.
     */
    fun configurationIndicesFor(channel: Int, supports48k: Boolean, codec: AudioSinkCodec): List<Int> =
        if (channel == Channel.ID_AU1 && pcmGuidance && prefer48kGuidance && supports48k && !codec.isAac) {
            GUIDANCE_CONFIGURATIONS
        } else DEFAULT_CONFIGURATION

    companion object {
        private val DEFAULT_CONFIGURATION = listOf(0)
        private val GUIDANCE_CONFIGURATIONS = listOf(0, 1)

        fun from(settings: Settings) = AudioSessionConfig(
            settings.enableAudioSink, settings.staticAudioFocus, settings.playbackFocusMode,
            settings.separateAudioStreams, settings.mediaAudioStream, settings.guidanceAudioStream,
            settings.systemAudioStream, settings.useAacAudio, settings.usePcmGuidance, settings.attachHwDspEqualizer,
            // Remembering a hidden preference must not renegotiate an unchanged wire format.
            settings.usePcmGuidance && settings.prefer48kGuidance
        )
    }
}
