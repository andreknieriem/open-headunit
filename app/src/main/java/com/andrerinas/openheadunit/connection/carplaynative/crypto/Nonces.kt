package com.andrerinas.openheadunit.connection.carplaynative.crypto

/**
 * The 12-byte ChaCha20-Poly1305 nonces of AirPlay.
 * Ported from xcertplay (https://github.com/shilapi/xcertplay).
 */
object Nonces {
    const val SIZE = 12
    private const val LABEL_SIZE = 8

    /** Four zero bytes, then the counter as eight little-endian bytes. */
    fun counter(counter: Long): ByteArray = ByteArray(SIZE).also { fillCounter(it, counter) }

    /** Writes the counter into the last eight bytes of [nonce], as [counter] does. */
    fun fillCounter(nonce: ByteArray, counter: Long) {
        var value = counter
        for (index in 4 until SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
    }

    /** Four zero bytes, then up to eight ASCII bytes of [label]. */
    fun label(label: String): ByteArray {
        val ascii = label.toByteArray(Charsets.US_ASCII)
        val nonce = ByteArray(SIZE)
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))
        return nonce
    }
}
