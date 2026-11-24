package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import android.content.Context
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.LibusbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.AbstractUsbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.usb.UsbRecoveryDevice
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
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
    @org.junit.After fun restoreLog() {
        log.close()
    }
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

    @Test fun `USB cannot negotiate from a response that started before the request`(): Unit =
        mockStatic(SystemClock::class.java).use {
            val current = reply(GalVersion(6, 1))
            for (stale in listOf(reply(GalVersion(6, 2)), reply(GalVersion(1, 5), status = -1))) {
                for (split in 1 until stale.size) {
                    val stream = stale + current + tlsFrame
                    val connection = mock(ProjectionConnection::class.java)
                    `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
                    `when`(connection.isConnected).thenReturn(true)
                    var sent = false
                    var position = 0
                    `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
                        sent = true
                        it.getArgument<Int>(1)
                    }
                    `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                        val count = minOf(it.getArgument<Int>(1), (if (sent) stream.size else split) - position)
                        stream.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                        position += count
                        count
                    }
                    val ssl = mock(AapSsl::class.java)
                    val transport = transport(ssl, compatibility = false)
                    run(transport, connection)
                    verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
                    verify(ssl).performHandshake(connection)
                    assertEquals(GalVersion(6, 1), transport.negotiatedVersion!!.version)
                    assertEquals(stale.size + current.size, position)
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

    /** Compatibility mode by default: these framing cases answer one 1.2 request with 1.7. */
    private fun transport(ssl: AapSsl, compatibility: Boolean = true,
                          owner: AaPresentationSession = AaPresentationSession()): AapTransport {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        fun field(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        field("ssl", ssl)
        field("galCompatibilityMode", compatibility)
        field("lifecycleLock", Any())
        field("displaySettings", GalDisplaySettings(false, false, false))
        field("presentationSession", owner)
        field("aapVideo", mock(AapVideo::class.java))
        field("presentationController", GalPresentationController())
        return transport
    }

    private fun run(transport: AapTransport, connection: ProjectionConnection) {
        val method = AapTransport::class.java.getDeclaredMethod("handshake",
            ProjectionConnection::class.java, UsbRecoveryDevice::class.java)
            .apply { isAccessible = true }
        // A mock TLS engine returns false after we prove it was reached (or not reached).
        assertEquals(false, method.invoke(transport, connection, null))
    }

    private fun reply(version: GalVersion, status: Int = 0) = response.copyOf().also {
        it[6] = (version.major shr 8).toByte(); it[7] = version.major.toByte()
        it[8] = (version.minor shr 8).toByte(); it[9] = version.minor.toByte()
        it[10] = (status shr 8).toByte(); it[11] = status.toByte()
    }

    private fun request(version: GalVersion) =
        com.andrerinas.openheadunit.aap.protocol.messages.Messages.versionRequest(version.major, version.minor)

    @Test fun `compatibility mode requests 1 dot 2 once and a higher reply never promotes it`() =
        mockStatic(SystemClock::class.java).use {
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            val connection = versionConnection(reply(GalVersion(6, 1))) { assertArrayEquals(request(GalVersion(1, 2)), it) }
            run(transport, connection)
            verify(ssl).performHandshake(connection)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
            assertEquals(GalProfile.COMPATIBILITY, transport.galContract!!.negotiation.profile)
            assertEquals(GalVersion(6, 1), transport.negotiatedVersion!!.version)
        }

    @Test fun `compatibility reply below 1 dot 2 is rejected before TLS`() =
        mockStatic(SystemClock::class.java).use {
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl)
            val connection = versionConnection(reply(GalVersion(1, 1))) {}
            run(transport, connection)
            verifyNoInteractions(ssl)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
            assertNull(transport.galContract)
        }

    // The phone keeps its last request, so a reply below 6.1 must be followed by the profile
    // the session will actually use; a 6.1-or-higher reply keeps the opening request.
    @Test fun `opening 6 dot 1 request binds the profile its reply covers before TLS`() =
        mockStatic(SystemClock::class.java).use {
            val cases = listOf(
                GalVersion(6, 2) to GalProfile.GAL_6_1,
                GalVersion(6, 1) to GalProfile.GAL_6_1,
                GalVersion(6, 0) to GalProfile.GAL_1_6,
                GalVersion(5, 0) to GalProfile.GAL_1_6,
                GalVersion(1, 7) to GalProfile.GAL_1_6,
                GalVersion(1, 5) to GalProfile.COMPATIBILITY
            )
            for ((opening, expected) in cases) {
                val ssl = mock(AapSsl::class.java)
                val transport = transport(ssl, compatibility = false)
                val requests = mutableListOf<ByteArray>()
                val final = reply(if (expected == GalProfile.GAL_1_6) GalVersion(1, 7) else opening)
                val connection = versionConnection(reply(opening) + final) { requests.add(it) }
                run(transport, connection)
                verify(ssl).performHandshake(connection)
                val contract = transport.galContract!!
                assertEquals(expected, contract.negotiation.profile)
                assertEquals(expected == GalProfile.GAL_6_1,
                    contract.media.optionsFor(com.andrerinas.openheadunit.aap.protocol.Channel.ID_VID).standalone)
                val sent = requests.map { it.toList() }
                if (expected == GalProfile.GAL_6_1) assertEquals(listOf(request(GalVersion(6, 1)).toList()), sent)
                else assertEquals(listOf(request(GalVersion(6, 1)), request(expected.version)).map { it.toList() }, sent)
            }
        }

    @Test fun `final confirmation below its request cannot enter TLS`() =
        mockStatic(SystemClock::class.java).use {
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl, compatibility = false)
            val connection = versionConnection(reply(GalVersion(6, 0)) + reply(GalVersion(1, 5))) {}
            run(transport, connection)
            verifyNoInteractions(ssl)
            assertNull(transport.galContract)
            verify(connection, times(2)).sendBlocking(any(), anyInt(), anyInt())
            Unit
        }

    @Test fun `GMS refusal retries only the same TCP listener peer once and not a different phone`(): Unit =
        mockStatic(SystemClock::class.java).use {
            val originalPeer = tcpPeer("192.0.2.2", 53001)!!
            val nextPeer = tcpPeer("192.0.2.2", 53002)
            val otherPeer = tcpPeer("192.0.2.3", 53001)
            assertEquals(originalPeer, nextPeer) // Inbound peers reconnect from another ephemeral port.
            assertNotEquals(originalPeer, otherPeer)
            assertNull(tcpPeer("127.0.0.1", 53001))

            val modern = request(GalVersion(6, 1))
            val legacy = request(GalVersion(1, 2))
            val ssl = mock(AapSsl::class.java)
            val first = transport(ssl, compatibility = false)
            run(first, versionConnection(reply(GalVersion(1, 5), status = -1), originalPeer) { assertArrayEquals(modern, it) })
            verifyNoInteractions(ssl)
            assertNull(first.galContract)

            for (unrelatedPeer in listOf(otherPeer, null)) {
                val otherSsl = mock(AapSsl::class.java)
                val other = versionConnection(reply(GalVersion(6, 2)), unrelatedPeer) { assertArrayEquals(modern, it) }
                run(transport(otherSsl, compatibility = false), other)
                verify(otherSsl).performHandshake(other)
            }

            val nextSsl = mock(AapSsl::class.java)
            val next = transport(nextSsl, compatibility = false)
            val connection = versionConnection(reply(GalVersion(1, 5)), nextPeer) { assertArrayEquals(legacy, it) }
            run(next, connection)
            verify(nextSsl).performHandshake(connection)
            assertEquals(GalProfile.COMPATIBILITY, next.galContract!!.negotiation.profile)

            val followingSsl = mock(AapSsl::class.java)
            val following = versionConnection(reply(GalVersion(6, 2)), nextPeer) { assertArrayEquals(modern, it) }
            run(transport(followingSsl, compatibility = false), following)
            verify(followingSsl).performHandshake(following)
        }

    @Test fun `TCP silence consumes one opening and does not authorize a legacy retry`(): Unit =
        mockStatic(SystemClock::class.java).use { clock ->
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val peer = tcpPeer("192.0.2.20", 53000)!!
            val modern = request(GalVersion(6, 1))
            val silent = versionConnection(byteArrayOf(), peer) { assertArrayEquals(modern, it) }
            `when`(silent.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                now += it.getArgument<Int>(2)
                0
            }
            val ssl = mock(AapSsl::class.java)
            val failed = transport(ssl, compatibility = false)
            run(failed, silent)
            verify(silent, times(1)).sendBlocking(any(), anyInt(), anyInt())
            verifyNoInteractions(ssl)
            assertEquals(AapTransport.HandshakeFailure.PEER_SILENT, failed.lastHandshakeFailure)
            assertEquals(10_000L, now)

            val slowWrite = versionConnection(byteArrayOf(), peer) {
                assertArrayEquals(modern, it)
                now += 10_001L
            }
            val writeOverrun = transport(ssl, compatibility = false)
            run(writeOverrun, slowWrite)
            verify(slowWrite, never()).recvBlocking(any(), anyInt(), anyInt(), anyBoolean())
            assertEquals(AapTransport.HandshakeFailure.OTHER, writeOverrun.lastHandshakeFailure)
            verifyNoInteractions(ssl)

            val resumed = versionConnection(reply(GalVersion(6, 2)), peer) { assertArrayEquals(modern, it) }
            run(transport(ssl, compatibility = false), resumed)
            verify(ssl).performHandshake(resumed)
        }

    @Test fun `TCP response completed after the opening deadline cannot start TLS or authorize a retry`(): Unit =
        mockStatic(SystemClock::class.java).use { clock ->
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val peer = tcpPeer("192.0.2.21", 53000)!!
            val modern = request(GalVersion(6, 1))
            val ssl = mock(AapSsl::class.java)
            for (response in listOf(reply(GalVersion(6, 2)), reply(GalVersion(1, 5), status = -1))) {
                val connection = versionConnection(response, peer) { assertArrayEquals(modern, it) }
                var position = 0
                `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                    val count = it.getArgument<Int>(1)
                    response.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                    position += count
                    if (position == response.size) now += it.getArgument<Int>(2) + 1L
                    count
                }
                val failed = transport(ssl, compatibility = false)
                run(failed, connection)
                verifyNoInteractions(ssl)
                assertNull(failed.galContract)
                assertEquals(AapTransport.HandshakeFailure.OTHER, failed.lastHandshakeFailure)
                verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
            }
            val next = versionConnection(reply(GalVersion(6, 2)), peer) { assertArrayEquals(modern, it) }
            run(transport(ssl, compatibility = false), next)
            verify(ssl).performHandshake(next)
        }

    @Test fun `final exchange has a full deadline even after a slow opening reply`() =
        mockStatic(SystemClock::class.java).use { clock ->
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isConnected).thenReturn(true)
            val requests = mutableListOf<ByteArray>()
            var peer = byteArrayOf()
            var position = 0
            var readyAt = 0L
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
                val size = it.getArgument<Int>(1)
                requests.add(it.getArgument<ByteArray>(0).copyOf(size))
                val opening = requests.size == 1
                readyAt = now + if (opening) 9_950 else 9_500
                peer = reply(if (opening) GalVersion(6, 0) else GalVersion(1, 7))
                position = 0
                size
            }
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                now = minOf(readyAt, now + it.getArgument<Int>(2))
                if (now < readyAt) return@thenAnswer 0
                val count = minOf(it.getArgument<Int>(1), peer.size - position)
                peer.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                position += count
                count
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl, compatibility = false)
            run(transport, connection)
            verify(ssl).performHandshake(connection)
            assertEquals(19_450L, now)
            assertEquals(listOf(request(GalVersion(6, 1)).toList(), request(GalVersion(1, 6)).toList()),
                requests.map { it.toList() })
            assertEquals(GalProfile.GAL_1_6, transport.galContract!!.negotiation.profile)
        }

    @Test fun `lost USB opening request can confirm identical lower replies but one reply is not proof`() =
        mockStatic(SystemClock::class.java).use { clock ->
            for (confirmed in listOf(false, true)) {
                var now = 0L
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
                val connection = mock(ProjectionConnection::class.java)
                `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
                `when`(connection.isConnected).thenReturn(true)
                val requests = mutableListOf<ByteArray>()
                var position = response.size
                `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer {
                    val size = it.getArgument<Int>(1)
                    requests.add(it.getArgument<ByteArray>(0).copyOf(size))
                    // The first request was lost. Both subsequent versions elicit 1.7,
                    // unless the additional confirmation write goes unanswered.
                    if (requests.size == 2 || requests.size == 3 || (requests.size == 4 && confirmed)) position = 0
                    size
                }
                `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                    if (position == response.size) {
                        now += it.getArgument<Int>(2)
                        -1
                    } else {
                        val count = minOf(it.getArgument<Int>(1), response.size - position)
                        response.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                        position += count
                        count
                    }
                }
                val ssl = mock(AapSsl::class.java)
                val transport = transport(ssl, compatibility = false)
                run(transport, connection)
                val opening = request(GalVersion(6, 1)).toList()
                val final = request(GalVersion(1, 6)).toList()
                assertEquals(listOf(opening, opening, final, final), requests.map { it.toList() })
                if (confirmed) {
                    verify(ssl).performHandshake(connection)
                    assertEquals(GalProfile.GAL_1_6, transport.galContract!!.negotiation.profile)
                } else {
                    verifyNoInteractions(ssl)
                    assertNull(transport.galContract)
                }
            }
        }

    @Test fun `retirement after the opening response prevents the final request and TLS`() =
        mockStatic(SystemClock::class.java).use {
            val stream = reply(GalVersion(6, 0)) + reply(GalVersion(1, 7))
            var connected = true
            var position = 0
            var writes = 0
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.isConnected).thenAnswer { connected }
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer { writes++; 10 }
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                val count = minOf(call.getArgument<Int>(1), stream.size - position)
                stream.copyInto(call.getArgument<ByteArray>(0), 0, position, position + count)
                position += count
                if (position == response.size) connected = false
                count
            }
            val ssl = mock(AapSsl::class.java)
            val transport = transport(ssl, compatibility = false)
            run(transport, connection)
            verifyNoInteractions(ssl)
            assertNull(transport.galContract)
            assertEquals(1, writes)
        }

    @Test fun `TLS completing on a retired owner cannot send AuthComplete`() = mockStatic(SystemClock::class.java).use {
        val owner = AaPresentationSession()
        val ssl = mock(AapSsl::class.java)
        val connection = versionConnection(response) {}
        `when`(ssl.performHandshake(connection)).thenAnswer { owner.retire(); true }
        run(transport(ssl, owner = owner), connection)
        verify(ssl).performHandshake(connection)
        verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
        Unit
    }

    @Test fun `identical link local addresses on different interfaces do not share refusal hints`() {
        val bytes = InetAddress.getByName("fe80::42").address
        val first = tcpPeer(Inet6Address.getByAddress(null, bytes, 3), 53000)!!
        val other = tcpPeer(Inet6Address.getByAddress(null, bytes, 4), 53001)!!
        val policy = GalVersionPolicy()
        policy.noteRefusal(first, 0)
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, other, 1))
        assertEquals(GalVersionPolicy.Opening.PEER_REFUSAL, policy.opening(false, first, 2))
    }

    @Test fun `closed TCP socket is not a live handshake connection`() {
        val socket = mock(Socket::class.java)
        `when`(socket.inetAddress).thenReturn(InetAddress.getByName("192.0.2.22"))
        val connection = SocketProjectionConnection(socket, mock(Context::class.java))
        // Socket keeps isConnected=true after close if it was ever connected.
        `when`(socket.isConnected).thenReturn(true)
        assertTrue(connection.isConnected)
        `when`(socket.isClosed).thenReturn(true)
        assertFalse(connection.isConnected)
    }

    private fun tcpPeer(remote: String, port: Int) = tcpPeer(InetAddress.getByName(remote), port)

    private fun tcpPeer(remote: InetAddress, port: Int): GalVersionPolicy.Peer.Tcp? {
        val socket = mock(Socket::class.java)
        `when`(socket.inetAddress).thenReturn(remote)
        `when`(socket.localAddress).thenReturn(InetAddress.getByName("192.0.2.1"))
        `when`(socket.port).thenReturn(port)
        `when`(socket.localPort).thenReturn(5277)
        return SocketProjectionConnection(socket, mock(Context::class.java)).galVersionPeer()
    }

    private fun versionConnection(peer: ByteArray, tcpPeer: GalVersionPolicy.Peer.Tcp? = null,
                                  request: (ByteArray) -> Unit): ProjectionConnection {
        val connection: ProjectionConnection = if (tcpPeer == null) mock(ProjectionConnection::class.java)
            else mock(SocketProjectionConnection::class.java).also { `when`(it.galVersionPeer()).thenReturn(tcpPeer) }
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
        for (compatibility in listOf(true, false)) for (frame in invalid) {
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
            val transport = transport(ssl, compatibility = compatibility)
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
