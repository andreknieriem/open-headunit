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

    /**
     * A USB read returns -1 for a timeout and for an error alike, so there a -1 that took half its
     * timeout or more is a timeout. A socket read returns 0 on a timeout, so its -1 is an error.
     */
    fun isReadError(ret: Int, elapsedMs: Long, timeoutMs: Int, timeoutLooksLikeError: Boolean): Boolean =
        ret < 0 && (!timeoutLooksLikeError || elapsedMs * 2 < timeoutMs)

    /** Names why the version exchange ended without a response; a transport error wins. */
    fun versionFailure(peerSentBytes: Boolean, transportError: Boolean): AapTransport.HandshakeFailure =
        when {
            transportError -> AapTransport.HandshakeFailure.TRANSPORT_ERROR
            !peerSentBytes -> AapTransport.HandshakeFailure.PEER_SILENT
            else -> AapTransport.HandshakeFailure.OTHER
        }
}
