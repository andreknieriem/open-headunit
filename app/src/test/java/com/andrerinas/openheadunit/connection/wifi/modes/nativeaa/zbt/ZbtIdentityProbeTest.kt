package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ZbtIdentityProbeTest {
    private fun string(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray()
        return ZbtMessages.varint((field shl 3) or 2) + ZbtMessages.varint(bytes.size) + bytes
    }

    // Vendor identifiers are opaque strings, not necessarily syntactically valid UUIDs.
    private fun greeting(mac: String = "112233445566") =
        ZbtMessages.varintField(1, ZbtMessages.INIT_INFO) +
            string(2, "dfjjcsdd-sdaa-4305-984b-adas677a1002") + string(3, "AVIN") + string(4, mac)

    @Test
    fun `only a complete identity counts`() {
        assertTrue(ZbtIdentityProbe.isIdentity(ZbtMessages.INIT_INFO, greeting()))
        assertTrue(ZbtIdentityProbe.isIdentity(ZbtMessages.INIT_INFO, greeting("11:22:33:44:55:66")))
        assertFalse(ZbtIdentityProbe.isIdentity(ZbtMessages.LINK_INFO, greeting()))
        assertFalse(ZbtIdentityProbe.isIdentity(ZbtMessages.INIT_INFO, byteArrayOf()))
        assertFalse(ZbtIdentityProbe.isIdentity(ZbtMessages.INIT_INFO, greeting("unknown")))
        assertFalse(ZbtIdentityProbe.isIdentity(ZbtMessages.INIT_INFO, greeting().dropLast(1).toByteArray()))
        assertFalse(ZbtIdentityProbe.isIdentity(ZbtMessages.INIT_INFO, byteArrayOf(8, 1) + greeting().drop(3)))
    }

    private fun withPeer(reply: ByteArray, fragmented: Boolean = false, expected: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 3000
                val peer = executor.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        if (fragmented) {
                            for (b in reply) socket.getOutputStream().write(b.toInt())
                        } else socket.getOutputStream().write(reply)
                        socket.getOutputStream().flush()
                        // Half-close lets the client distinguish an incomplete frame from silence.
                        socket.shutdownOutput()
                        socket.getInputStream().read()
                    }
                }
                assertEquals(if (expected) ZbtIdentityProbe.Outcome.IDENTIFIED else ZbtIdentityProbe.Outcome.UNCONFIRMED,
                    ZbtIdentityProbe.probe(port = server.localPort, budgetMs = 1500))
                // Discovery sends no Init/Reconn/byte-channel data and always closes its socket.
                assertEquals(-1, peer.get(3, TimeUnit.SECONDS).toInt())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `fragmented unsolicited greeting identifies daemon without any writes`() {
        val other = ZbtFraming.encodeFrame(byteArrayOf(), ZbtMessages.HFP_LINK_INFO)
        withPeer(other + ZbtFraming.encodeFrame(greeting(), ZbtMessages.INIT_INFO), true, true)
    }

    @Test
    fun `refuses malformed framing oversized bodies and truncated greeting`() {
        withPeer(ByteArray(16), expected = false)
        withPeer(ZbtFraming.encodeHeader(Int.MAX_VALUE, ZbtMessages.INIT_INFO), expected = false)
        withPeer(ZbtFraming.encodeFrame(greeting(), ZbtMessages.INIT_INFO).dropLast(1).toByteArray(), expected = false)
    }

    @Test
    fun `a listening silent port is not proof of compatibility`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 3000
                val peer = executor.submit<Int> {
                    server.accept().use { it.soTimeout = 3000; it.getInputStream().read() }
                }
                assertEquals(ZbtIdentityProbe.Outcome.UNCONFIRMED, ZbtIdentityProbe.probe(port = server.localPort, budgetMs = 150))
                assertEquals(-1, peer.get(3, TimeUnit.SECONDS).toInt())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelled probe does not connect`() {
        ServerSocket(0).use { server ->
            assertEquals(ZbtIdentityProbe.Outcome.UNCONFIRMED, ZbtIdentityProbe.probe(port = server.localPort, keepTrying = { false }))
        }
    }
}
