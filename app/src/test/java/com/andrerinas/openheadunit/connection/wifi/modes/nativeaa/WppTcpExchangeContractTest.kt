package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.Context
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Runs whole WPP-over-TCP exchanges through the accept loop, with only the sockets, TLS and the
 * clock doubled. The admitted network has to hold from Type 4 (its WPP endpoint, 5299) through
 * Type 1 (the AAP address, 5288) to Type 3, and an exchange that cannot keep that promise has to
 * end whole, freeing the phone's reservation for its next dial.
 */
class WppTcpExchangeContractTest {
    private val operation = ConnectionOperation(0L, null)
    private val commManager = mock(CommManager::class.java).also {
        `when`(it.isCurrent(operation)).thenReturn(true)
        org.mockito.kotlin.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as () -> Any?)()
        }.`when`(it).withAppConnectionLifecycleLock<Any?>(org.mockito.kotlin.any())
    }


    private val stable = NativeNetworkCredentials(
        "DIRECT-AA-OHU", "12345678", "192.168.49.1", "02:11:22:33:44:55", GroupIdentityStability.STABLE
    )
    private val published = AtomicReference<NativeNetworkCredentials?>(stable)
    private val clock = AtomicLong(1_000L)
    private val dials = LinkedBlockingQueue<Socket>()
    private val listenerClosed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tlsFactory = mock(SSLSocketFactory::class.java)
    private val peer: InetAddress = InetAddress.getLoopbackAddress()

    private val callbacks = object : WppTcpServer.Callbacks {
        override fun credentials() = published.get()
        override fun strategy() = NativeStrategy.WIFI_DIRECT
        override fun carInfo(): Wireless.WppCarInfo = WppMessages.carInfo("OHU", "HU", "2026", "1", "OHU", "HU")
        override fun canRunRfcomm() = true
        override fun projectionSessionUp() = false
        override fun projectionEndpoint(network: NativeNetworkCredentials) = network.ip to 5288
        override fun noteDialRefused() {}
        override fun retiringNetworkName(): String? = null
        override fun noteEndpointRetired() {}
        override fun noteEndpointAdvertised(credentials: NativeNetworkCredentials) {}
    }

    private val server = WppTcpServer(
        mock(Context::class.java), scope, callbacks, operation, commManager,
        createServerSocket = { listener() },
        createTlsFactory = { tlsFactory },
        elapsedRealtime = { clock.get() + System.nanoTime() / 1_000_000 },
    )

    @Before fun initializeAndroidStatics() {
        // App captures process start time once. Initialize it on the test thread while the
        // Android clock is mocked; Mockito static mocks do not propagate to IO workers.
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
            Class.forName("com.andrerinas.openheadunit.App")
        }
    }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
    }

    @Test fun `admitted network completes the exchange through a -8 version response and a redelivery`() = runBlocking {
        server.start()
        val phone = dial()
        val version = phone.expect(WppMessageType.VERSION_REQUEST)
        assertEquals("Type 4 advertises the WPP endpoint, not the AAP port",
            WppTcpServer.PORT, Wireless.WifiVersionRequest.parseFrom(version).wppInfo.port)
        // NO_SUPPORTED_WIFI_CHANNELS on Type 5 is not a failure; the exchange carries on.
        phone.send(WppMessageType.VERSION_RESPONSE, Wireless.WifiVersionResponse.newBuilder().setStatus(-8).build().toByteArray())
        val start = Wireless.WifiStartRequest.parseFrom(phone.expect(WppMessageType.START_REQUEST))
        assertEquals(stable.ip, start.ipAddress)
        assertEquals(5288, start.port)
        // The same network delivered again, its BSSID in another case: not a replacement.
        published.set(stable.copy(bssid = stable.bssid.lowercase()))
        phone.send(WppMessageType.INFO_REQUEST)
        val info = Wireless.WifiInfoResponse.parseFrom(phone.expect(WppMessageType.INFO_RESPONSE))
        assertEquals(stable.ssid, info.ssid)
        assertEquals(Wireless.AccessPointType.STATIC, info.accessPointType)
    }

    @Test fun `an incomplete network after Type 1 ends the whole exchange and frees the peer`() = runBlocking {
        server.start()
        val phone = dial()
        phone.expect(WppMessageType.VERSION_REQUEST)
        phone.send(WppMessageType.VERSION_RESPONSE)
        phone.expect(WppMessageType.START_REQUEST)
        // WifiDirectManager forwards a group whose passphrase it could not read as "".
        published.set(stable.copy(psk = ""))
        phone.send(WppMessageType.INFO_REQUEST)
        phone.expectClosedWithout(WppMessageType.INFO_RESPONSE)
        awaitNoReservations()

        published.set(stable)
        dial().expect(WppMessageType.VERSION_REQUEST)
        Unit
    }

    @Test fun `a same-IP replacement after Type 1 is not sent under the admitted endpoint`() = runBlocking {
        server.start()
        val phone = dial()
        phone.expect(WppMessageType.VERSION_REQUEST)
        phone.send(WppMessageType.VERSION_RESPONSE)
        phone.expect(WppMessageType.START_REQUEST)
        published.set(stable.copy(ssid = "DIRECT-BB-OHU", bssid = "02:66:77:88:99:AA", identity = GroupIdentityStability.UNPROVEN))
        phone.send(WppMessageType.INFO_REQUEST)
        phone.expectClosedWithout(WppMessageType.INFO_RESPONSE)
        awaitNoReservations()
    }

    @Test fun `the admitted network re-judged as not to be kept ends the exchange`() = runBlocking {
        server.start()
        val phone = dial()
        phone.expect(WppMessageType.VERSION_REQUEST)
        phone.send(WppMessageType.VERSION_RESPONSE)
        phone.expect(WppMessageType.START_REQUEST)
        published.set(stable.copy(identity = GroupIdentityStability.UNPROVEN))
        phone.send(WppMessageType.INFO_REQUEST)
        phone.expectClosedWithout(WppMessageType.INFO_RESPONSE)
    }

    @Test fun `a replacement between admission and Type 4 sends nothing for it`() = runBlocking {
        val replacement = stable.copy(ssid = "DIRECT-BB-OHU", ip = "192.168.50.1")
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val swapping = object : WppTcpServer.Callbacks by callbacks {
            // The first read is admission; the group is replaced right after it.
            override fun credentials() = if (reads.getAndIncrement() == 0) stable else replacement
        }
        val raced = WppTcpServer(mock(Context::class.java), scope, swapping, operation, commManager, { listener() }, { tlsFactory }, { clock.get() + System.nanoTime() / 1_000_000 })
        try {
            raced.start()
            dial().expectClosedWithout(WppMessageType.VERSION_REQUEST)
        } finally { raced.stop() }
    }

    @Test fun `settling ends on its own deadline and frees the peer`() = runBlocking {
        server.start()
        val phone = dial()
        phone.expect(WppMessageType.VERSION_REQUEST)
        phone.send(WppMessageType.VERSION_RESPONSE)
        phone.expect(WppMessageType.START_REQUEST)
        phone.send(WppMessageType.INFO_REQUEST)
        phone.expect(WppMessageType.INFO_RESPONSE)
        // Ping is a read-loop barrier: the Type 3 action has returned and the stage timer is set.
        phone.send(WppMessageType.PING_REQUEST)
        phone.expect(WppMessageType.PING_RESPONSE)
        // Past the whole settle budget with no session. StageTimeout alone never ended SETTLING.
        clock.addAndGet(NativeHandoffPolicy.MAX_SETTLE_MS + 1_000L)
        phone.expectClosedWithout(WppMessageType.INFO_RESPONSE)
        awaitNoReservations()
    }

    // --- doubles ---

    private fun listener(): ServerSocket = mock(ServerSocket::class.java).also { socket ->
        `when`(socket.localPort).thenReturn(WppTcpServer.PORT)
        doAnswer {
            while (!listenerClosed.get()) dials.poll(50, TimeUnit.MILLISECONDS)?.let { return@doAnswer it }
            throw SocketException("closed")
        }.`when`(socket).accept()
        doAnswer { listenerClosed.set(true); null }.`when`(socket).close()
    }

    private inner class Phone(val tls: SSLSocket, private val toServer: OutputStream) {
        val frames = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)

        fun send(type: Int, payload: ByteArray = ByteArray(0)) {
            toServer.write(WppFraming.encodeFrame(payload, type))
            toServer.flush()
        }

        /** The next frame's payload, asserting its type. */
        fun expect(type: Int): ByteArray {
            val frame = frames.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no Type $type within 5s")
            assertEquals(type, WppFraming.decodeType(frame))
            return frame.copyOfRange(WppFraming.HEADER_SIZE, frame.size)
        }

        fun expectClosedWithout(type: Int) {
            assertTrue("the exchange must end, closing TLS", closed.await(5, TimeUnit.SECONDS))
            val sent = generateSequence { frames.poll() }.map { WppFraming.decodeType(it) }.toList()
            assertTrue("Type $type must not go out, sent $sent", type !in sent)
        }
    }

    private fun dial(): Phone {
        val inputBytes = LinkedBlockingQueue<Int>()
        val fromPhone = object : java.io.InputStream() {
            private var ended = false
            override fun read(): Int {
                if (ended) return -1
                return inputBytes.take().also { if (it < 0) ended = true }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val value = read()
                if (value < 0) return -1
                b[off] = value.toByte()
                return 1
            }
            override fun close() { inputBytes.offer(-1) }
        }
        val toServer = object : OutputStream() {
            override fun write(b: Int) { inputBytes.put(b and 255) }
        }
        val raw = mock(Socket::class.java)
        val tls = mock(SSLSocket::class.java)
        lateinit var phone: Phone
        val output = object : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) { phone.frames.put(b.copyOfRange(off, off + len)) }
        }
        `when`(raw.inetAddress).thenReturn(peer)
        `when`(raw.port).thenReturn(40_000)
        `when`(tlsFactory.createSocket(raw, peer.hostAddress, 40_000, true)).thenReturn(tls)
        `when`(tls.inputStream).thenReturn(fromPhone)
        `when`(tls.outputStream).thenReturn(output)
        phone = Phone(tls, toServer)
        doAnswer { fromPhone.close(); phone.closed.countDown(); null }.`when`(tls).close()
        dials.put(raw)
        return phone
    }

    private suspend fun awaitNoReservations() {
        val field = WppTcpServer::class.java.getDeclaredField("connections").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val connections = field.get(server) as Map<InetAddress, Socket>
        withTimeout(5_000) {
            while (synchronized(connections) { connections.isNotEmpty() }) delay(10)
        }
        assertNull(synchronized(connections) { connections[peer] })
    }
}
