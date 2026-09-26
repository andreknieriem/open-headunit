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
            val tail = Bits(data, offset, size, 16)
            if (!tail.onlyZeroes()) {
                // Backward-compatible ASC may begin with LC but enable SBR/PS later.
                // Keep explicit "not present" signaling; never strip unknown extensions
                // and accidentally feed a different output format into the negotiated sink.
                if (tail.read(11) != 0x2b7 || tail.read(5) != 5 || tail.read(1) != 0) return null
                if (!tail.onlyZeroes()) {
                    if (tail.read(11) != 0x548 || tail.read(1) != 0 || !tail.onlyZeroes()) return null
                }
            }
            return AacCodecConfig(data.copyOfRange(offset, offset + size))
        }
    }

    /** Bounded to this CSD, not the unused tail of the reusable transport buffer. */
    private class Bits(private val data: ByteArray, private val offset: Int,
                       size: Int, private var position: Int) {
        private val limit = size * 8
        fun read(count: Int): Int {
            if (count > limit - position) return -1
            var value = 0
            repeat(count) {
                value = (value shl 1) or bit(position++)
            }
            return value
        }
        fun onlyZeroes(): Boolean {
            for (index in position until limit) if (bit(index) != 0) return false
            return true
        }
        private fun bit(index: Int) = (data[offset + index / 8].toInt() ushr (7 - index % 8)) and 1
    }
}
