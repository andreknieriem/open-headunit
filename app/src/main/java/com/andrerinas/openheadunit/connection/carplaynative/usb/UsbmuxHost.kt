package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.utils.AppLog
import java.util.ArrayDeque

/**
 * usbmux version 2 over the iPhone bulk pipe: the handshake, then small TCP streams to ports on the iPhone.
 * The link's reader thread feeds [onBytes]. Callers block, so they run off the main thread. [close] ends every stream.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/Iap2UsbMuxHost.kt` and DiPlay (https://github.com/shihabal3amri/DiPlay) `transport/Iap2UsbMuxHost.kt`.
 */
class UsbmuxHost(
    private val sendBytes: (ByteArray) -> Unit,
    private val clock: () -> Long,
) : MuxConnector, AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val writeLock = Any()
    private val frames = UsbmuxCodec.FrameBuffer { AppLog.w("CarPlayNative: $it") }
    private val connections = HashMap<Int, MuxConnection>()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var versionAccepted = false
    private var staleFrames = 0
    private var sequence = 0
    private var acknowledgement = 0
    private var nextSourcePort = FIRST_SOURCE_PORT

    /** Sends VERSION, waits for the reply, then sends SETUP. A frame that is not the reply ends it. */
    fun begin(timeoutMs: Long = HANDSHAKE_TIMEOUT_MS) {
        synchronized(lock) { checkOpen() }
        write(UsbmuxCodec.versionPacket())
        val deadline = clock() + timeoutMs
        synchronized(lock) {
            while (!versionAccepted) {
                checkOpen()
                val remaining = deadline - clock()
                if (remaining <= 0) throw IphoneUsbException.TimedOut("usbmux version reply not received")
                waitOn(remaining)
            }
        }
        AppLog.i("CarPlayNative: usbmux version accepted")
        sendFrame(UsbmuxCodec.PROTOCOL_SETUP, byteArrayOf(UsbmuxCodec.SETUP_VALUE.toByte()))
    }

    /** Called by the link's reader thread with each bulk read. */
    fun onBytes(bytes: ByteArray) {
        val toSend = ArrayList<Pair<MuxConnection, ByteArray>>()
        try {
            synchronized(lock) {
                if (closed) return
                frames.append(bytes)
                while (true) {
                    val frame = frames.takeFrame() ?: break
                    acknowledgement = frame.sequence
                    handle(frame, toSend)
                }
                lock.notifyAll()
            }
        } catch (e: UsbmuxFramingException) {
            fail(IphoneUsbException.Protocol("usbmux framing: ${e.message}", e))
            return
        }
        try {
            toSend.forEach { (_, segment) -> sendFrame(UsbmuxCodec.PROTOCOL_TCP, segment) }
        } catch (e: IphoneUsbException) {
            fail(e)
        }
    }

    /** The link ended under the host. Every stream fails with [reason]. */
    fun onLinkEnded(reason: String) = fail(IphoneUsbException.DeviceUnavailable(reason))

    override fun connect(port: Int, timeoutMillis: Long): BlockingDuplexByteStream {
        require(port in 1..0xffff && timeoutMillis > 0)
        val connection = synchronized(lock) {
            checkOpen()
            val source = allocateSourcePort()
            MuxConnection(UsbmuxTcp(source, port)).also { connections[source] = it }
        }
        try {
            sendFrame(UsbmuxCodec.PROTOCOL_TCP, synchronized(lock) { connection.tcp.syn() })
            val deadline = clock() + timeoutMillis
            synchronized(lock) {
                while (!connection.connected) {
                    checkOpen()
                    connection.failure?.let { throw it }
                    if (connection.ended) throw IphoneUsbException.DeviceUnavailable("usbmux connection to port $port closed")
                    val remaining = deadline - clock()
                    if (remaining <= 0) throw IphoneUsbException.TimedOut("usbmux connection to port $port timed out")
                    waitOn(remaining)
                }
            }
            return connection
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    override fun close() {
        val open = synchronized(lock) {
            if (closed) return
            closed = true
            // Wakes a begin() that waits for the version reply.
            lock.notifyAll()
            connections.values.toList().also { connections.clear() }
        }
        open.forEach { it.markEnded(null) }
    }

    private fun fail(error: IphoneUsbException) {
        val open = synchronized(lock) {
            if (closed) return
            failure = error
            closed = true
            lock.notifyAll()
            connections.values.toList().also { connections.clear() }
        }
        AppLog.w("CarPlayNative: usbmux ended (${error.message})")
        open.forEach { it.markEnded(error) }
    }

    private fun handle(frame: UsbmuxCodec.Frame, toSend: MutableList<Pair<MuxConnection, ByteArray>>) {
        if (!versionAccepted) {
            when {
                UsbmuxCodec.isVersionReply(frame) -> versionAccepted = true
                // Bytes the last process left in the endpoint are not the reply to our VERSION.
                frame.protocol == UsbmuxCodec.PROTOCOL_TCP && ++staleFrames <= MAX_STALE_FRAMES -> Unit
                else -> throw UsbmuxFramingException("unexpected frame before the version reply (protocol ${frame.protocol})")
            }
            return
        }
        if (frame.protocol != UsbmuxCodec.PROTOCOL_TCP) return
        val segment = UsbmuxTcp.parse(frame.payload)
        val connection = connections[segment.destinationPort] ?: return
        val reaction = connection.tcp.onSegment(segment.flags, segment.sequence, segment.payload)
        reaction.replies.forEach { toSend.add(connection to it) }
        if (reaction.data != null && !connection.accept(reaction.data)) {
            connections.remove(segment.destinationPort)
        }
        if (reaction.connected) connection.connected = true
        if (reaction.reset) {
            connections.remove(segment.destinationPort)
            connection.failure = IphoneUsbException.DeviceUnavailable("usbmux connection reset by the iPhone")
            connection.ended = true
        } else if (reaction.closed) {
            connection.ended = true
        }
    }

    private fun sendFrame(protocol: Int, payload: ByteArray) = write(null, protocol, payload)

    private fun write(raw: ByteArray) = write(raw, 0, ByteArray(0))

    /** Sequence numbers are taken inside the write lock, so frames leave in numbered order. */
    private fun write(raw: ByteArray?, protocol: Int, payload: ByteArray) = synchronized(writeLock) {
        val (seq, ack) = synchronized(lock) { checkOpen(); sequence to acknowledgement }
        try {
            if (raw != null) sendBytes(raw) else sendBytes(UsbmuxCodec.frame(protocol, seq, ack, payload))
        } catch (e: IphoneUsbException) {
            fail(e)
            throw e
        }
        if (raw == null) synchronized(lock) { sequence = (sequence + 1) and 0xffff }
    }

    private fun allocateSourcePort(): Int {
        repeat(0xffff) {
            val candidate = nextSourcePort
            nextSourcePort = if (candidate == 0xffff) FIRST_SOURCE_PORT else candidate + 1
            if (candidate !in connections) return candidate
        }
        throw IphoneUsbException.DeviceUnavailable("usbmux has no free source port")
    }

    private fun checkOpen() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("usbmux host is closed")
    }

    private fun waitOn(remainingMs: Long) {
        try { lock.wait(remainingMs) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IphoneUsbException.DeviceUnavailable("interrupted while waiting for usbmux", e)
        }
    }

    private inner class MuxConnection(val tcp: UsbmuxTcp) : BlockingDuplexByteStream {
        private val sendLock = Any()
        private val received = ArrayDeque<ByteArray>()
        private var receivedBytes = 0
        var connected = false
        var ended = false
        var failure: IphoneUsbException? = null
        private var closedLocally = false

        /** Queues payload under the host lock. False when it overflows: the stream fails and the host sends a reset. */
        fun accept(data: ByteArray): Boolean {
            if (closedLocally) return true
            if (receivedBytes + data.size > MAX_RECEIVED_BYTES) {
                failure = IphoneUsbException.Protocol("usbmux receive queue for port ${tcp.destinationPort} is full")
                ended = true
                received.clear()
                receivedBytes = 0
                return false
            }
            received.addLast(data)
            receivedBytes += data.size
            return true
        }

        override fun send(data: ByteArray) {
            if (data.isEmpty()) return
            synchronized(sendLock) {
                val segments = synchronized(lock) {
                    checkOpen()
                    failure?.let { throw it }
                    if (!connected || ended || closedLocally) throw IphoneUsbException.DeviceUnavailable("usbmux connection is not open")
                    tcp.data(data)
                }
                segments.forEach { sendFrame(UsbmuxCodec.PROTOCOL_TCP, it) }
            }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            require(maxBytes > 0 && timeoutMillis > 0)
            val deadline = clock() + timeoutMillis
            synchronized(lock) {
                while (received.isEmpty() && !ended && !closedLocally && !closed) {
                    val remaining = deadline - clock()
                    if (remaining <= 0) return null
                    waitOn(remaining)
                }
                if (received.isEmpty()) {
                    failure?.let { throw it }
                    return ByteArray(0)
                }
                val head = received.removeFirst()
                receivedBytes -= head.size
                if (head.size <= maxBytes) return head
                val tail = head.copyOfRange(maxBytes, head.size)
                received.addFirst(tail)
                receivedBytes += tail.size
                return head.copyOf(maxBytes)
            }
        }

        override fun close() {
            val fin = synchronized(lock) {
                if (closedLocally) return
                closedLocally = true
                connections.remove(tcp.sourcePort)
                lock.notifyAll()
                if (connected && !closed) tcp.fin() else null
            }
            if (fin != null) try { sendFrame(UsbmuxCodec.PROTOCOL_TCP, fin) } catch (_: IphoneUsbException) { /* the pipe is gone, the stream is closed */ }
        }

        fun markEnded(error: IphoneUsbException?) = synchronized(lock) {
            if (error != null && failure == null) failure = error
            ended = true
            closedLocally = true
            lock.notifyAll()
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val FIRST_SOURCE_PORT = 1
        private const val MAX_STALE_FRAMES = 32
        private const val MAX_RECEIVED_BYTES = 8 * 1024 * 1024
    }
}
