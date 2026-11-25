package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.connection.usb.UsbRecoveryDevice
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any

class RetiredHandshakeTest {
    private fun handshake(connection: ProjectionConnection,
                          owner: AaPresentationSession = AaPresentationSession()): AapTransport {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        AapTransport::class.java.getDeclaredField("displaySettings")
            .apply { isAccessible = true }.set(transport, GalDisplaySettings(false, false, false))
        AapTransport::class.java.getDeclaredField("presentationSession")
            .apply { isAccessible = true }.set(transport, owner)
        val method = AapTransport::class.java.getDeclaredMethod("handshake",
            ProjectionConnection::class.java, UsbRecoveryDevice::class.java)
            .apply { isAccessible = true }
        assertEquals(false, method.invoke(transport, connection, null))
        return transport
    }

    @Test fun `tunnel retired before first request is not a silent phone`() = mockStatic(SystemClock::class.java).use {
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.isSingleMessage).thenReturn(true)
        `when`(connection.isConnected).thenReturn(false)
        assertEquals(AapTransport.HandshakeFailure.OTHER, handshake(connection).lastHandshakeFailure)
        verify(connection, never()).sendBlocking(any(), anyInt(), anyInt())
        Unit
    }

    @Test fun `tunnel retired after a timeout is not a silent phone`() = mockStatic(SystemClock::class.java).use {
        var now = 0L
        it.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now += 100; now }
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.isSingleMessage).thenReturn(true)
        var connected = true
        `when`(connection.isConnected).thenAnswer { connected }
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
        `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
            connected = false
            0
        }
        assertEquals(AapTransport.HandshakeFailure.OTHER, handshake(connection).lastHandshakeFailure)
        verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
        Unit
    }

    @Test fun `live tunnel with timed out requests still reports silence`() = mockStatic(SystemClock::class.java).use {
        var now = 0L
        it.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now += 100; now }
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.isSingleMessage).thenReturn(true)
        `when`(connection.isConnected).thenReturn(true)
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
        assertEquals(AapTransport.HandshakeFailure.PEER_SILENT, handshake(connection).lastHandshakeFailure)
    }

    @Test fun `retired USB owner does not resend while the endpoint is still connected`() =
        mockStatic(SystemClock::class.java).use { clock ->
            var now = 0L
            var sent = false
            val owner = AaPresentationSession()
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
            `when`(connection.isConnected).thenReturn(true)
            `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenAnswer { sent = true; 10 }
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                if (!sent) 0 else {
                    now += it.getArgument<Int>(2)
                    owner.retire()
                    -1
                }
            }
            assertEquals(AapTransport.HandshakeFailure.OTHER, handshake(connection, owner).lastHandshakeFailure)
            verify(connection, times(1)).sendBlocking(any(), anyInt(), anyInt())
            Unit
        }
}
