package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.nio.ByteBuffer
import javax.net.ssl.*
import javax.net.ssl.SSLEngineResult.HandshakeStatus.*
import javax.net.ssl.SSLEngineResult.Status.OK

class AapSslSessionTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    @org.junit.Before fun stubClock() { clock = org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java) }
    @org.junit.After fun restoreClock() { clock.close() }

    @Test fun retiringOldSessionDoesNotClearReplacementControlRecordsOrListener() {
        val context = mock<SSLContext>()
        val engine = mock<SSLEngine>()
        val session = mock<SSLSession>()
        whenever(context.createSSLEngine(any(), any())).thenReturn(engine)
        whenever(engine.session).thenReturn(session)
        whenever(session.packetBufferSize).thenReturn(64)
        whenever(session.applicationBufferSize).thenReturn(64)
        whenever(engine.handshakeStatus).thenReturn(NOT_HANDSHAKING)
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            it.getArgument<ByteBuffer>(0).get()
            SSLEngineResult(OK, NEED_WRAP, 1, 0)
        }
        whenever(engine.wrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            it.getArgument<ByteBuffer>(1).put(42.toByte())
            SSLEngineResult(OK, NOT_HANDSHAKING, 0, 1)
        }
        val factory = AapSslContext(context)
        val old = factory.newSession()
        val current = factory.newSession()
        assertNotSame(old, current)
        assertTrue(current.performHandshake(mock()))
        current.postHandshakeReset()
        var notifications = 0
        current.setControlRecordListener { notifications++ }
        current.decrypt(0, 1, byteArrayOf(1))
        old.release()
        current.decrypt(0, 1, byteArrayOf(1))
        assertEquals(2, notifications)
        assertEquals(2, current.drainControlRecords().size)
        verify(context).createSSLEngine("android-auto", 5277)
    }
    @Test fun emptyApplicationDiagnosticsAreBoundedAndOutsideTheEngineLock() {
        val context = mock<SSLContext>()
        val engine = mock<SSLEngine>()
        val session = mock<SSLSession>()
        whenever(context.createSSLEngine(any(), any())).thenReturn(engine)
        whenever(engine.session).thenReturn(session)
        whenever(session.packetBufferSize).thenReturn(64)
        whenever(session.applicationBufferSize).thenReturn(64)
        whenever(engine.handshakeStatus).thenReturn(NOT_HANDSHAKING)
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            it.getArgument<ByteBuffer>(0).get()
            SSLEngineResult(OK, NOT_HANDSHAKING, 1, 0)
        }
        val ssl = AapSslContext(context)
        assertTrue(ssl.performHandshake(mock()))
        ssl.postHandshakeReset()
        val lines = mutableListOf<String>()
        val original = com.andrerinas.openheadunit.utils.AppLog.LOGGER
        com.andrerinas.openheadunit.utils.AppLog.LOGGER = object : com.andrerinas.openheadunit.utils.AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                assertFalse("diagnostic held TLS engine lock", Thread.holdsLock(ssl))
                lines.add(msg)
            }
        }
        try {
            repeat(12) { assertEquals(0, ssl.decrypt(0, 1, byteArrayOf(1))!!.limit) }
            assertEquals(10, lines.count { it.contains("SSL Decrypt: no application data after consuming 1 bytes") })
            clock.`when`<Long> { android.os.SystemClock.elapsedRealtime() }.thenReturn(60_000L)
            ssl.decrypt(0, 1, byteArrayOf(1))
            assertEquals(11, lines.size)
            assertTrue(lines.last().contains("and 2 more since the last report"))
        } finally { com.andrerinas.openheadunit.utils.AppLog.LOGGER = original }
    }

}
