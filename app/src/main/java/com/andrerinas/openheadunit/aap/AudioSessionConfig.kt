package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.decoder.audio.AudioTimeCorrectionMode
import com.andrerinas.openheadunit.utils.Settings

/**
 * Settings which need a fresh projection session, not just new output tracks. Codec changes
 * require ServiceDiscovery/Setup again; focus and routing must change together with the mixer.
 * Keep this snapshot until disconnect so saving preferences cannot change half a live session.
 * Time-correction engines retain PCM internally, so switching one also needs this boundary.
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
    val attachHwDspEqualizer: Boolean,
    val matchingMode: AudioTimeCorrectionMode,
    // Decides which sinks service discovery offers, so it belongs to this session's snapshot and
    // not to whatever Settings says at the moment the phone asks. Defaulted so the field is
    // additive for existing construction.
    val mapsAudioOnly: Boolean = false
) {
    companion object {
        fun from(settings: Settings) = AudioSessionConfig(
            settings.enableAudioSink, settings.staticAudioFocus, settings.playbackFocusMode,
            settings.separateAudioStreams, settings.mediaAudioStream, settings.guidanceAudioStream,
            settings.systemAudioStream, settings.useAacAudio, settings.attachHwDspEqualizer, settings.audioMatchingMode,
            settings.mapsAudioOnly
        )
    }
}
