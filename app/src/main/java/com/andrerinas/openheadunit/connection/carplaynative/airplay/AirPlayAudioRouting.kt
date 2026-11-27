package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxAudioFormat

/**
 * Where an AirPlay audio stream plays and what its format bits mean. Stage 2 plays every type 100
 * stream on the media channel, whatever its `audioType`, type 101 on the navigation channel, and linear PCM only.
 * Follows xcertplay airplay/AudioStream.kt `AudioStreamCodec` and LIVI (https://github.com/f-io/LIVI) `stack.rs`.
 */
object AirPlayAudioRouting {
    const val STREAM_TYPE_AUDIO = 100
    const val STREAM_TYPE_ALT_AUDIO = 101

    enum class Codec { LPCM, AAC_LC, OPUS }

    class Format(val codec: Codec, val sampleRate: Int, val channels: Int) {
        /** The sink format of a linear PCM stream, null for a codec stage 2 does not play. */
        val sinkFormat: CarPlayBoxAudioFormat.Format?
            get() = if (codec == Codec.LPCM) CarPlayBoxAudioFormat.Format(sampleRate, channels) else null
    }

    private const val AAC_LC_44K_STEREO = 0x40_0000L
    private const val AAC_LC_48K_STEREO = 0x80_0000L
    private const val OPUS_MASK = 0x1000_0000L or 0x2000_0000L or 0x4000_0000L

    private val PCM = mapOf(
        0x4L to (8_000 to 1), 0x8L to (8_000 to 2),
        0x10L to (16_000 to 1), 0x20L to (16_000 to 2),
        0x40L to (24_000 to 1), 0x80L to (24_000 to 2),
        0x100L to (32_000 to 1), 0x200L to (32_000 to 2),
        0x400L to (44_100 to 1), 0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1), 0x8000L to (48_000 to 2),
    )

    /** Null means the stream is not played. A type 100 stream goes to the media channel, whatever its `audioType`. */
    fun channelFor(streamType: Int): Int? = when (streamType) {
        STREAM_TYPE_AUDIO -> Channel.ID_AUD
        STREAM_TYPE_ALT_AUDIO -> Channel.ID_AU1
        else -> null
    }

    /** A format word that names no known bit falls back to 44.1 kHz stereo PCM, as both references do. */
    fun formatFor(bits: Long): Format = when {
        bits and OPUS_MASK != 0L -> Format(Codec.OPUS, 48_000, 1)
        bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO) != 0L ->
            Format(Codec.AAC_LC, if (bits and AAC_LC_48K_STEREO != 0L) 48_000 else 44_100, 2)
        else -> PCM[bits]?.let { Format(Codec.LPCM, it.first, it.second) } ?: Format(Codec.LPCM, 44_100, 2)
    }
}
