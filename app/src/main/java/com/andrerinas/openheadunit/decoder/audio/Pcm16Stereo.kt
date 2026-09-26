package com.andrerinas.openheadunit.decoder.audio

/** Copy a transport-owned PCM16 payload into reusable mixer-owned storage. */
internal object Pcm16Stereo {
    fun decode(source: ByteArray, offset: Int, frames: Int, destination: ShortArray) {
        require(offset in 0..source.size && frames >= 0 &&
            frames <= (source.size - offset) / 4 && frames <= destination.size / 2)
        for (i in 0 until frames * 2) {
            val index = offset + i * 2
            destination[i] = ((source[index].toInt() and 0xff) or
                (source[index + 1].toInt() shl 8)).toShort()
        }
    }
}
