package com.andrerinas.openheadunit.connection.carplaynative

/** Just enough DER to tell a PKCS#7, a certificate and a PKCS#8 key apart and to read an ECDSA signature. */
internal object Der {

    const val SEQUENCE = 0x30
    const val INTEGER = 0x02
    const val OCTET_STRING = 0x04
    const val OID = 0x06

    /** [length] is -1 for a BER indefinite length, which openssl writes for a PKCS#7. */
    class Header(val tag: Int, val contentStart: Int, val length: Int) {
        val end: Int get() = contentStart + length
    }

    enum class Kind { SIGNED_DATA, X509, PKCS8, ENCRYPTED_PKCS8, UNKNOWN }

    private val SIGNED_DATA_OID = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x02)

    fun header(b: ByteArray, pos: Int): Header? {
        if (pos < 0 || pos + 2 > b.size) return null
        val tag = b[pos].toInt() and 0xff
        val first = b[pos + 1].toInt() and 0xff
        var p = pos + 2
        if (first < 0x80) return if (p + first <= b.size) Header(tag, p, first) else null
        val count = first and 0x7f
        if (count == 0) return Header(tag, p, -1)
        if (count > 3 || p + count > b.size) return null
        var length = 0
        repeat(count) { length = (length shl 8) or (b[p++].toInt() and 0xff) }
        return if (p + length <= b.size) Header(tag, p, length) else null
    }

    fun kind(b: ByteArray): Kind {
        val root = header(b, 0)?.takeIf { it.tag == SEQUENCE } ?: return Kind.UNKNOWN
        val first = header(b, root.contentStart) ?: return Kind.UNKNOWN
        if (first.length < 0) return Kind.UNKNOWN
        return when (first.tag) {
            OID -> if (b.copyOfRange(first.contentStart, first.end).contentEquals(SIGNED_DATA_OID)) Kind.SIGNED_DATA else Kind.UNKNOWN
            INTEGER -> if (header(b, first.end)?.tag == SEQUENCE) Kind.PKCS8 else Kind.UNKNOWN
            SEQUENCE -> if (header(b, first.end)?.tag == OCTET_STRING) Kind.ENCRYPTED_PKCS8 else Kind.X509
            else -> Kind.UNKNOWN
        }
    }
}
