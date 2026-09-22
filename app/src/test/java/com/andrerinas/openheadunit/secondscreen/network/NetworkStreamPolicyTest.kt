package com.andrerinas.openheadunit.secondscreen.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStreamPolicyTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `VLC and browsers are told apart from raw readers`() {
        val get = bytes("GET / HTTP/1.1\r\n")
        assertTrue(NetworkStreamPolicy.isHttpRequest(get, get.size))
        val head = bytes("HEAD / HTTP/1.1\r\n")
        assertTrue(NetworkStreamPolicy.isHttpRequest(head, head.size))
        assertFalse(NetworkStreamPolicy.isHttpRequest(ByteArray(0), 0))
        assertFalse(NetworkStreamPolicy.isHttpRequest(ByteArray(512), -1))
        val post = bytes("POST / HTTP/1.1")
        assertFalse(NetworkStreamPolicy.isHttpRequest(post, post.size))
    }

    @Test
    fun `a privileged or impossible port falls back to the default`() {
        assertEquals(5000, NetworkStreamPolicy.portOrDefault(80))
        assertEquals(5000, NetworkStreamPolicy.portOrDefault(70000))
        assertEquals(8554, NetworkStreamPolicy.portOrDefault(8554))
    }

    @Test
    fun `the receiver command names the host and port`() {
        assertEquals(
            "ffplay -f h264 -fflags nobuffer -flags low_delay -framedrop tcp://192.168.1.20:5000",
            NetworkStreamPolicy.ffplayCommand("192.168.1.20", 5000),
        )
    }
}
