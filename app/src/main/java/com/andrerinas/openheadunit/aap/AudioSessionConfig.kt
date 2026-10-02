package com.andrerinas.openheadunit.aap

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
    val attachHwDspEqualizer: Boolean
) {
    companion object {
        fun from(settings: Settings) = AudioSessionConfig(
            settings.enableAudioSink, settings.staticAudioFocus, settings.playbackFocusMode,
            settings.separateAudioStreams, settings.mediaAudioStream, settings.guidanceAudioStream,
            settings.systemAudioStream, settings.useAacAudio, settings.attachHwDspEqualizer
        )
    }
}
