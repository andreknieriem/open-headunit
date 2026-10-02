package com.andrerinas.openheadunit.aap.protocol.proto
object Control { enum class ByeByeReason { USER_SELECTION }; object AudioFocusRequestNotification { object AudioFocusRequestType {
    const val GAIN_VALUE=1; const val GAIN_TRANSIENT_VALUE=2
    const val GAIN_TRANSIENT_MAY_DUCK_VALUE=3; const val RELEASE_VALUE=4
} } }
object Media {
    object MsgType { const val MEDIA_MESSAGE_DATA_VALUE=0; const val MEDIA_MESSAGE_CODEC_CONFIG_VALUE=1 }
    object MediaCodecType {
        const val MEDIA_CODEC_AUDIO_PCM_VALUE=1; const val MEDIA_CODEC_AUDIO_AAC_LC_VALUE=2
        const val MEDIA_CODEC_AUDIO_AAC_LC_ADTS_VALUE=4
    }
}
