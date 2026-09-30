package com.andrerinas.openheadunit.utils
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
class Settings {
    var staticAudioFocus=false
    var separateAudioStreams=false
    var mediaAudioStream=3
    var guidanceAudioStream=0
    var systemAudioStream=5
    var mediaVolumeOffset=0
    var guidanceVolumeOffset=0
    var systemVolumeOffset=0
    var audioLatencyMultiplier=2
    var audioQueueCapacity=0
    var useAacAudio=false
    var useAAudioOutput=false
    var enableAudioSink=true
    var attachHwDspEqualizer=false
    var playbackFocusMode=PlaybackFocusPolicy.Mode.ALWAYS
    var playbackFocusSelfDefeating=false
}
