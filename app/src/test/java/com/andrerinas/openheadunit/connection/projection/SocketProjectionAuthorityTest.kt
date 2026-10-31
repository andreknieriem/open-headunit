package com.andrerinas.openheadunit.connection.projection

import android.content.Context
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** TCP effects belong to the request captured at construction, including after external waits. */
class SocketProjectionAuthorityTest {
    private val logLevel = AppLog::class.java.getDeclaredField("cachedLogLevel")
        .apply { isAccessible = true }
    private var oldLogLevel: Int? = null

    @Before fun silenceAndroidLog() {
        oldLogLevel = logLevel.getInt(null)
        // Gate errors before Android stack formatting, including on Dispatchers.IO.
        logLevel.setInt(null, Int.MAX_VALUE)
    }

    @After fun restoreLog() { oldLogLevel?.let { logLevel.setInt(null, it) } }

    private class Fixture : AutoCloseable {
        val allowed = AtomicBoolean(true)
        private val authorityLock = Any()
        val admission = ConnectionAdmission { effect -> synchronized(authorityLock) {
            if (!allowed.get()) false else { effect();true }
        } }
        val socket = RecordingSocket()
        val connection = SocketProjectionConnection("127.0.0.1", 5277, mock(Context::class.java), admission)

        init {
            SocketProjectionConnection::class.java.getDeclaredField("transport").apply {
                isAccessible = true
                (get(connection) as Socket).close()
                set(connection, socket)
            }
            socket.onBoundary = {
                assertFalse("External socket work must not hold admission monitor", Thread.holdsLock(authorityLock))
                for (field in SocketProjectionConnection::class.java.declaredFields.filter { it.name.endsWith("Lock") }) {
                    field.isAccessible = true
                    field.get(connection)?.let { lock ->
                        assertFalse("External socket work must not hold ${field.name}", Thread.holdsLock(lock))
                    }
                }
            }
        }

        fun assertNoStreams() {
            for (name in listOf("input", "output")) {
                assertNull("A retired candidate must not install $name",
                    SocketProjectionConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection))
            }
        }

        override fun close() { connection.disconnect() }
    }

    private class RecordingSocket : Socket() {
        var connected = false
        var retired = false
        var dials = 0
        var closes = 0
        var inputReads = 0
        var outputReads = 0
        var onBoundary: (() -> Unit)? = null
        var onDial: (() -> Unit)? = null
        var onInput: (() -> Unit)? = null
        override fun isConnected() = connected
        override fun isClosed() = retired
        override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getPort() = 5277
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            onBoundary?.invoke()
            check(!retired)
            dials++
            onDial?.invoke()
            if (retired) throw SocketException("closed while dial was completing")
            connected = true
        }
        override fun close() { onBoundary?.invoke();closes++;retired = true }
        override fun getInputStream(): ByteArrayInputStream {
            onBoundary?.invoke();inputReads++;onInput?.invoke()
            return ByteArrayInputStream(byteArrayOf(1))
        }
        override fun getOutputStream(): ByteArrayOutputStream {
            onBoundary?.invoke();outputReads++;return ByteArrayOutputStream()
        }
        override fun setTcpNoDelay(value: Boolean) {}
        override fun getTcpNoDelay() = true
        override fun setKeepAlive(value: Boolean) {}
        override fun setReuseAddress(value: Boolean) {}
        override fun setTrafficClass(value: Int) {}
        override fun setReceiveBufferSize(value: Int) {}
        override fun getReceiveBufferSize() = 262144
        override fun setSendBufferSize(value: Int) {}
        override fun getSendBufferSize() = 262144
        override fun setSoTimeout(value: Int) {}
    }

    @Test fun revokedRequestCannotBeginTcpDial() {
        Fixture().use { fixture ->
            fixture.allowed.set(false)
            assertFalse(runBlocking { fixture.connection.connect() })
            assertEquals(0, fixture.socket.dials)
            assertEquals(0, fixture.socket.inputReads)
            fixture.assertNoStreams()
        }
    }

    @Test fun currentRequestConnectsAndPublishesExactlyOnceOutsideMonitors() {
        Fixture().use { fixture ->
            assertTrue(runBlocking { fixture.connection.connect() })
            assertEquals(1, fixture.socket.dials)
            assertTrue(fixture.connection.isConnected)
            assertEquals(1, fixture.socket.inputReads)
            assertEquals(1, fixture.socket.outputReads)
        }
    }

    @Test fun disconnectClosesUnconnectedSocketAndCannotReopenIt() {
        Fixture().use { fixture ->
            fixture.connection.disconnect()
            assertTrue(fixture.socket.retired)
            assertEquals(1, fixture.socket.closes)
            assertFalse(runBlocking { fixture.connection.connect() })
            assertEquals(0, fixture.socket.dials)
            fixture.assertNoStreams()
        }
    }

    @Test fun revokedAlreadyEnteredDialClosesItsCandidateWithoutPublishing() {
        Fixture().use { fixture ->
            fixture.socket.onDial = { fixture.allowed.set(false) }
            assertFalse(runBlocking { fixture.connection.connect() })
            assertEquals("The admitted raw dial may complete", 1, fixture.socket.dials)
            assertTrue(fixture.socket.retired)
            fixture.assertNoStreams()
        }
    }

    @Test fun disconnectDuringAlreadyEnteredDialCannotPublishStreams() {
        Fixture().use { fixture ->
            fixture.socket.onDial = { fixture.connection.disconnect() }
            assertFalse(runBlocking { fixture.connection.connect() })
            assertEquals(1, fixture.socket.dials)
            assertTrue(fixture.socket.retired)
            assertFalse(fixture.connection.isConnected)
            fixture.assertNoStreams()
        }
    }

    @Test fun revocationInsideStreamGetterLosesFinalPublication() {
        Fixture().use { fixture ->
            fixture.socket.onInput = { fixture.allowed.set(false) }
            assertFalse(runBlocking { fixture.connection.connect() })
            assertEquals(1, fixture.socket.dials)
            assertEquals(1, fixture.socket.inputReads)
            assertTrue(fixture.socket.retired)
            fixture.assertNoStreams()
        }
    }
}
