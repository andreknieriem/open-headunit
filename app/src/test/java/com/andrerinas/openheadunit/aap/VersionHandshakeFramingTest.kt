package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.LibusbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.AbstractUsbProjectionConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any

/** Exercise the transport entry point, including the handoff to TLS, not just the byte parser. */
class VersionHandshakeFramingTest {
    private lateinit var log: org.mockito.MockedStatic<android.util.Log>
    @org.junit.Before fun stubLog() {
        log = mockStatic(android.util.Log::class.java)
        log.`when`<String> { android.util.Log.getStackTraceString(any<Throwable>()) }.thenReturn("test exception")
    }
    @org.junit.After fun restoreLog() { log.close() }
    private val response = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
    private val tlsFrame = byteArrayOf(0, 3, 0, 3, 0, 3, 22)

    @Test fun `misaligned old USB tail is discarded before request without accessory recovery`() = mockStatic(SystemClock::class.java).use {
        for (chunkSize in listOf(1, 2, 7)) {
            val stale = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x55, 0x55, 0x55)
            val stream = stale + response + tlsFrame
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
            `when`(connection.isConnected).thenReturn(true)
            var sent = false
            var position = 0
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer { sent = true; 10 }
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                if (!sent && position == stale.size) 0 else {
                    val available = (if (!sent) stale.size else stream.size) - position
                    val count = minOf(call.getArgument<Int>(1), available, chunkSize)
                    stream.copyInto(call.getArgument<ByteArray>(0), 0, position, position + count)
                    position += count
                    count
                }
            }
            val ssl = mock(AapSsl::class.java)
            run(transport(ssl), connection)
            verify(ssl).performHandshake(connection)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
            assertEquals(stale.size + response.size, position)
        }
    }

    @Test fun `USB pre-request idle gap preserves every stale frame split`() = mockStatic(SystemClock::class.java).use {
        for (flags in listOf(9, 13, 15)) {
            val extra = if (AapMessageFraming.carriesTotalLength(flags)) byteArrayOf(-1, -1, -1, -1) else byteArrayOf()
            val stale = byteArrayOf(5, flags.toByte(), 0, 4) + extra + byteArrayOf(0, 3, 0, 8)
            for (split in 1 until stale.size) {
                val stream = stale + response + tlsFrame
                val connection = mock(ProjectionConnection::class.java)
                `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
                `when`(connection.isConnected).thenReturn(true)
                var sent = false
                var position = 0
                `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer { sent = true; 10 }
                `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                    if (!sent && position == split) 0 else {
                        val count = minOf(call.getArgument<Int>(1), if (!sent) split - position else stream.size - position)
                        stream.copyInto(call.getArgument<ByteArray>(0), 0, position, position + count)
                        position += count
                        count
                    }
                }
                val ssl = mock(AapSsl::class.java)
                run(transport(ssl), connection)
                verify(ssl).performHandshake(connection)
                verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
                assertEquals(stale.size + response.size, position)
            }
        }
    }

    @Test fun `continuous stale traffic cannot hold the pre-request drain indefinitely`() = mockStatic(SystemClock::class.java).use { clock ->
        var now = 0L
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
        `when`(connection.isConnected).thenReturn(true)
        var sent = false
        var position = 0
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
            assertEquals(500L, now)
            sent = true
            10
        }
        `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
            val out = call.getArgument<ByteArray>(0)
            if (!sent) {
                byteArrayOf(5, 15, 0, 0).copyInto(out)
                now += call.getArgument<Int>(2)
                4
            } else {
                val count = call.getArgument<Int>(1)
                response.copyInto(out, 0, position, position + count)
                position += count
                count
            }
        }
        val ssl = mock(AapSsl::class.java)
        run(transport(ssl), connection)
        verify(ssl).performHandshake(connection)
        Unit
    }

    private fun transport(ssl: AapSsl, profile: GalProfile = GalProfile.COMPATIBILITY): AapTransport {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        field("ssl", ssl)
        field("selectedGalProfile", profile)
        field("laboratoryOptions", GalLaboratoryOptions())
        field("displaySettings", GalDisplaySettings(false, false, false))
        field("aapVideo", mock(AapVideo::class.java))
        field("presentationController", GalPresentationController())
        return transport
    }

    private fun run(transport: AapTransport, connection: ProjectionConnection) {
        val method = AapTransport::class.java.getDeclaredMethod("handshake", ProjectionConnection::class.java)
            .apply { isAccessible = true }
        // A mock TLS engine returns false after we prove it was reached (or not reached).
        assertEquals(false, method.invoke(transport, connection))
    }

    @Test fun `each selected profile is requested directly and higher replies never promote it`() =
        mockStatic(SystemClock::class.java).use {
            for (profile in GalProfile.entries) {
                val peer = response.copyOf().also { it[7] = 6; it[9] = 1 }
                val ssl = mock(AapSsl::class.java)
                val transport = transport(ssl, profile)
                val connection = versionConnection(peer) { request ->
                    assertArrayEquals(com.andrerinas.openheadunit.aap.protocol.messages.Messages
                        .versionRequest(profile.version.major, profile.version.minor), request)
                }
                run(transport, connection)
                verify(ssl).performHandshake(connection)
                verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
                assertEquals(profile, transport.galContract!!.negotiation.profile)
                assertEquals(GalVersion(6, 1), transport.negotiatedVersion!!.version)
            }
        }

    @Test fun `reply below the directly selected version is rejected before TLS without fallback`() =
        mockStatic(SystemClock::class.java).use {
            for (profile in GalProfile.entries) {
                val lower = if (profile.version.minor == 0) GalVersion(profile.version.major - 1, 9)
                    else GalVersion(profile.version.major, profile.version.minor - 1)
                val peer = response.copyOf().also {
                    it[7] = lower.major.toByte(); it[9] = lower.minor.toByte()
                }
                val ssl = mock(AapSsl::class.java)
                val transport = transport(ssl, profile)
                val connection = versionConnection(peer) {}
                run(transport, connection)
                verifyNoInteractions(ssl)
                verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
                assertNull(transport.galContract)
                assertNull(transport.negotiatedVersion)
            }
        }

    private fun versionConnection(peer: ByteArray, request: (ByteArray) -> Unit): ProjectionConnection {
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.isConnected).thenReturn(true)
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
            val size = it.getArgument<Int>(1)
            request(it.getArgument<ByteArray>(0).copyOf(size))
            size
        }
        var position = 0
        `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
            val count = minOf(it.getArgument<Int>(1), peer.size - position)
            peer.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
            position += count
            count
        }
        return connection
    }

    @Test fun `split response survives a stalled read on one request and leaves coalesced TLS bytes for SSL`() = mockStatic(SystemClock::class.java).use { clock ->
        for (split in 1 until response.size) {
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isSingleMessage).thenReturn(true)
            `when`(connection.isConnected).thenReturn(true)
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
            val stream = response + tlsFrame
            var position = 0
            var timedOut = false
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                assertFalse(it.getArgument<Boolean>(3))
                if (position == split && !timedOut) {
                    timedOut = true
                    now += 2000
                    0
                } else {
                    val count = minOf(it.getArgument<Int>(1),
                        if (position < split) split - position else stream.size - position)
                    stream.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                    position += count
                    count
                }
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            verify(ssl).performHandshake(connection)
            assertEquals(response.size, position)
            assertEquals(7, transport.negotiatedVersion!!.minor)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
        }
    }

    @Test fun `compatible nonzero version statuses reach TLS with their status preserved`() = mockStatic(SystemClock::class.java).use {
        for (type in ProjectionConnection.Type.entries) {
            for (status in listOf(1, -8, 255)) {
                val frame = response.copyOf().also {
                    it[10] = (status shr 8).toByte()
                    it[11] = status.toByte()
                }
                val connection = mock(ProjectionConnection::class.java)
                `when`(connection.type).thenReturn(type)
                `when`(connection.isConnected).thenReturn(true)
                var sent = false
                var position = 0
                `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer { sent = true; 10 }
                `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                    if (!sent) 0 else {
                        val count = minOf(it.getArgument<Int>(1), frame.size - position)
                        frame.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                        position += count
                        count
                    }
                }
                val ssl = mock(AapSsl::class.java)
                val transport = transport(ssl)
                run(transport, connection)
                verify(ssl).performHandshake(connection)
                assertEquals(status, transport.negotiatedVersion!!.status)
            }
        }
    }

    @Test fun `rejected truncated and malformed responses never reach TLS`() = mockStatic(SystemClock::class.java).use {
        val invalid = listOf(
            response.copyOf().also { it[10] = -1; it[11] = -1 },
            byteArrayOf(0, 3, 0, 2, 0, 2),
            byteArrayOf(0, 3, 0, 2, -1, -1),
            byteArrayOf(0, 3, 0, 2, 0, 15),
            response.copyOf().also { it[1] = 7 }
        )
        for (frame in invalid) {
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isSingleMessage).thenReturn(true)
            `when`(connection.isConnected).thenReturn(true)
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
            var position = 0
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                val count = minOf(it.getArgument<Int>(1), frame.size - position)
                frame.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                position += count
                count
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            verifyNoInteractions(ssl)
            assertNull(transport.negotiatedVersion)
            assertEquals(if (frame[1] != 3.toByte()) AapTransport.HandshakeFailure.TRANSPORT_ERROR
                else AapTransport.HandshakeFailure.OTHER, transport.lastHandshakeFailure)
        }
    }

    @Test fun `partial request write fails without sending a second header`() = mockStatic(SystemClock::class.java).use {
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.isSingleMessage).thenReturn(true)
        `when`(connection.isConnected).thenReturn(true)
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(5)
        val ssl = mock(AapSsl::class.java)
        val transport = transport(ssl)
        run(transport, connection)
        assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR, transport.lastHandshakeFailure)
        verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
        verify(connection, never()).recvBlocking(any(), anyInt(), anyInt(), anyBoolean())
        verifyNoInteractions(ssl)
    }

    @Test fun `USB stale encrypted traffic after the drain is skipped before version and TLS`() = mockStatic(SystemClock::class.java).use {
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
        `when`(connection.isConnected).thenReturn(true)
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
        val stale = byteArrayOf(5, 9, 0, 3, -1, -1, -1, -1, 1, 2, 3) +
            byteArrayOf(5, 8, 0, 1, 4) + byteArrayOf(5, 10, 0, 0) + byteArrayOf(5, 11, 0, 1, 5)
        val stream = stale + response + tlsFrame
        var position = 0
        var draining = true
        `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
            if (draining) {
                draining = false
                assertEquals(100, call.getArgument<Int>(2))
                0 // Old traffic arrives only after the initial drain went quiet.
            } else {
                assertTrue(call.getArgument<Int>(2) >= 100)
                call.getArgument<ByteArray>(0)[0] = stream[position++]
                1
            }
        }
        val ssl = mock(AapSsl::class.java)
        run(transport(ssl), connection)
        verify(ssl).performHandshake(connection)
        assertEquals(stale.size + response.size, position)
    }

    @Test fun `negative or short writes never replay on unknown endpoints or WiFi`() = mockStatic(SystemClock::class.java).use {
        for (type in ProjectionConnection.Type.entries) for (result in listOf(-7, -1, 0, 5)) {
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.type).thenReturn(type)
            `when`(connection.isConnected).thenReturn(true)
            // A subsequent call could succeed, but the first error doesn't prove zero delivery.
            // In particular, #1128 logs libusb's count while still returning a negative timeout.
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(result, 10)
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
            verifyNoInteractions(ssl)
            assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR, transport.lastHandshakeFailure)
        }
    }

    @Test fun `USB negative version write retries only when the actual endpoint fits the whole request`() = mockStatic(SystemClock::class.java).use {
        for (libusb in listOf(false, true)) for (packetSize in listOf(0, 8, 16, 64, 512, 1024)) {
            for (result in listOf(-7, -1, 0, 5)) {
                val connection: AbstractUsbProjectionConnection = if (libusb)
                    mock(LibusbProjectionConnection::class.java) else mock(StandardUsbProjectionConnection::class.java)
                `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
                `when`(connection.isConnected).thenReturn(true)
                `when`(connection.outMaxPacketSize).thenReturn(packetSize)
                var sends = 0
                var position = 0
                `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer { if (++sends == 1) result else 10 }
                `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                    if (sends == 0) 0 else {
                        val count = call.getArgument<Int>(1)
                        response.copyInto(call.getArgument<ByteArray>(0), 0, position, position + count)
                        position += count
                        count
                    }
                }
                val ssl = mock(AapSsl::class.java)
                val transport = transport(ssl)
                run(transport, connection)
                if (packetSize >= 10 && result < 0) {
                    assertEquals(2, sends)
                    verify(ssl).performHandshake(connection)
                } else {
                    assertEquals(1, sends)
                    verifyNoInteractions(ssl)
                    assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR, transport.lastHandshakeFailure)
                }
            }
        }
    }

    @Test fun `USB write retries stop on attempts deadline or disconnect`() = mockStatic(SystemClock::class.java).use { clock ->
        for (mode in listOf("attempts", "deadline", "disconnect")) {
            var now = 0L
            var alive = true
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val connection = mock(LibusbProjectionConnection::class.java)
            `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
            `when`(connection.isConnected).thenAnswer { alive }
            `when`(connection.outMaxPacketSize).thenReturn(64)
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
                if (mode == "deadline") now = 9999
                if (mode == "disconnect") alive = false
                -7
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            run(transport, connection)
            verify(connection, times(if (mode == "attempts") 3 else 1)).sendBlocking(any(), anyInt(), anyInt())
            verifyNoInteractions(ssl)
            assertEquals(AapTransport.HandshakeFailure.TRANSPORT_ERROR, transport.lastHandshakeFailure)
        }
    }

    @Test fun `fully delivered USB request with an error can retry and its duplicate response is skipped in TLS`() =
        mockStatic(SystemClock::class.java).use {
            val connection = mock(LibusbProjectionConnection::class.java)
            `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
            `when`(connection.isConnected).thenReturn(true)
            `when`(connection.outMaxPacketSize).thenReturn(64)
            val stream = response + response + tlsFrame + byteArrayOf(99)
            var sends = 0
            var position = 0
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
                // The first complete request reached the phone, despite an error at the host.
                if (++sends == 1) -7 else 10
            }
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                if (sends == 0) 0 else {
                    val count = call.getArgument<Int>(1)
                    stream.copyInto(call.getArgument<ByteArray>(0), 0, position, position + count)
                    position += count
                    count
                }
            }
            val engine = mock(javax.net.ssl.SSLEngine::class.java)
            val session = mock(javax.net.ssl.SSLSession::class.java)
            val context = mock(javax.net.ssl.SSLContext::class.java)
            `when`(context.createSSLEngine(anyString(), anyInt())).thenReturn(engine)
            `when`(engine.session).thenReturn(session)
            `when`(session.packetBufferSize).thenReturn(64)
            `when`(session.applicationBufferSize).thenReturn(64)
            var finished = false
            `when`(engine.handshakeStatus).thenAnswer {
                if (finished) javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                else javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP
            }
            `when`(engine.unwrap(any<java.nio.ByteBuffer>(), any<java.nio.ByteBuffer>())).thenAnswer { call ->
                val data = call.getArgument<java.nio.ByteBuffer>(0)
                assertEquals(1, data.remaining())
                assertEquals(22, data.get().toInt())
                finished = true
                javax.net.ssl.SSLEngineResult(javax.net.ssl.SSLEngineResult.Status.OK,
                    javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED, 1, 0)
            }
            val ssl = mock(AapSsl::class.java)
            `when`(ssl.performHandshake(connection)).thenAnswer {
                assertTrue(AapSslContext(context).performHandshake(connection))
                false // Stop the fixture before AuthComplete; the real TLS reader was exercised.
            }
            run(transport(ssl), connection)
            assertTrue(finished)
            assertEquals(2, sends)
            assertEquals(stream.size - 1, position)
            verify(engine, times(1)).unwrap(any<java.nio.ByteBuffer>(), any<java.nio.ByteBuffer>())
            Unit
        }
}
