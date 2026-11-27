package com.andrerinas.openheadunit.connection.carplaynative

/** Converts between the DER form Java gives and the raw `r||s` form the phone expects. */
internal object EcdsaSignature {

    /** [size] is the byte length of one value: 32 for P-256. */
    fun derToRaw(der: ByteArray, size: Int = 32): ByteArray {
        val seq = Der.header(der, 0)?.takeIf { it.tag == Der.SEQUENCE && it.length >= 0 }
            ?: throw IllegalArgumentException("not a DER signature")
        val r = integer(der, seq.contentStart)
        val s = integer(der, r.end)
        val out = ByteArray(size * 2)
        copy(der, r, out, 0, size)
        copy(der, s, out, size, size)
        return out
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.isNotEmpty() && raw.size % 2 == 0) { "raw signature has an odd size" }
        val half = raw.size / 2
        val body = encodeInteger(raw.copyOfRange(0, half)) + encodeInteger(raw.copyOfRange(half, raw.size))
        // Two P-256 values need at most 70 bytes, so the short length form is enough.
        require(body.size < 0x80) { "signature too long" }
        return byteArrayOf(Der.SEQUENCE.toByte(), body.size.toByte()) + body
    }

    private fun integer(der: ByteArray, pos: Int): Der.Header =
        Der.header(der, pos)?.takeIf { it.tag == Der.INTEGER && it.length > 0 }
            ?: throw IllegalArgumentException("not a DER signature")

    private fun copy(der: ByteArray, h: Der.Header, out: ByteArray, offset: Int, size: Int) {
        var start = h.contentStart
        while (start < h.end && der[start] == 0.toByte()) start++
        val length = h.end - start
        require(length <= size) { "signature value is longer than $size bytes" }
        System.arraycopy(der, start, out, offset + size - length, length)
    }

    private fun encodeInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start++
        val trimmed = value.copyOfRange(start, value.size)
        val body = if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(Der.INTEGER.toByte(), body.size.toByte()) + body
    }
}
