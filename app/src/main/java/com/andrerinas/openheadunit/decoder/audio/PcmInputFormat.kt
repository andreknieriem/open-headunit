package com.andrerinas.openheadunit.decoder.audio

/** Immutable format captured with each decoded buffer, including across codec format changes. */
internal data class PcmInputFormat(val sampleRate: Int, val channels: Int, val encoding: Int = PCM16) {
    val bytesPerFrame: Int get() = channels * if (encoding == FLOAT) 4 else 2
    val supported: Boolean get() = sampleRate in 8000..192000 && channels in 1..2 &&
        (encoding == PCM16 || encoding == FLOAT)

    companion object {
        const val PCM16 = 2
        const val FLOAT = 4
    }
}
