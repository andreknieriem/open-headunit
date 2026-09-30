package com.andrerinas.openheadunit.decoder.audio

/** Encoded framing is part of the negotiated format, including when both formats use AAC-LC. */
enum class AudioSinkCodec(val isAac: Boolean) {
    PCM(false), AAC_LC(true), AAC_LC_ADTS(true)
}
