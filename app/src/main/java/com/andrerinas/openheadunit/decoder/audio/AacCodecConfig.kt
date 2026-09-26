package com.andrerinas.openheadunit.decoder.audio

/** Owned, immutable CSD shared by queued access units so shedding audio cannot lose configuration. */
internal class AacCodecConfig private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()
    fun sameAs(other: AacCodecConfig): Boolean = bytes.contentEquals(other.bytes)

    companion object {
        private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun default(sampleRate: Int, channels: Int): AacCodecConfig {
            val rateIndex = rates.indexOf(sampleRate)
            require(rateIndex >= 0 && channels in 1..2)
            val bits = (2 shl 11) or (rateIndex shl 7) or (channels shl 3)
            return AacCodecConfig(byteArrayOf((bits shr 8).toByte(), bits.toByte()))
        }

        /** Accept the advertised AAC-LC format, without allowing CSD to change the PCM sink. */
        fun parse(data: ByteArray, offset: Int, size: Int, sampleRate: Int, channels: Int): AacCodecConfig? {
            if (offset < 0 || size !in 2..64 || offset > data.size - size) return null
            val bits = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            val rateIndex = (bits shr 7) and 15
            // 1024-frame AAC-LC, no core-coder dependency or GASpecificConfig extension.
            if (bits shr 11 != 2 || rates.getOrNull(rateIndex) != sampleRate ||
                (bits shr 3) and 15 != channels || bits and 7 != 0) return null
            return AacCodecConfig(data.copyOfRange(offset, offset + size))
        }
    }
}
