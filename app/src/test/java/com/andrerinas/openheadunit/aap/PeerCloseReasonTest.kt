package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class PeerCloseReasonTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    private lateinit var androidLog: org.mockito.MockedStatic<android.util.Log>
    @org.junit.Before fun stubClock() {
        clock = mockStatic(android.os.SystemClock::class.java)
        androidLog = mockStatic(android.util.Log::class.java)
        androidLog.`when`<String> { android.util.Log.getStackTraceString(any(Throwable::class.java)) }.thenReturn("fixture callback failure")
    }
    @org.junit.After fun restoreClock() { androidLog.close(); clock.close() }

    private class StopAtCallback : RuntimeException()
    private fun transport(): AapTransport = mock(AapTransport::class.java, CALLS_REAL_METHODS).also {
        AapTransport::class.java.getDeclaredField("tlsWriter").apply { isAccessible = true }
            .set(it, AapTlsWriter(mock(AapSsl::class.java)) { _, length -> length })
        AapTransport::class.java.getDeclaredField("quitLock").apply { isAccessible = true }.set(it, Any())
        fun set(name: String, value: Any) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(it, value)
        set("lifecycleLock", Any())
        set("terminated", java.util.concurrent.CountDownLatch(1))
        set("videoBufferPool", java.util.concurrent.LinkedBlockingQueue<ByteArray>())
        set("videoBacklog", java.util.concurrent.atomic.AtomicInteger())
        set("videoShedTotal", java.util.concurrent.atomic.AtomicLong())
        // Keep the real retirement path: callback failure must not skip resource cleanup.
        for (name in listOf("videoDecoder", "aapAudio", "aapVideo", "ssl", "micSessions", "focusCycleLever")) {
            val field = AapTransport::class.java.getDeclaredField(name).apply { isAccessible = true }
            field.set(it, mock(field.type))
        }

    }

    @Test fun `queued send failure retains the received ByeBye intent`() {
        val transport = transport()
        val reasons = mutableListOf<Boolean>()
        transport.onQuit = { clean -> reasons.add(clean); throw StopAtCallback() }
        transport.notePeerClose()
        try { transport.quit() } catch (_: StopAtCallback) { }
        transport.quit(clean = true)
        assertEquals(listOf(true), reasons)
    }

    @Test fun `ordinary failure remains unclean and is claimed once`() {
        val transport = transport()
        val reasons = mutableListOf<Boolean>()
        transport.onQuit = { clean -> reasons.add(clean); throw StopAtCallback() }
        try { transport.quit() } catch (_: StopAtCallback) { }
        transport.notePeerClose()
        transport.quit(clean = true)
        assertEquals(listOf(false), reasons)
    }

    @Test fun `Helper header close is recorded before logging can let a writer quit first`() {
        val transport = transport()
        val reasons = mutableListOf<Boolean>()
        transport.onQuit = { clean -> reasons.add(clean); throw StopAtCallback() }
        val connection = mock(com.andrerinas.openheadunit.connection.projection.ProjectionConnection::class.java)
        `when`(connection.isConnected).thenReturn(true)
        `when`(connection.recvBlocking(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
            it.getArgument<ByteArray>(0).fill(0xff.toByte()); 4
        }
        val ssl = mock(AapSsl::class.java)
        val handler = mock(AapMessageHandler::class.java)
        val reader = AapReadSingleMessage(connection, ssl, handler, onPeerClose = transport::notePeerClose)
        val previous = com.andrerinas.openheadunit.utils.AppLog.LOGGER
        com.andrerinas.openheadunit.utils.AppLog.LOGGER = object : com.andrerinas.openheadunit.utils.AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                if (msg.contains("Magic Garbage detected in header")) {
                    try { transport.quit() } catch (_: StopAtCallback) { }
                }
            }
        }
        try {
            assertEquals(-2, reader.read())
            transport.quit(clean = true)
            assertEquals(listOf(true), reasons)
            verifyNoInteractions(ssl, handler)
        } finally { com.andrerinas.openheadunit.utils.AppLog.LOGGER = previous }
    }

    @Test fun `encrypted Helper close records intent before its diagnostic and clears listener on release`() {
        val transport = transport()
        val reasons = mutableListOf<Boolean>()
        transport.onQuit = { clean -> reasons.add(clean); throw StopAtCallback() }
        val ssl = AapSslContext(javax.net.ssl.SSLContext.getInstance("TLS"))
        val engine = mock(javax.net.ssl.SSLEngine::class.java)
        val session = mock(javax.net.ssl.SSLSession::class.java)
        `when`(engine.session).thenReturn(session)
        `when`(session.packetBufferSize).thenReturn(4096)
        `when`(engine.handshakeStatus).thenReturn(javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
        `when`(engine.unwrap(org.mockito.kotlin.any<java.nio.ByteBuffer>(), org.mockito.kotlin.any<java.nio.ByteBuffer>()))
            .thenThrow(javax.net.ssl.SSLException("not a TLS record"))
        fun field(name: String, value: Any) {
            AapSslContext::class.java.getDeclaredField(name).apply { isAccessible = true }.set(ssl, value)
        }
        field("sslEngine", engine)
        field("rxBuffer", java.nio.ByteBuffer.allocate(4096))
        field("plaintextBuffer", ByteArray(4096))
        field("applicationPump", TlsApplicationPump(engine))
        ssl.onPeerClose = transport::notePeerClose
        val previous = com.andrerinas.openheadunit.utils.AppLog.LOGGER
        com.andrerinas.openheadunit.utils.AppLog.LOGGER = object : com.andrerinas.openheadunit.utils.AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                if (msg.contains("SSL Decrypt: Magic Garbage detected")) {
                    try { transport.quit() } catch (_: StopAtCallback) { }
                }
            }
        }
        try {
            // A Helper marker is not a TLS record. Its unwrap failure is deliberately recognized.
            assertNull(ssl.decrypt(0, 16, ByteArray(16) { 0xff.toByte() }))
            assertTrue(ssl.isUserDisconnect)
            transport.quit(clean = true)
            assertEquals(listOf(true), reasons)
            ssl.release()
            assertNull(ssl.onPeerClose)
        } finally { com.andrerinas.openheadunit.utils.AppLog.LOGGER = previous }
    }
}
