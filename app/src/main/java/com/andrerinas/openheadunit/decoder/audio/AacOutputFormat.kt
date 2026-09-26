package com.andrerinas.openheadunit.decoder.audio

/** Per-decoder validation; an old codec callback cannot approve a replacement's PCM output. */
internal class AacOutputFormat(private val rate: Int, private val channels: Int) {
    private var rejected = false
    @Volatile var acceptsOutput = false
        private set

    fun update(outputRate: Int, outputChannels: Int, pcmEncoding: Int?): Boolean {
        // AudioFormat.ENCODING_PCM_16BIT is 2, also on pre-24 codecs that omit the format key.
        if (outputRate != rate || outputChannels != channels || (pcmEncoding ?: 2) != 2) rejected = true
        acceptsOutput = !rejected
        return acceptsOutput
    }
}
