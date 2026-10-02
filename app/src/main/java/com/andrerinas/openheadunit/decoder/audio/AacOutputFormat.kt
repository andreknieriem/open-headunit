package com.andrerinas.openheadunit.decoder.audio

/** Per-decoder validation; an old codec callback cannot approve a replacement's PCM output. */
internal class AacOutputFormat(private val rate: Int, private val channels: Int,
                               private val allowConversion: Boolean = false) {
    private var rejected = false
    @Volatile var format: PcmInputFormat? = null
        private set
    val acceptsOutput: Boolean get() = format != null

    fun update(outputRate: Int, outputChannels: Int, pcmEncoding: Int?): Boolean {
        // AudioFormat.ENCODING_PCM_16BIT is 2, also on pre-24 codecs that omit the format key.
        val candidate = PcmInputFormat(outputRate, outputChannels, pcmEncoding ?: PcmInputFormat.PCM16)
        if (!candidate.supported || (!allowConversion && candidate != PcmInputFormat(rate, channels))) rejected = true
        format = if (rejected) null else candidate
        return acceptsOutput
    }
}
