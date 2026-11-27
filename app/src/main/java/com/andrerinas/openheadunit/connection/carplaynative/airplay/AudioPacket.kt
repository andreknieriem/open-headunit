package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces

/**
 * Opens one audio datagram: a 12-byte RTP header, the sealed payload with its 16-byte tag, then the
 * 8-byte little-endian nonce counter. Bytes 4 to 12 of the header (timestamp and SSRC) are the AEAD data.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-audio, `stream.rs`, and xcertplay airplay/AudioStream.kt.
 */
class AudioPacketOpener(key: ByteArray) {
    sealed class Result {
        /** [sample] is the RTP timestamp, as an unsigned 32-bit value. [payload] is the plain RTP body. */
        class Opened(val sequence: Int, val sample: Long, val payload: ByteArray) : Result()

        object TooShort : Result()

        /** [first] is true once per opener, for one log line. */
        class AuthFailed(val first: Boolean) : Result()
    }

    private val opener = CarPlayCrypto.ChaChaOpener(key)
    private val nonce = ByteArray(Nonces.SIZE)
    private var failureLogged = false

    fun open(packet: ByteArray, length: Int = packet.size): Result {
        if (length < RTP_HEADER_LEN + TAIL_LEN) return Result.TooShort
        val sealedEnd = length - NONCE_LEN
        packet.copyInto(nonce, 4, sealedEnd, length)
        val aad = packet.copyOfRange(4, RTP_HEADER_LEN)
        val sealed = packet.copyOfRange(RTP_HEADER_LEN, sealedEnd)
        val out = ByteArray(sealed.size - CarPlayCrypto.TAG_SIZE)
        val plainLength = opener.open(nonce, sealed, sealed.size, aad, out)
        if (plainLength == null) {
            val first = !failureLogged
            failureLogged = true
            return Result.AuthFailed(first)
        }
        val sequence = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val sample = ((packet[4].toLong() and 0xff) shl 24) or ((packet[5].toLong() and 0xff) shl 16) or
            ((packet[6].toLong() and 0xff) shl 8) or (packet[7].toLong() and 0xff)
        return Result.Opened(sequence, sample, out)
    }

    companion object {
        const val RTP_HEADER_LEN = 12
        private const val NONCE_LEN = 8
        private const val TAIL_LEN = CarPlayCrypto.TAG_SIZE + NONCE_LEN

        /** CarPlay sends 16-bit samples big-endian; the audio track takes little-endian. An odd last byte stays. */
        fun swapS16InPlace(payload: ByteArray): ByteArray {
            var i = 0
            while (i + 1 < payload.size) {
                val tmp = payload[i]
                payload[i] = payload[i + 1]
                payload[i + 1] = tmp
                i += 2
            }
            return payload
        }
    }
}
