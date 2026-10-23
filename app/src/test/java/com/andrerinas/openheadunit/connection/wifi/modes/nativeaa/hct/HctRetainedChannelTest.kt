package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.*
import java.io.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HctRetainedChannelTest {
    private class Wire : HandshakeLink {
        override val input = PipedInputStream(256 * 1024)
        val phone = PipedOutputStream(input)
        val bytes = ByteArrayOutputStream()
        override val output = object : OutputStream() {
            override fun write(value: Int) { synchronized(bytes) { bytes.write(value) }; Thread.yield() }
        }
        @Volatile var closes = 0
        fun written() = synchronized(bytes) { bytes.toByteArray() }
        fun send(type: Int, payload: ByteArray = byteArrayOf()) { phone.write(WppFraming.encodeFrame(payload, type)); phone.flush() }
        override val peerName = "phone"
        override val peerAddress = "11:22:33:44:55:66"
        override val radioLabel = "HCT test"
        override val persistPeerForAutoStart = false
        override val peerReportedPresent = true
        override val retransmitsWhileSilent = false
        override fun close() { closes++; input.close(); phone.close() }
    }

    private fun acceptStart(lease: HctRetainedChannel.Lease, wire: Wire) {
        lease.output.write(WppFraming.encodeFrame(byteArrayOf(), WppMessageType.START_REQUEST))
        lease.receiveStartResponse(byteArrayOf(24, 0)) // optional status field 3 = SUCCESS
        synchronized(wire.bytes) { wire.bytes.reset() }
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(2_000) { while (!predicate()) delay(5) }
    }

    @Test fun `Exit keeps one reader ping response and next explicit Start on the same channel`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        val first = owner.lease()!!
        val job = scope.launch { runCatching { owner.run() } }
        try {
            acceptStart(first, wire)
            owner.park()
            assertTrue(owner.isParked)
            assertEquals(0, wire.closes)
            wire.send(WppMessageType.INFO_REQUEST) // Parked frames cannot become a later lease's input.
            wire.send(WppMessageType.PING_REQUEST, byteArrayOf(9, 8, 7))
            eventually { wire.written().size == 7 }
            assertArrayEquals(WppFraming.encodeFrame(byteArrayOf(9, 8, 7), 9), wire.written())
            val second = owner.lease()!!
            assertTrue(second.versionNegotiated)
            val session = WppHandshakeSession(holdsChannel = true, versionNegotiated = second.versionNegotiated)
            assertTrue(session.on(WppEvent.SocketReady).isEmpty())
            assertEquals(listOf(WppAction.SendStartRequest), session.on(WppEvent.CredentialsReady))
            second.output.write(WppFraming.encodeFrame(byteArrayOf(1, 2), WppMessageType.START_REQUEST))
            first.close() // Delayed cleanup belongs to the old lease only.
            assertTrue(owner.isOpen)
            wire.send(WppMessageType.START_RESPONSE, byteArrayOf(3))
            val frame = ByteArray(5)
            withContext(Dispatchers.IO) { DataInputStream(second.input).readFully(frame) }
            assertArrayEquals(WppFraming.encodeFrame(byteArrayOf(3), 7), frame)
            assertEquals(0, wire.closes)
            // Failed reuse must not inherit first lease's projection success.
            second.close()
            assertFalse(owner.isOpen)
            assertEquals(1, wire.closes)
        } finally { owner.close(); job.join(); scope.cancel() }
    }

    @Test fun `EOF while parked revokes reuse rather than opening another socket`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        acceptStart(owner.lease()!!, wire)
        owner.park()
        val job = scope.launch { runCatching { owner.run() } }
        wire.phone.close()
        job.join()
        assertFalse(owner.isOpen)
        assertNull(owner.lease())
        assertEquals(1, wire.closes)
        scope.cancel()
    }

    @Test fun `closing a lease unblocks its reader and rejects stale writes`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        val lease = owner.lease()!!
        val read = scope.async { lease.input.read() }
        acceptStart(lease, wire)
        owner.park()
        assertEquals(-1, withTimeout(2_000) { read.await() })
        val next = owner.lease()!!
        try {
            lease.output.write(WppFraming.encodeFrame(byteArrayOf(1), 1))
            fail("retired writer accepted")
        } catch (_: IOException) {}
        assertTrue(owner.isOpen)
        next.output.write(WppFraming.encodeFrame(byteArrayOf(2), 1))
        assertArrayEquals(WppFraming.encodeFrame(byteArrayOf(2), 1), wire.written())
        owner.close(); scope.cancel()
    }

    @Test fun `unconsumed projection messages are bounded and overflow closes ownership`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        owner.lease()!!
        val job = scope.launch { runCatching { owner.run() } }
        repeat(17) { wire.send(WppMessageType.INFO_REQUEST) }
        job.join()
        assertFalse(owner.isOpen)
        assertNull(owner.lease())
        scope.cancel()
    }
    @Test fun `actual FSM bridge keeps its bound while the consumer waits`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        val lease = owner.lease()!!
        val input = DataInputStream(lease.input)
        val bridge = WppInboundMessages(lease.versionNegotiated) {
            val header = ByteArray(4)
            input.readFully(header)
            val payload = ByteArray(WppFraming.decodePayloadSize(header))
            input.readFully(payload)
            header + payload
        }
        val read = scope.launch { runCatching { bridge.readUntilClosed() } }
        val physical = scope.launch { runCatching { owner.run() } }
        try {
            // The real bridge is running but the FSM is deliberately not consuming. Even
            // after it drains the first inbox, total retention must remain bounded.
            repeat(100) { wire.send(WppMessageType.INFO_REQUEST) }
            withTimeout(2_000) { physical.join() }
            assertFalse(owner.isOpen)
        } finally { owner.close(); read.cancelAndJoin(); scope.cancel() }
    }

    @Test fun `failed reuse serializes retirement before another lease can enter`() {
        val closing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        acceptStart(owner.lease()!!, wire)
        owner.park()
        val failed = owner.lease()!!
        failed.inbound.invokeOnClose { closing.countDown(); release.await() }
        val cleanup = Thread { failed.close() }.apply { start() }
        assertTrue(closing.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val candidate = java.util.concurrent.atomic.AtomicReference<HctRetainedChannel.Lease?>()
        val selection = Thread { candidate.set(owner.lease()) }.apply { start() }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (selection.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, selection.state)
        release.countDown()
        cleanup.join(2_000); selection.join(2_000)
        assertFalse(cleanup.isAlive); assertFalse(selection.isAlive)
        assertNull(candidate.get())
        assertFalse(owner.isOpen)
        scope.cancel()
    }

    @Test fun `Start acceptance alone never retains a failed attempt without intentional Exit`() {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        val lease = owner.lease()!!
        acceptStart(lease, wire)
        lease.close() // Timeout/ordinary failure, not owner.park().
        assertFalse(owner.isOpen)
        scope.cancel()
    }

    @Test fun `an unrelated accepted TCP session cannot make an unanswered Start retainable`() {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        owner.lease()!!
        // The physical owner has no generic TCP-success callback. Only its own Type 7
        // acceptance can qualify it; Exit with an unanswered Start always closes it.
        owner.park()
        assertFalse(owner.isOpen)
        scope.cancel()
    }

    @Test fun `Start success allows Type3 and repeated Type2 until intentional park`() = runBlocking {
        for (early in listOf(true, false)) {
            val wire = Wire()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val owner = HctRetainedChannel(wire, scope)
            val lease = owner.lease()!!
            val job = scope.launch { runCatching { owner.run() } }
            val fsm = WppHandshakeSession(holdsChannel = true, versionNegotiated = true)
            fsm.on(WppEvent.SocketReady); fsm.on(WppEvent.CredentialsReady)
            lease.output.write(WppFraming.encodeFrame(byteArrayOf(), 1))
            try {
                if (early) {
                    wire.send(7, byteArrayOf(24, 0))
                    eventually { owner.canRetain }
                    fsm.on(WppEvent.MessageReceived(7, 0))
                }
                assertTrue(fsm.on(WppEvent.MessageReceived(2, null)).contains(WppAction.SendInfoResponse))
                lease.output.write(WppFraming.encodeFrame(byteArrayOf(1), 3))
                if (!early) {
                    wire.send(7, byteArrayOf(24, 0))
                    eventually { owner.canRetain }
                    fsm.on(WppEvent.MessageReceived(7, 0))
                }
                assertTrue(fsm.on(WppEvent.MessageReceived(2, null)).contains(WppAction.SendInfoResponse))
                lease.output.write(WppFraming.encodeFrame(byteArrayOf(2), 3))
                owner.park()
                try { lease.output.write(WppFraming.encodeFrame(byteArrayOf(3), 3)); fail("old lease wrote after Exit") }
                catch (_: IOException) {}
                assertTrue(owner.isParked)
            } finally { owner.close(); job.join(); scope.cancel() }
        }
    }

    @Test fun `Type7 queued before Start never becomes acceptance when consumed afterwards`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(wire, scope)
        val lease = owner.lease()!!
        val job = scope.launch { runCatching { owner.run() } }
        try {
            wire.send(7, byteArrayOf(24, 0))
            wire.send(8) // Physical reader barrier: Type7 is now in the lease inbox.
            eventually { wire.written().size == 4 }
            lease.output.write(WppFraming.encodeFrame(byteArrayOf(), 1))
            DataInputStream(lease.input).readFully(ByteArray(6))
            assertFalse(owner.canRetain)
            owner.park()
            assertFalse(owner.isOpen)
        } finally { owner.close(); job.join(); scope.cancel() }
    }

    @Test fun `retired physical channel never commits a selection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = HctRetainedChannel(Wire(), scope)
        owner.close()
        var selections = 0
        assertNull(owner.lease { selections++ })
        assertEquals(0, selections)
        scope.cancel()
    }

    @Test fun `physical close ends an idle or partial frame reader`() = runBlocking {
        for (prefix in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 2, 0, 8, 1))) {
            val wire = Wire()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val owner = HctRetainedChannel(wire, scope)
            val job = scope.launch { runCatching { owner.run() } }
            wire.phone.write(prefix); wire.phone.flush()
            owner.close()
            withTimeout(2_000) { job.join() }
            assertEquals(1, wire.closes)
            assertFalse(owner.isOpen)
            scope.cancel()
        }
    }

}
