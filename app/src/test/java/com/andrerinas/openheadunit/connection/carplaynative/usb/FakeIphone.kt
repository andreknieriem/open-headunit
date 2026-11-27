package com.andrerinas.openheadunit.connection.carplaynative.usb

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device side of the iPhone USB link, for JVM tests: a pipe that speaks usbmux and a small TCP to listeners on the "iPhone".
 * It is the script of record for the rig fake iPhone. Every thread it starts ends in [close].
 */
class FakeIphone : IphoneUsbPipe {
    private val toHost = LinkedBlockingQueue<ByteArray>()
    private val deviceFrames = UsbmuxCodec.FrameBuffer()
    private val connections = ConcurrentHashMap<Int, DeviceConn>()
    private val listeners = ConcurrentHashMap<Int, (BlockingDuplexByteStream) -> Unit>()
    private val threads = java.util.Collections.synchronizedList(ArrayList<Thread>())
    private var sequence = 0
    @Volatile private var closed = false
    val closeCount = AtomicInteger()
    val versionRequests = AtomicInteger()
    val openStreams = AtomicInteger()

    /** Answers a control transfer: the request code and the buffer to fill give the byte count, or -1. */
    var control: (request: Int, buffer: ByteArray) -> Int = { _, _ -> -1 }

    /** Splits each frame sent to the host into reads of this size, to test reassembly. 0 sends whole frames. */
    var readSplit = 0

    /** Frames the host wrote, without parsing. */
    val written = LinkedBlockingQueue<ByteArray>()

    fun listen(port: Int, handler: (BlockingDuplexByteStream) -> Unit) { listeners[port] = handler }

    fun sendToHost(bytes: ByteArray) {
        if (readSplit <= 0) toHost.add(bytes) else bytes.toList().chunked(readSplit).forEach { toHost.add(it.toByteArray()) }
    }

    override fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int) = control(request, buffer)

    override fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        if (closed) return -1
        written.add(buffer.copyOf(length))
        synchronized(deviceFrames) {
            deviceFrames.append(buffer.copyOf(length))
            while (true) handle(deviceFrames.takeFrame() ?: break)
        }
        return length
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) return -1
        val chunk = toHost.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        if (chunk.isEmpty()) return -1
        chunk.copyInto(buffer)
        return chunk.size
    }

    override fun close() {
        closed = true
        closeCount.incrementAndGet()
        toHost.add(ByteArray(0))
        synchronized(threads) { threads.toList() }.forEach { it.interrupt() }
    }

    fun joinThreads(timeoutMs: Long = 2000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(threads) { threads.toList() }.forEach { it.join(maxOf(1, end - System.currentTimeMillis())) }
        return synchronized(threads) { threads.none { it.isAlive } }
    }

    private fun handle(frame: UsbmuxCodec.Frame) {
        when (frame.protocol) {
            UsbmuxCodec.PROTOCOL_VERSION -> { versionRequests.incrementAndGet(); sendToHost(UsbmuxCodec.versionPacket()) }
            UsbmuxCodec.PROTOCOL_TCP -> {
                val segment = UsbmuxTcp.parse(frame.payload)
                if (segment.flags and UsbmuxTcp.SYN != 0) accept(segment) else connections[segment.sourcePort]?.onSegment(segment)
            }
        }
    }

    private fun accept(syn: UsbmuxTcp.Segment) {
        val handler = listeners[syn.destinationPort]
        val conn = DeviceConn(syn.destinationPort, syn.sourcePort, syn.sequence + 1)
        if (handler == null) { conn.send(UsbmuxTcp.RST or UsbmuxTcp.ACK, ByteArray(0)); return }
        connections[syn.sourcePort] = conn
        conn.send(UsbmuxTcp.SYN or UsbmuxTcp.ACK, ByteArray(0))
        conn.seq = 1
        val thread = Thread({
            openStreams.incrementAndGet()
            try { handler(conn) } catch (_: Exception) { } finally { openStreams.decrementAndGet(); conn.close() }
        }, "fake-iphone-${syn.destinationPort}").apply { isDaemon = true }
        threads.add(thread)
        thread.start()
    }

    private fun emit(segment: ByteArray, acknowledgement: Int) {
        val out = synchronized(this) { UsbmuxCodec.frame(UsbmuxCodec.PROTOCOL_TCP, sequence++ and 0xffff, acknowledgement, segment) }
        sendToHost(out)
    }

    private inner class DeviceConn(val devicePort: Int, val hostPort: Int, var ack: Int) : BlockingDuplexByteStream {
        var seq = 0
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private var ended = false
        private var closedHere = false

        fun send(flags: Int, payload: ByteArray) {
            val segment = ByteArray(20 + payload.size)
            UsbmuxCodec.putU16(segment, 0, devicePort); UsbmuxCodec.putU16(segment, 2, hostPort)
            UsbmuxCodec.putU32(segment, 4, seq); UsbmuxCodec.putU32(segment, 8, ack)
            segment[12] = (5 shl 4).toByte(); segment[13] = flags.toByte(); UsbmuxCodec.putU16(segment, 14, 512)
            payload.copyInto(segment, 20)
            emit(segment, 0)
        }

        fun onSegment(s: UsbmuxTcp.Segment) {
            if (s.payload.isNotEmpty()) { ack += s.payload.size; inbound.add(s.payload); send(UsbmuxTcp.ACK, ByteArray(0)) }
            if (s.flags and UsbmuxTcp.FIN != 0) { ack += 1; send(UsbmuxTcp.ACK, ByteArray(0)); inbound.add(ByteArray(0)) }
        }

        override fun send(data: ByteArray) {
            if (closedHere) throw IphoneUsbException.DeviceUnavailable("fake stream closed")
            send(UsbmuxTcp.ACK, data)
            seq += data.size
        }

        private var pending = ByteArray(0)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (pending.isEmpty()) {
                if (ended) return ByteArray(0)
                val chunk = inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
                if (chunk.isEmpty()) { ended = true; return chunk }
                pending = chunk
            }
            val count = minOf(maxBytes, pending.size)
            return pending.copyOf(count).also { pending = pending.copyOfRange(count, pending.size) }
        }

        override fun close() {
            if (closedHere) return
            closedHere = true
            if (!ended) send(UsbmuxTcp.FIN or UsbmuxTcp.ACK, ByteArray(0))
        }
    }
}
