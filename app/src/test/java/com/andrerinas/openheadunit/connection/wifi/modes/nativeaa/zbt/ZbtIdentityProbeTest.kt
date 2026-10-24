package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
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
        try {
            ZbtIdentityProbe.probe(keepTrying = { false }, socketFactory = {
                error("cancelled probe created a socket")
            })
            fail("expected cancellation")
        } catch (_: ZbtChannelOpening.Cancelled) { }
    }
    @Test fun `occupied observer propagates busy without creating or misidentifying a socket`() {
        ZbtDaemonReachability.forget()
        val old = ZbtDaemonReachability.observerOpening({ true }, diagnostic = true)!!
        try {
            assertThrows(ZbtChannelOpening.ObserverBusy::class.java) {
                ZbtIdentityProbe.probe(socketFactory = { error("second observer socket") })
            }
            assertTrue(old.wasCancelled())
            assertNull(ZbtDaemonReachability.cached(100L, requireIdentity = true))
        } finally {
            old.close()
            ZbtDaemonReachability.releaseObserver(old)
            ZbtDaemonReachability.forget()
        }
    }

    private fun measuredSocket(bytes: ByteArray, preempt: Boolean, denyRead: Boolean = false): Socket =
        object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {}
            override fun setSoTimeout(timeout: Int) {}
            override fun close() {}
            override fun getInputStream(): InputStream {
                if (denyRead) {
                    if (preempt) ZbtDaemonReachability.claimCarrier().close()
                    throw SecurityException("vendor access revoked")
                }
                return object : ByteArrayInputStream(bytes) {
                    override fun read(target: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(target, offset, length)
                        // The bytes have already arrived, but parsing has not classified them.
                        // Releasing the carrier immediately must not erase this preemption.
                        if (available() == 0 && preempt) ZbtDaemonReachability.claimCarrier().close()
                        return count
                    }
                }
            }
        }

    @Test fun `every identity outcome preserves preemption even after carrier release`() {
        val frames = listOf(
            ByteArray(ZbtFraming.HEADER_SIZE),
            ZbtFraming.encodeHeader(4097, ZbtMessages.INIT_INFO),
            ByteArray(0).let { empty -> (1..16).fold(empty) { all, _ ->
                all + ZbtFraming.encodeFrame(empty, ZbtMessages.HFP_LINK_INFO)
            } },
            ZbtFraming.encodeFrame(greeting(), ZbtMessages.INIT_INFO),
        )
        for (bytes in frames) {
            ZbtDaemonReachability.forget()
            assertThrows(ZbtChannelOpening.Cancelled::class.java) {
                ZbtIdentityProbe.probe(socketFactory = { measuredSocket(bytes, true) })
            }
            assertFalse(ZbtDaemonReachability.resolve(nowMs = { 100L }, requireIdentity = true,
                identityDial = { ZbtIdentityProbe.probe(socketFactory = { measuredSocket(bytes, true) }) }))
            assertNull(ZbtDaemonReachability.cached(100L, requireIdentity = true))
        }
    }

    @Test fun `access refusal after preemption is not cached as identity failure`() {
        ZbtDaemonReachability.forget()
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { 100L }, requireIdentity = true,
            identityDial = { ZbtIdentityProbe.probe(socketFactory = {
                measuredSocket(byteArrayOf(), true, denyRead = true)
            }) }))
        assertNull(ZbtDaemonReachability.cached(100L, requireIdentity = true))
    }

    @Test fun `ordinary malformed result still settles the identity decision`() {
        ZbtDaemonReachability.forget()
        try {
            assertFalse(ZbtDaemonReachability.resolve(nowMs = { 100L }, requireIdentity = true,
                identityDial = { ZbtIdentityProbe.probe(socketFactory = {
                    measuredSocket(ByteArray(ZbtFraming.HEADER_SIZE), false)
                }) }))
            assertEquals(false, ZbtDaemonReachability.cached(100L, requireIdentity = true))
        } finally { ZbtDaemonReachability.forget() }
    }

}
