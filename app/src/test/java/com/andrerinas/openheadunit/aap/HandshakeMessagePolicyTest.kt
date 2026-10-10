package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeMessagePolicyTest {

    private fun header(channel: Int, type: Int) = byteArrayOf(
        channel.toByte(), 3, 0, 8, ((type shr 8) and 0xFF).toByte(), (type and 0xFF).toByte()
    )

    @Test
    fun `a version response on the control channel is a late answer`() {
        assertTrue(HandshakeMessagePolicy.isLateVersionResponse(header(0, 2)))
    }

    @Test
    fun `a TLS record is passed to the engine`() {
        assertFalse(HandshakeMessagePolicy.isLateVersionResponse(header(0, 3)))
    }

    @Test
    fun `type 2 on another channel is not a version response`() {
        assertFalse(HandshakeMessagePolicy.isLateVersionResponse(header(1, 2)))
    }

    @Test
    fun `a high type byte is not mistaken for type 2`() {
        assertFalse(HandshakeMessagePolicy.isLateVersionResponse(header(0, 0x0102)))
    }

    @Test
    fun `a short header is never a version response`() {
        assertFalse(HandshakeMessagePolicy.isLateVersionResponse(byteArrayOf(0, 3, 0, 8, 0)))
    }

    @Test
    fun `a send that failed every time is a transport error`() {
        assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR,
            HandshakeMessagePolicy.versionFailure(peerSentBytes = false, transportError = true))
    }

    @Test
    fun `a peer that sent nothing over a working link is silent`() {
        assertEquals(AapTransport.HandshakeFailure.PEER_SILENT,
            HandshakeMessagePolicy.versionFailure(peerSentBytes = false, transportError = false))
    }

    @Test
    fun `a transport error wins over bytes the peer sent`() {
        assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR,
            HandshakeMessagePolicy.versionFailure(peerSentBytes = true, transportError = true))
    }

    @Test
    fun `bytes with no version response and no error is other`() {
        assertEquals(AapTransport.HandshakeFailure.OTHER,
            HandshakeMessagePolicy.versionFailure(peerSentBytes = true, transportError = false))
    }

    @Test
    fun `a read that fails at once is a transport error`() {
        assertTrue(HandshakeMessagePolicy.isReadError(ret = -1, elapsedMs = 3, timeoutMs = 2000,
            timeoutLooksLikeError = true))
    }

    @Test
    fun `a read that fails at its timeout is a timeout, not an error`() {
        assertFalse(HandshakeMessagePolicy.isReadError(ret = -1, elapsedMs = 2001, timeoutMs = 2000,
            timeoutLooksLikeError = true))
    }

    @Test
    fun `a failed read past half its timeout counts as a timeout`() {
        assertFalse(HandshakeMessagePolicy.isReadError(ret = -1, elapsedMs = 1000, timeoutMs = 2000,
            timeoutLooksLikeError = true))
    }

    @Test
    fun `a read that returns bytes or zero is never an error`() {
        assertFalse(HandshakeMessagePolicy.isReadError(ret = 0, elapsedMs = 1, timeoutMs = 2000,
            timeoutLooksLikeError = true))
        assertFalse(HandshakeMessagePolicy.isReadError(ret = 12, elapsedMs = 1, timeoutMs = 2000,
            timeoutLooksLikeError = true))
    }

    @Test
    fun `a socket read that fails late is still a transport error`() {
        assertTrue(HandshakeMessagePolicy.isReadError(ret = -1, elapsedMs = 1200, timeoutMs = 2000,
            timeoutLooksLikeError = false))
        assertTrue(HandshakeMessagePolicy.isReadError(ret = -1, elapsedMs = 9000, timeoutMs = 10000,
            timeoutLooksLikeError = false))
    }

    @Test
    fun `a socket read that returns bytes or zero is never an error`() {
        assertFalse(HandshakeMessagePolicy.isReadError(ret = 0, elapsedMs = 2000, timeoutMs = 2000,
            timeoutLooksLikeError = false))
        assertFalse(HandshakeMessagePolicy.isReadError(ret = 12, elapsedMs = 1, timeoutMs = 2000,
            timeoutLooksLikeError = false))
    }
}
