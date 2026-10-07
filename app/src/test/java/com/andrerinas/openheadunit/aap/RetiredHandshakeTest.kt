package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any

class RetiredHandshakeTest {
    private fun handshake(connection: ProjectionConnection): AapTransport {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        val method = AapTransport::class.java.getDeclaredMethod("handshake", ProjectionConnection::class.java)
            .apply { isAccessible = true }
        assertEquals(false, method.invoke(transport, connection))
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
        `when`(connection.isConnected).thenReturn(true, false)
        `when`(connection.sendBlocking(any(), anyInt(), anyInt())).thenReturn(10)
        assertEquals(AapTransport.HandshakeFailure.OTHER, handshake(connection).lastHandshakeFailure)
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
}
