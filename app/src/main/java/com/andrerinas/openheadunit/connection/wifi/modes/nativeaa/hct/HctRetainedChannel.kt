package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppMessageType
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * One continuously owned, already verified HCT WPP connection. Projection leases are disposable;
 * the physical reader is not. Cancelling a blocking reader and attaching another one to the same
 * stream can split a four-byte WPP header between readers, so only this owner reads that stream.
 *
 * Related HCT firmware closes the local consumer independently of its physical AA profile.
 * Keeping our existing consumer avoids needing a new manufacturer AA event after ordinary Exit.
 * This never opens a socket or asserts ownership of a connection which has already reached EOF.
 */
internal class HctRetainedChannel(private val physical: HandshakeLink, private val scope: CoroutineScope) {
    private val closed = AtomicBoolean()
    private val current = AtomicReference<Lease?>()
    private val writeLock = Any()
    private val leaseLock = Any()
    private val ended = CompletableDeferred<Unit>()
    @Volatile private var parkedAfterAcceptedStart = false
    val peer: String? get() = physical.peerAddress
    val isOpen: Boolean get() = !closed.get()
    val isParked: Boolean get() = isOpen && parkedAfterAcceptedStart && current.get() == null
    val canRetain: Boolean get() = isOpen && (current.get()?.startAccepted ?: parkedAfterAcceptedStart)

    /** Called by the service owner; never more than one projection lease at a time. */
    fun lease(onReserved: () -> Unit = {}): Lease? = synchronized(leaseLock) {
        if (closed.get()) return null
        val lease = Lease()
        if (!current.compareAndSet(null, lease)) return null
        // EOF/close uses this same lock. Commit explicit selection only after ownership is
        // reserved, so a rejected reuse cannot change the chosen phone or retry policy.
        try { onReserved() } catch (error: Throwable) { lease.close(); throw error }
        return lease
    }

    suspend fun run() = coroutineScope {
        val reader = launch(Dispatchers.IO) {
            try {
                val input = DataInputStream(physical.input)
                while (isActive && !closed.get()) {
                    val header = ByteArray(WppFraming.HEADER_SIZE)
                    input.readFully(header)
                    val payload = ByteArray(WppFraming.decodePayloadSize(header))
                    input.readFully(payload)
                    when (WppFraming.decodeType(header)) {
                        WppMessageType.PING_REQUEST -> write(null,
                            WppFraming.encodeFrame(payload, WppMessageType.PING_RESPONSE))
                        WppMessageType.PING_RESPONSE -> Unit
                        // Opening already checked 1.7. Its buffered response is consumed here,
                        // not replayed into a new projection state machine on each reuse.
                        WppMessageType.VERSION_RESPONSE -> HctOpening.validateResponse(payload)
                        else -> current.get()?.let { lease ->
                            // Record acceptance before either inbox can delay FSM consumption.
                            // A Type 7 already read before Start cannot later become its reply.
                            if (WppFraming.decodeType(header) == WppMessageType.START_RESPONSE) {
                                lease.receiveStartResponse(payload)
                            }
                            if (!lease.inbound.trySend(header + payload).isSuccess && current.get() === lease) {
                                throw IOException("HCT WPP projection inbox overflow")
                            }
                        }
                    }
                }
            } finally { close() }
        }
        try { ended.await() } finally { close(); reader.cancel() }
    }

    /** Revokes queued projection work now; Ping remains owned by the physical reader. */
    fun park(): Unit = synchronized(leaseLock) {
        val lease = current.get()
        if (!canRetain) { close(); return }
        // Only an intentional Exit grants retention. A successful Start followed by a
        // timeout, ordinary disconnect or reader failure must not park itself in finally.
        parkedAfterAcceptedStart = true
        lease?.keepOnClose = true
        lease?.close()
    }

    fun close() {
        synchronized(leaseLock) {
            if (!closed.compareAndSet(false, true)) return
            current.getAndSet(null)?.retire()
        }
        // Close outside writeLock: a blocked writer must be interruptible by shutdown/watchdog.
        runCatching { physical.close() }
        ended.complete(Unit)
    }

    private fun write(lease: Lease?, frame: ByteArray) = synchronized(writeLock) {
        if (closed.get() || (lease != null && current.get() !== lease)) throw IOException("HCT WPP lease retired")
        // One whole frame per write. A frame already admitted here can finish during park;
        // a queued old writer checks its lease again after acquiring this lock and cannot send.
        val watchdog = scope.launch(Dispatchers.Default) { delay(5_000); close() }
        try {
            if (lease != null && WppFraming.decodeType(frame) == WppMessageType.START_REQUEST) {
                lease.startSent = true
            }
            physical.output.write(frame)
            physical.output.flush()
        }
        catch (error: Exception) { close(); throw error }
        finally { watchdog.cancel() }
    }

    inner class Lease : HandshakeLink {
        internal val inbound = Channel<ByteArray>(16)
        private val retired = AtomicBoolean()
        @Volatile internal var startSent = false
        @Volatile internal var startAccepted = false
        internal var keepOnClose = false
        private var chunk = ByteArray(0)
        private var offset = 0
        override val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                require(start >= 0 && length >= 0 && start <= bytes.size - length)
                if (length == 0) return 0
                if (retired.get()) return -1
                if (offset == chunk.size) {
                    chunk = runBlocking { inbound.receiveCatching().getOrNull() } ?: return -1
                    offset = 0
                }
                if (retired.get()) return -1
                val count = minOf(length, chunk.size - offset)
                chunk.copyInto(bytes, start, offset, offset + count)
                offset += count
                return count
            }
            override fun close() { this@Lease.close() }
        }
        override val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("WPP requires a complete frame") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val frame = bytes.copyOfRange(offset, offset + length)
                require(frame.size >= WppFraming.HEADER_SIZE &&
                    WppFraming.decodePayloadSize(frame) == frame.size - WppFraming.HEADER_SIZE)
                write(this@Lease, frame)
            }
        }
        override val peerName get() = physical.peerName
        override val peerAddress get() = physical.peerAddress
        override val radioLabel get() = physical.radioLabel
        override val persistPeerForAutoStart = false
        override val peerReportedPresent = true
        override val retransmitsWhileSilent = false
        override val versionRequestAlreadySent = true
        override val versionNegotiated = true
        internal fun receiveStartResponse(payload: ByteArray): Unit = synchronized(leaseLock) {
            if (current.get() !== this || closed.get() || !startSent) return
            val response = runCatching { Wireless.WifiStartResponse.parseFrom(payload) }.getOrNull()
            if (response?.hasStatus() == true && response.status == 0) startAccepted = true
            // WPP has no request ID. A duplicate first received after the next Start cannot
            // be distinguished, but a response already read before Start is never reclassified.
        }
        internal fun retire() { retired.set(true); inbound.cancel() }
        override fun close(): Unit = synchronized(leaseLock) {
            if (!current.compareAndSet(this, null)) { retire(); return }
            retire()
            // Only park() can grant retention, after this peer accepted this Start. A failed
            // reuse cannot inherit an older lease's success, or another phone's TCP connection.
            if (!keepOnClose) this@HctRetainedChannel.close()
        }
    }
}
