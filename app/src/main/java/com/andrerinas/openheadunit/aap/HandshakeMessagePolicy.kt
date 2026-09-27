package com.andrerinas.openheadunit.aap

/**
 * A slow peer answers every version request it was sent, an abandoned handshake's included, and
 * the extra answers land in the TLS phase. The SSLEngine fails on them, so the reader skips them.
 */
object HandshakeMessagePolicy {
    /** [header] is the 6-byte AAP header: channel, flags, length (2), type (2). */
    fun isLateVersionResponse(header: ByteArray): Boolean =
        header.size >= 6 &&
            header[0] == 0.toByte() &&
            header[4] == 0.toByte() &&
            header[5] == 2.toByte()
}
