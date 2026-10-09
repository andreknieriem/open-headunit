package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException

class TlsWriteDiagnosticTest {
    @Test fun actualTransportReportsShortFailedAndThrowingWritesOnce() {
        val original = AppLog.LOGGER
        val lines = mutableListOf<String>()
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { lines.add(msg) }
        }
        try {
            for (result in listOf(3, -1, 8, null)) {
                lines.clear()
                val connection = mock(ProjectionConnection::class.java)
                val bytes = ByteArray(8)
                if (result == null) `when`(connection.sendBlocking(bytes, 8, 250))
                    .thenAnswer { throw IOException("link gone") }
                else `when`(connection.sendBlocking(bytes, 8, 250)).thenReturn(result)
                val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
                AapTransport::class.java.getDeclaredField("connection").apply { isAccessible = true }
                    .set(transport, connection)
                val write = AapTransport::class.java.getDeclaredMethod("writeTlsFrame",
                    ByteArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                assertEquals(result ?: -1, write.invoke(transport, bytes, 8))
                if (result == 8) assertTrue(lines.isEmpty())
                else {
                    assertEquals(1, lines.size)
                    assertTrue(lines.single().contains("AapTransport: send incomplete (ret=${result ?: -1} of 8)"))
                    if (result == null) assertTrue(lines.single().contains("IOException: link gone"))
                }
            }
        } finally { AppLog.LOGGER = original }
    }
}
