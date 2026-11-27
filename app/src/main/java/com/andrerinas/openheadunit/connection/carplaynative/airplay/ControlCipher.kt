package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces

/**
 * ChaCha20-Poly1305 records on the control channel: a 2-byte little-endian length that is also the
 * AEAD data, the ciphertext, a 16-byte tag. Each direction has its own key and counter.
 * Follows xcertplay airplay/ControlCipher.kt (https://github.com/shilapi/xcertplay) and LIVI (https://github.com/f-io/LIVI) control_cipher.rs.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    /** The plain bytes of the whole records, and the start of a partial record that is left. */
    class Decrypted(val data: ByteArray, val rest: ByteArray)

    private var readCounter = 0L
    private var writeCounter = 0L

    /** Returns null when a record does not authenticate. The caller ends the connection. */
    fun decrypt(buffer: ByteArray): Decrypted? {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = (buffer[offset].toInt() and 0xff) or ((buffer[offset + 1].toInt() and 0xff) shl 8)
            val end = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < end) break
            val header = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val sealed = buffer.copyOfRange(offset + HEADER_SIZE, end)
            val plain = CarPlayCrypto.chachaOpen(readKey, Nonces.counter(readCounter), sealed, header) ?: return null
            out.write(plain)
            readCounter++
            offset = end
        }
        return Decrypted(out.toByteArray(), buffer.copyOfRange(offset, buffer.size))
    }

    /** Splits at 16 KiB. An empty message still makes one record. */
    fun encrypt(plain: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        do {
            val chunk = plain.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plain.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            out.write(header)
            out.write(CarPlayCrypto.chachaSeal(writeKey, Nonces.counter(writeCounter), chunk, header))
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plain.size)
        return out.toByteArray()
    }

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = CarPlayCrypto.TAG_SIZE
        const val MAX_PAYLOAD = 0x4000
    }
}
