package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** The real owner and reader run together; only Android's native carrier is replaced. */
class HctWppOwnerEofTest {
    @Test fun `old reader EOF cannot become a replacement client's handoff failure`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = mock(AapService::class.java)
        `when`(service.serviceScope).thenReturn(scope)
        val eofReached = CountDownLatch(1)
        val releaseEof = CountDownLatch(1)
        val input = PipedInputStream()
        val phone = PipedOutputStream(input)
        val closed = AtomicInteger()
        val wire = object : HandshakeLink {
            override val input = object : FilterInputStream(input) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(bytes, offset, length)
                    if (count < 0) {
                        eofReached.countDown()
                        check(releaseEof.await(5, TimeUnit.SECONDS))
                    }
                    return count
                }
            }
            override val output = ByteArrayOutputStream()
            override val peerName = "phone"
            override val peerAddress = "11:22:33:44:55:66"
            override val radioLabel = "test"
            override val persistPeerForAutoStart = false
            override val peerReportedPresent = true
            override val retransmitsWhileSilent = false
            override fun close() { closed.incrementAndGet(); input.close(); phone.close() }
        }
        lateinit var serve: suspend (HandshakeLink) -> Unit
        lateinit var unavailable: (HctWakePolicy.Result?, () -> Boolean) -> Unit
        val physicalStopped = CompletableDeferred<Unit>()
        val notices = AtomicInteger()
        @Suppress("UNCHECKED_CAST")
        val construction = mockConstruction(HctAaCarrier::class.java) { carrier, context ->
            serve = context.arguments()[2] as suspend (HandshakeLink) -> Unit
            unavailable = context.arguments()[6] as (HctWakePolicy.Result?, () -> Boolean) -> Unit
            doAnswer { physicalStopped.complete(Unit); null }.`when`(carrier).close()
            runBlocking {
                doAnswer { call ->
                    val wait: suspend () -> Unit = { physicalStopped.await() }
                    wait.startCoroutineUninterceptedOrReturn(call.rawArguments.last() as Continuation<Unit>)
                }.`when`(carrier).run()
            }
        }
        val owner = HctWppOwner(service, Dispatchers.Unconfined)
        val leaseReady = CompletableDeferred<HandshakeLink>()
        try {
            val first = owner.attach({ Wireless.WifiVersionRequest.getDefaultInstance() },
                { lease -> leaseReady.complete(lease); awaitCancellation() },
                { true }, { true }, {}, { notices.incrementAndGet() }, admission = ConnectionAdmission.UNRESTRICTED)
            first.requestWakeFor(wire.peerAddress, admission = ConnectionAdmission.UNRESTRICTED)
            val transport = scope.async {
                // This is the acquisition callback boundary in HctAaCarrier. Any I/O failure
                // escaping the real owner would be reported as initial handoff failure.
                try { serve(wire) } catch (_: IOException) { unavailable(null) { true } }
            }
            val lease = withTimeout(2_000) { leaseReady.await() }
            lease.output.write(WppFraming.encodeFrame(byteArrayOf(), 1))
            phone.write(WppFraming.encodeFrame(byteArrayOf(24, 0), 7))
            withContext(Dispatchers.IO) { DataInputStream(lease.input).readFully(ByteArray(6)) }
            owner.park(); first.close()
            phone.close()
            assertTrue(eofReached.await(2, TimeUnit.SECONDS))
            owner.attach({ Wireless.WifiVersionRequest.getDefaultInstance() },
                { awaitCancellation() }, { true }, { true }, {}, { notices.incrementAndGet() }, admission = ConnectionAdmission.UNRESTRICTED)
            assertEquals(1, construction.constructed().size) // Same physical carrier, new client.
            releaseEof.countDown()
            withTimeout(2_000) { transport.await() }
            assertEquals(0, notices.get())
            assertEquals(1, closed.get())
            // Initial acquisition errors still reach the current client; only reader EOF is local.
            unavailable(null) { true }
            assertEquals(1, notices.get())
        } finally {
            releaseEof.countDown(); owner.close(); scope.cancel(); construction.close()
        }
    }
}
