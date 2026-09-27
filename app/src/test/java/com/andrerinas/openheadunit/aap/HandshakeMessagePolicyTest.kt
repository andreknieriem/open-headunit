package com.andrerinas.openheadunit.aap

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
}
