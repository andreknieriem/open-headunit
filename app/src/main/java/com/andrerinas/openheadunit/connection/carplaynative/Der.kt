package com.andrerinas.openheadunit.connection.carplaynative

/** Just enough DER to tell a PKCS#7, a certificate and a PKCS#8 key apart, read an ECDSA signature and write a certificate. */
internal object Der {

    const val SEQUENCE = 0x30
    const val INTEGER = 0x02
    const val OCTET_STRING = 0x04
    const val OID = 0x06
    const val BIT_STRING = 0x03
    const val NULL = 0x05
    const val BOOLEAN = 0x01
    const val SET = 0x31

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

    /** Definite-length TLV. A length above 127 uses the shortest long form. */
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        if (content.size < 0x80) out.write(content.size) else {
            var count = 0
            var rest = content.size
            while (rest != 0) { count++; rest = rest ushr 8 }
            out.write(0x80 or count)
            for (i in count - 1 downTo 0) out.write(content.size ushr (8 * i))
        }
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(SEQUENCE, concat(parts))
    fun set(vararg parts: ByteArray): ByteArray = tlv(SET, concat(parts))
    fun explicit(number: Int, content: ByteArray): ByteArray = tlv(0xa0 + number, content)
    fun octetString(value: ByteArray): ByteArray = tlv(OCTET_STRING, value)
    fun boolean(value: Boolean): ByteArray = tlv(BOOLEAN, byteArrayOf(if (value) 0xff.toByte() else 0))
    fun nullValue(): ByteArray = tlv(NULL, ByteArray(0))
    fun integer(value: java.math.BigInteger): ByteArray = tlv(INTEGER, value.toByteArray())
    fun bitString(value: ByteArray, unusedBits: Int = 0): ByteArray = tlv(BIT_STRING, byteArrayOf(unusedBits.toByte()) + value)

    /** An object identifier from its dotted form, such as `2.5.29.19`. */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs[1] >= 0 && (arcs[0] == 2L || arcs[1] < 40)) { "bad object identifier" }
        val out = java.io.ByteArrayOutputStream()
        fun base128(value: Long) {
            val groups = ArrayList<Int>()
            var rest = value
            do { groups.add((rest and 0x7f).toInt()); rest = rest ushr 7 } while (rest != 0L)
            for (i in groups.size - 1 downTo 0) out.write(groups[i] or if (i > 0) 0x80 else 0)
        }
        base128(arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { base128(it) }
        return tlv(OID, out.toByteArray())
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
