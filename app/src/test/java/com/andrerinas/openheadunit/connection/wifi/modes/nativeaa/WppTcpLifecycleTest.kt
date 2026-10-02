package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.Context
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Drives the production TLS-to-hold path, with only the socket and Android context doubled. */
class WppTcpLifecycleTest {
    @Test fun `stop while bind is blocked closes the unpublished listener`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = mock(ServerSocket::class.java)
        val bound = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = CountDownLatch(1)
        doAnswer {
            bound.countDown()
            check(resume.await(3, TimeUnit.SECONDS))
            null
        }.`when`(listener).bind(any())
        doAnswer { closed.countDown(); null }.`when`(listener).close()
        val server = WppTcpServer(
            mock(Context::class.java), owner, mock(WppTcpServer.Callbacks::class.java),
            createServerSocket = { listener }, createTlsFactory = { mock(SSLSocketFactory::class.java) }
        )
        try {
            server.start()
            assertTrue(bound.await(3, TimeUnit.SECONDS))
            server.stop()
            resume.countDown()
            assertTrue("the unpublished listener must not leak after stop", closed.await(3, TimeUnit.SECONDS))
            assertNull(server.listeningPort)
            verify(listener, never()).accept()
            Unit
        } finally { resume.countDown(); server.stop(); owner.cancel() }
    }

    @Test fun `an accepted job from a retired server cannot begin TLS on the next run`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = WppTcpServer(mock(Context::class.java), owner, mock(WppTcpServer.Callbacks::class.java))
        val raw = mock(Socket::class.java)
        val factory = mock(SSLSocketFactory::class.java)
        server.stop() // invalidates generation zero before its accepted coroutine starts
        WppTcpServer::class.java.getDeclaredField("running").apply {
            isAccessible = true
            setBoolean(server, true) // a later run is live; running alone is insufficient
        }
        try {
            withTimeout(3000) { runConnection(server, raw, factory) }
            verifyNoInteractions(factory)
            verify(raw).close()
        } finally {
            server.stop()
            owner.cancel()
        }
    }

    @Test fun `withheld endpoint stays open and answers only pings until projection ends`() = runBlocking {
        val projecting = AtomicBoolean(true)
        val callbacks = mock(WppTcpServer.Callbacks::class.java)
        `when`(callbacks.strategy()).thenReturn(NativeStrategy.WIFI_DIRECT)
        `when`(callbacks.identity()).thenReturn(GroupIdentityStability.RENAMED)
        `when`(callbacks.projectionSessionUp()).thenAnswer { projecting.get() }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = WppTcpServer(mock(Context::class.java), owner, callbacks)
        WppTcpServer::class.java.getDeclaredField("running").apply {
            isAccessible = true
            setBoolean(server, true)
        }
        val input = PipedInputStream()
        val phone = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        val raw = mock(Socket::class.java)
        val tls = mock(SSLSocket::class.java)
        val factory = mock(SSLSocketFactory::class.java)
        val handshaken = CountDownLatch(1)
        `when`(raw.inetAddress).thenReturn(InetAddress.getLoopbackAddress())
        `when`(raw.port).thenReturn(5299)
        `when`(factory.createSocket(raw, raw.inetAddress.hostAddress, 5299, true)).thenReturn(tls)
        `when`(tls.inputStream).thenReturn(input)
        `when`(tls.outputStream).thenReturn(output)
        doAnswer { handshaken.countDown(); null }.`when`(tls).startHandshake()
        doAnswer { input.close(); null }.`when`(tls).close()
        val session = async(Dispatchers.IO) { runConnection(server, raw, factory) }
        try {
            assertTrue(handshaken.await(3, TimeUnit.SECONDS))
            val payload = byteArrayOf(8, 1)
            phone.write(WppFraming.encodeFrame(payload, WppMessageType.PING_REQUEST))
            phone.flush()
            withTimeout(3000) {
                while (output.size() == 0 && !session.isCompleted) delay(10)
            }
            // The old early-refusal path completes with no reply and a closed TLS socket.
            assertFalse("active projection must retain its control connection", session.isCompleted)
            assertArrayEquals(WppFraming.encodeFrame(payload, WppMessageType.PING_RESPONSE), output.toByteArray())
            verify(tls, never()).close()
            verify(callbacks, never()).noteDialRefused()
            verify(callbacks, never()).noteEndpointAdvertised()
            projecting.set(false)
            withTimeout(3000) { session.await() }
            verify(tls).close()
        } finally {
            projecting.set(false)
            server.stop()
            phone.close()
            input.close()
            session.cancelAndJoin()
            owner.cancel()
        }
    }

    private suspend fun runConnection(server: WppTcpServer, raw: Socket, factory: SSLSocketFactory): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = WppTcpServer::class.java.getDeclaredMethod(
                "handleConnection", Socket::class.java, SSLSocketFactory::class.java,
                Long::class.javaPrimitiveType, Continuation::class.java
            ).apply { isAccessible = true }
            method.invoke(server, raw, factory, 0L, continuation)
        }
}
