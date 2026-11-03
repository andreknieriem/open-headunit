package com.andrerinas.openheadunit.aap

import android.os.Handler
import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class ApplicationSendReadinessTest {
    private fun field(transport: AapTransport, name: String, value: Any) {
        AapTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.set(transport, value)
    }

    @Test fun `application admission follows the complete AuthComplete write`() {
        mockStatic(SystemClock::class.java).use {
            for (statusResult in listOf(-1, 0, Messages.statusOk.size - 1, Messages.statusOk.size)) {
                val transport = mock<AapTransport>(defaultAnswer = CALLS_REAL_METHODS)
                val ssl = mock<AapSsl>()
                val connection = mock<ProjectionConnection>()
                val handler = mock<Handler>()
                val writes = mutableListOf<ByteArray>()
                val writer = AapTlsWriter(ssl) { bytes, length -> writes.add(bytes.copyOf(length)); length }
                field(transport, "ssl", ssl)
                field(transport, "tlsWriter", writer)
                field(transport, "sendHandler", handler)
                field(transport, "selectedGalProfile", GalProfile.COMPATIBILITY)
                field(transport, "laboratoryOptions", GalLaboratoryOptions())
                field(transport, "displaySettings", GalDisplaySettings(false, false, false))
                field(transport, "aapVideo", mock<AapVideo>())
                whenever(connection.isSingleMessage).thenReturn(true)
                whenever(connection.isConnected).thenReturn(true)
                // A complete VersionResponse carries major, minor and status after its type.
                // Honor bounded reads so this fixture reaches AuthComplete through the real
                // frame reader, rather than assuming one receive call is one whole message.
                val versionResponse = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
                var responsePosition = 0
                whenever(connection.recvBlocking(any(), any(), any(), any())).thenAnswer {
                    assertFalse(it.getArgument<Boolean>(3))
                    assertTrue("version response already consumed", responsePosition < versionResponse.size)
                    val count = minOf(it.getArgument<Int>(1), versionResponse.size - responsePosition)
                    versionResponse.copyInto(it.getArgument(0), 0, responsePosition, responsePosition + count)
                    responsePosition += count
                    count
                }
                whenever(ssl.performHandshake(connection)).thenReturn(true)
                whenever(ssl.drainControlRecords()).thenReturn(emptyList())
                whenever(ssl.encrypt(any(), any(), any())).thenAnswer {
                    val data = it.getArgument<ByteArray>(2)
                    ByteArrayWithLimit(data.copyOf(), data.size)
                }
                val message = mock<AapMessage>()
                fun assertWaiting() {
                    assertFalse(writer.isReady)
                    transport.send(message)
                    assertEquals(AapTlsWriter.Result.NOT_READY, writer.send(ByteArray(6), 6))
                    assertTrue(writer.flushControl())
                    assertTrue(writes.isEmpty())
                    verifyNoInteractions(handler, message)
                    verify(ssl, never()).encrypt(any(), any(), any())
                }
                doAnswer { assertWaiting(); null }.whenever(ssl).postHandshakeReset()
                var authCompleteWrites = 0
                whenever(connection.sendBlocking(any(), any(), any())).thenAnswer {
                    val bytes = it.getArgument<ByteArray>(0)
                    if (bytes.contentEquals(Messages.statusOk)) {
                        authCompleteWrites++
                        assertWaiting()
                        statusResult
                    }
                    else it.getArgument<Int>(1)
                }
                val handshake = AapTransport::class.java.getDeclaredMethod("handshake", ProjectionConnection::class.java)
                    .apply { isAccessible = true }
                val success = handshake.invoke(transport, connection) as Boolean
                // Failed status writes must reach the admission boundary too; an earlier
                // version/TLS failure would otherwise satisfy their expected false result.
                assertEquals(versionResponse.size, responsePosition)
                assertTrue(transport.negotiatedVersion?.accepted == true)
                verify(ssl).performHandshake(connection)
                verify(ssl).postHandshakeReset()
                assertEquals(1, authCompleteWrites)
                assertEquals(statusResult == Messages.statusOk.size, success)
                assertEquals(success, writer.isReady)
                if (success) {
                    assertEquals(AapTlsWriter.Result.SENT, writer.send(ByteArray(6), 6))
                    assertEquals(1, writes.size)
                } else assertWaiting()
            }
        }
    }

    @Test fun `retirement cannot be undone by a late handshake completion`() {
        val ssl = mock<AapSsl>()
        val writer = AapTlsWriter(ssl) { _, _ -> error("retired writer performed I/O") }
        writer.retire()
        assertFalse(writer.activate())
        assertEquals(AapTlsWriter.Result.NOT_READY, writer.send(ByteArray(6), 6))
        assertTrue(writer.flushControl())
        verifyNoInteractions(ssl)
    }
}
