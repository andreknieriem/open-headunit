package com.andrerinas.openheadunit.connection.carplaynative.codec

/** RFC 4648 Base64 without line breaks. `java.util.Base64` is API 26 and the github flavor runs on API 16. */
internal object PlainBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** Whitespace must be gone already. Padding is required. */
    fun decode(text: String): ByteArray {
        if (text.length % 4 != 0) throw PlistException("base64: bad length")
        val pad = text.takeLastWhile { it == '=' }.length
        if (pad > 2) throw PlistException("base64: bad padding")
        val out = ByteArray(text.length / 4 * 3 - pad)
        var o = 0
        var i = 0
        while (i < text.length) {
            var acc = 0
            for (k in 0 until 4) {
                val c = text[i + k]
                val v = if (c == '=' && i + k >= text.length - pad) 0 else ALPHABET.indexOf(c)
                if (v < 0) throw PlistException("base64: bad character")
                acc = (acc shl 6) or v
            }
            for (k in 0 until 3) if (o < out.size) out[o++] = (acc ushr (16 - 8 * k)).toByte()
            i += 4
        }
        return out
    }
}
