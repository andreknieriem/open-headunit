package com.andrerinas.openheadunit.decoder.audio

/** Streaming conversion to mixer PCM16 stereo. Retains interpolation phase across packets so
 * packet boundaries cannot duplicate samples or lose fractional frames. At unequal rates the
 * interpolator adds at most one source frame of delay. Storage is reused by its channel owner. */
internal class PcmConverter(private val outputRate: Int = 48000) {
    var samples = ShortArray(0)
        private set
    private var format: PcmInputFormat? = null
    private var phase = 0
    private var previousLeft = 0
    private var previousRight = 0
    private var hasPrevious = false

    /** A codec discontinuity must not interpolate from the retired codec's last sample. */
    fun reset() {
        format = null
        phase = 0
        hasPrevious = false
    }

    fun convert(data: ByteArray, offset: Int, length: Int, input: PcmInputFormat): Int {
        require(input.supported && offset >= 0 && length >= 0 && offset <= data.size - length)
        require(length % input.bytesPerFrame == 0) { "Incomplete PCM frame" }
        if (format != input) {
            format = input
            phase = 0
            hasPrevious = false
        }
        val frames = length / input.bytesPerFrame
        val outputFrames = ((phase.toLong() + frames.toLong() * outputRate) / input.sampleRate).toInt()
        val count = outputFrames * 2
        if (samples.size < count) samples = ShortArray(maxOf(count, 12288))
        if (input.sampleRate == outputRate && input.channels == 2 && input.encoding == PcmInputFormat.PCM16) {
            Pcm16Stereo.decode(data, offset, frames, samples)
            return count
        }
        var written = 0
        val sampleBytes = input.bytesPerFrame / input.channels
        for (frame in 0 until frames) {
            val index = offset + frame * input.bytesPerFrame
            val left = sample(data, index, input.encoding)
            val right = if (input.channels == 1) left else sample(data, index + sampleBytes, input.encoding)
            if (!hasPrevious) {
                previousLeft = left
                previousRight = right
                hasPrevious = true
            }
            phase += outputRate
            while (phase >= input.sampleRate) {
                phase -= input.sampleRate
                // A remainder of zero lands on the current source sample exactly.
                samples[written++] = (left + (previousLeft - left).toLong() * phase / outputRate).toShort()
                samples[written++] = (right + (previousRight - right).toLong() * phase / outputRate).toShort()
            }
            previousLeft = left
            previousRight = right
        }
        return written
    }

    private fun sample(data: ByteArray, index: Int, encoding: Int): Int {
        if (encoding == PcmInputFormat.PCM16) return (data[index].toInt() and 0xff) or
            (data[index + 1].toInt() shl 8)
        val bits = (data[index].toInt() and 0xff) or ((data[index + 1].toInt() and 0xff) shl 8) or
            ((data[index + 2].toInt() and 0xff) shl 16) or (data[index + 3].toInt() shl 24)
        val value = java.lang.Float.intBitsToFloat(bits)
        if (value.isNaN()) return 0
        return (value.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
    }
}
