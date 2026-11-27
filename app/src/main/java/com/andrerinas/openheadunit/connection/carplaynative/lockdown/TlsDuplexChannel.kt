package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Plain bytes over one TLS engine and one owned stream. [open] finishes the handshake inside one deadline on [clock].
 * It owns [underlying]: a failed handshake and [close] both close it.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/TlsDuplexChannel.kt`.
 */
class TlsDuplexChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
    private val engine: SSLEngine,
    private val clock: () -> Long,
) : BlockingDuplexByteStream {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var encrypted = ByteArray(0)
    private var plain = ByteArray(0)
    private var closed = false
    private var inboundEnded = false
    private var failure: IphoneUsbException? = null

    override fun send(data: ByteArray) = synchronized(writeLock) {
        checkWritable()
        if (data.isEmpty()) return@synchronized
        try {
            val source = ByteBuffer.wrap(data)
            while (source.hasRemaining()) {
                val out = wrap(source)
                if (out.bytes.isNotEmpty()) underlying.send(out.bytes)
                if (out.result.status == SSLEngineResult.Status.CLOSED) throw IphoneUsbException.Protocol("TLS closed during send")
                if (out.result.bytesConsumed() == 0 && out.bytes.isEmpty()) throw IphoneUsbException.Protocol("TLS send made no progress")
            }
        } catch (e: Exception) {
            throw fail(e)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0 && timeoutMillis > 0)
        synchronized(readLock) {
            try {
                return receiveLocked(maxBytes, clock() + timeoutMillis)
            } catch (e: Exception) {
                throw fail(e)
            }
        }
    }

    private fun receiveLocked(maxBytes: Int, deadline: Long): ByteArray? {
        takePlain(maxBytes)?.let { return it }
        synchronized(stateLock) {
            failure?.let { throw it }
            if (inboundEnded) return ByteArray(0)
            if (closed) throw IphoneUsbException.DeviceUnavailable("TLS channel is closed")
        }
        var idle = 0
        while (true) {
            val out = unwrap()
            plain += out.bytes
            when (out.result.status) {
                SSLEngineResult.Status.OK -> {
                    idle = if (out.result.bytesConsumed() == 0 && out.bytes.isEmpty()) idle + 1 else 0
                    if (idle >= MAX_IDLE_STEPS) throw IphoneUsbException.Protocol("TLS engine made no progress")
                    drainHandshake(out.result.handshakeStatus)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    takePlain(maxBytes)?.let { return it }
                    val remaining = deadline - clock()
                    if (remaining <= 0) return null
                    val incoming = underlying.recv(RECEIVE_CHUNK, remaining) ?: return null
                    if (incoming.isEmpty()) throw IphoneUsbException.Protocol("TLS stream ended without close_notify")
                    appendEncrypted(incoming)
                    idle = 0
                }
                SSLEngineResult.Status.CLOSED -> {
                    val rest = takePlain(maxBytes)
                    synchronized(stateLock) { inboundEnded = true }
                    close()
                    return rest ?: ByteArray(0)
                }
                else -> throw IphoneUsbException.Protocol("TLS unwrap overflow")
            }
            takePlain(maxBytes)?.let { return it }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        try {
            synchronized(writeLock) {
                synchronized(engine) { if (!engine.isOutboundDone) engine.closeOutbound() }
                var steps = 0
                while (steps++ < MAX_CLOSE_STEPS && !synchronized(engine) { engine.isOutboundDone }) {
                    val out = wrap(ByteBuffer.allocate(0))
                    if (out.bytes.isNotEmpty()) underlying.send(out.bytes)
                    if (out.result.status == SSLEngineResult.Status.CLOSED) break
                }
            }
        } catch (_: Exception) {
            // close_notify is best effort, closing the stream is final.
        } finally {
            try { underlying.close() } catch (_: Exception) { /* local state is closed */ }
        }
    }

    private fun handshake(timeoutMs: Long) {
        val deadline = clock() + timeoutMs
        var status = synchronized(engine) { engine.beginHandshake(); engine.handshakeStatus }
        var idle = 0
        while (status != SSLEngineResult.HandshakeStatus.FINISHED && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            if (clock() >= deadline) throw IphoneUsbException.TimedOut("TLS handshake timed out")
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> status = runTasks()
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = synchronized(writeLock) { wrap(ByteBuffer.allocate(0)).also { if (it.bytes.isNotEmpty()) underlying.send(it.bytes) } }
                    if (out.result.status == SSLEngineResult.Status.CLOSED) throw IphoneUsbException.Protocol("TLS closed during the handshake")
                    status = out.result.handshakeStatus
                    idle = if (out.bytes.isEmpty()) idle + 1 else 0
                }
                needsUnwrap(status) -> {
                    val out = unwrap()
                    plain += out.bytes
                    when (out.result.status) {
                        SSLEngineResult.Status.OK -> {
                            status = out.result.handshakeStatus
                            idle = if (out.result.bytesConsumed() == 0 && out.bytes.isEmpty()) idle + 1 else 0
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val remaining = deadline - clock()
                            if (remaining <= 0) throw IphoneUsbException.TimedOut("TLS handshake timed out")
                            val incoming = underlying.recv(RECEIVE_CHUNK, remaining) ?: throw IphoneUsbException.TimedOut("TLS handshake timed out")
                            if (incoming.isEmpty()) throw IphoneUsbException.Protocol("the stream ended during the TLS handshake")
                            appendEncrypted(incoming)
                            idle = 0
                        }
                        else -> throw IphoneUsbException.Protocol("TLS closed during the handshake")
                    }
                }
                else -> throw IphoneUsbException.Protocol("unsupported TLS handshake state ${status.name}")
            }
            if (idle >= MAX_IDLE_STEPS) throw IphoneUsbException.Protocol("TLS engine made no progress")
        }
    }

    /** Sends what the engine owes after a record, such as a key update. A need to read waits for the next [recv]. */
    private fun drainHandshake(first: SSLEngineResult.HandshakeStatus) {
        var status = first
        var steps = 0
        while (steps++ < MAX_CONTROL_STEPS) {
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> status = runTasks()
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = synchronized(writeLock) { wrap(ByteBuffer.allocate(0)).also { if (it.bytes.isNotEmpty()) underlying.send(it.bytes) } }
                    if (out.result.status == SSLEngineResult.Status.CLOSED) throw IphoneUsbException.Protocol("TLS closed after a record")
                    status = out.result.handshakeStatus
                }
                else -> return
            }
        }
        throw IphoneUsbException.Protocol("TLS post-handshake work did not end")
    }

    private fun runTasks(): SSLEngineResult.HandshakeStatus = synchronized(engine) {
        while (true) (engine.delegatedTask ?: break).run()
        engine.handshakeStatus
    }

    private class Outcome(val result: SSLEngineResult, val bytes: ByteArray)

    private fun wrap(source: ByteBuffer): Outcome {
        var capacity = synchronized(engine) { engine.session.packetBufferSize }.coerceAtLeast(1)
        while (true) {
            if (capacity > MAX_TLS_BUFFER) throw IphoneUsbException.Protocol("TLS packet buffer is over its limit")
            val out = ByteBuffer.allocate(capacity)
            val result = synchronized(engine) { engine.wrap(source, out) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) { capacity *= 2; continue }
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) throw IphoneUsbException.Protocol("TLS wrap underflow")
            out.flip()
            return Outcome(result, ByteArray(out.remaining()).also(out::get))
        }
    }

    private fun unwrap(): Outcome {
        var capacity = synchronized(engine) { engine.session.applicationBufferSize }.coerceAtLeast(1)
        while (true) {
            if (capacity > MAX_TLS_BUFFER) throw IphoneUsbException.Protocol("TLS application buffer is over its limit")
            val out = ByteBuffer.allocate(capacity)
            val result = synchronized(engine) { engine.unwrap(ByteBuffer.wrap(encrypted), out) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) { capacity *= 2; continue }
            encrypted = encrypted.copyOfRange(result.bytesConsumed(), encrypted.size)
            out.flip()
            return Outcome(result, ByteArray(out.remaining()).also(out::get))
        }
    }

    private fun appendEncrypted(bytes: ByteArray) {
        if (encrypted.size + bytes.size > MAX_TLS_BUFFER) throw IphoneUsbException.Protocol("TLS input is over its limit")
        encrypted += bytes
    }

    private fun takePlain(max: Int): ByteArray? {
        if (plain.isEmpty()) return null
        val count = minOf(max, plain.size)
        return plain.copyOf(count).also { plain = plain.copyOfRange(count, plain.size) }
    }

    private fun checkWritable() = synchronized(stateLock) {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("TLS channel is closed")
    }

    private fun fail(error: Exception): IphoneUsbException {
        val mapped = error as? IphoneUsbException ?: IphoneUsbException.Protocol("TLS channel failed (${error.javaClass.simpleName})")
        val closeNow = synchronized(stateLock) {
            if (failure == null) failure = mapped
            (!closed).also { closed = true }
        }
        if (closeNow) try { underlying.close() } catch (_: Exception) { /* keep the first failure */ }
        return synchronized(stateLock) { failure ?: mapped }
    }

    companion object {
        private val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")
        private const val RECEIVE_CHUNK = 16 * 1024
        private const val MAX_TLS_BUFFER = 1 shl 20
        private const val MAX_CONTROL_STEPS = 32
        private const val MAX_IDLE_STEPS = 8
        private const val MAX_CLOSE_STEPS = 4

        private fun needsUnwrap(status: SSLEngineResult.HandshakeStatus) =
            status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || status.name == "NEED_UNWRAP_AGAIN"

        /** Runs the handshake on [engine] over [underlying] and takes ownership of the stream on both outcomes. */
        fun open(underlying: BlockingDuplexByteStream, engine: SSLEngine, timeoutMs: Long, clock: () -> Long): TlsDuplexChannel {
            try {
                val supported = engine.supportedProtocols.toSet()
                val enabled = PROTOCOLS.filter(supported::contains).toTypedArray()
                if (enabled.isEmpty()) throw IphoneUsbException.Protocol("the TLS engine supports neither TLSv1.2 nor TLSv1.3")
                engine.enabledProtocols = enabled
                return TlsDuplexChannel(underlying, engine, clock).also { it.handshake(timeoutMs) }
            } catch (e: Exception) {
                try { underlying.close() } catch (_: Exception) { /* keep the handshake failure */ }
                throw e as? IphoneUsbException ?: IphoneUsbException.Protocol("TLS handshake failed (${e.javaClass.simpleName})", e)
            }
        }
    }
}
