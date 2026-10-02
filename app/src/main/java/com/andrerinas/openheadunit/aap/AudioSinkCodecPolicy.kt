package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.audio.AudioSinkCodec

/**
 * An audio sink's codec and framing, read from the type the phone names in its Media
 * Sink Setup rather than from the setting the announcement was built from: the wire is the one
 * source that cannot disagree with what the phone sends. Null for a type that is not an audio codec.
 */
object AudioSinkCodecPolicy {

    fun codecFor(setupType: Int): AudioSinkCodec? = when (setupType) {
        Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE -> AudioSinkCodec.PCM
        Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE -> AudioSinkCodec.AAC_LC
        Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_ADTS_VALUE -> AudioSinkCodec.AAC_LC_ADTS
        else -> null
    }
}
