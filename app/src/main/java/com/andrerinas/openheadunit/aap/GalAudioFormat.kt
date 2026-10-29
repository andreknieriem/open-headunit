package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** Formats supported by both the PCM mixer and AAC-LC AudioSpecificConfig builder. */
internal object GalAudioFormat {
    private val rates = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
    fun supported(format: Media.AudioConfiguration): Boolean = format.numberOfBits == 16 &&
        format.numberOfChannels in 1..2 && format.sampleRate in rates
}
