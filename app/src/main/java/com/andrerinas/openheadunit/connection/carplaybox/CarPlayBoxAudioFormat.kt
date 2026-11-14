package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.aap.protocol.Channel

/** Box audio decode types as sample rate and channel count. */
object CarPlayBoxAudioFormat {

    data class Format(val sampleRate: Int, val channels: Int)

    fun forDecodeType(decodeType: Int): Format? = when (decodeType) {
        1, 2 -> Format(44100, 2)
        3 -> Format(8000, 1)
        4 -> Format(48000, 2)
        5 -> Format(16000, 1)
        6 -> Format(24000, 1)
        7 -> Format(16000, 2)
        else -> null
    }
}

/** Maps a box audio stream onto the AAP channel ids that `AudioDecoder` keys its tracks on. */
object CarPlayBoxAudioRouting {

    private const val AUDIO_TYPE_MEDIA = 1
    private const val AUDIO_TYPE_NAVIGATION = 2

    /** Null means the stream is not ours to play (the box never sends our own microphone back). */
    fun channelFor(decodeType: Int, audioType: Int): Int? = when {
        audioType == CarPlayBoxMessages.AUDIO_TYPE_MIC -> null
        audioType == AUDIO_TYPE_MEDIA -> Channel.ID_AUD
        audioType == AUDIO_TYPE_NAVIGATION -> Channel.ID_AU1
        // Call audio has no audio type: it arrives as 8 kHz or 16 kHz mono.
        decodeType == 3 || decodeType == 5 -> Channel.ID_AU2
        else -> Channel.ID_AUD
    }
}
