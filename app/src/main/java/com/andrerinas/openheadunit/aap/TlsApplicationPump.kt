package com.andrerinas.openheadunit.aap

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

/**
 * Drives application-phase TLS, including control-only records such as TLS 1.3 KeyUpdate.
 * Callers serialize this with all other engine operations and preserve the emitted record order.
 * AAP owns the message boundary: several TLS records may carry one AAP fragment.
 */
internal class TlsApplicationPump(private val engine: SSLEngine) {
    private val network = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val encryptedOutput = ByteArrayOutputStream()

    fun unwrap(source: ByteBuffer, destination: ByteBuffer, control: (ByteArray) -> Unit) {
        var rounds = 0
        var handshake = engine.handshakeStatus
        while (source.hasRemaining() || handshake == HandshakeStatus.NEED_WRAP ||
            handshake == HandshakeStatus.NEED_TASK) {
            if (++rounds > MAX_ROUNDS) throw SSLException("TLS application processing exceeded its progress limit")
            handshake = runTasks(handshake)
            if (handshake == HandshakeStatus.NEED_WRAP) {
                network.clear()
                val previous = handshake
                val result = engine.wrap(EMPTY.duplicate(), network)
                requireOk(result.status)
                handshake = runTasks(result.handshakeStatus)
                if (network.position() == 0 && handshake == previous) {
                    throw SSLException("TLS control wrap made no progress")
                }
                if (network.position() > 0) control(bytes(network))
                continue
            }
            if (!source.hasRemaining()) break
            val previous = handshake
            val result = engine.unwrap(source, destination)
            requireOk(result.status)
            handshake = runTasks(result.handshakeStatus)
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0 &&
                handshake == previous) {
                throw SSLException("TLS unwrap made no progress")
            }
        }
    }

    fun wrap(source: ByteBuffer): ByteArray {
        val output = encryptedOutput
        output.reset()
        var rounds = 0
        var handshake = engine.handshakeStatus
        while (source.hasRemaining() || handshake == HandshakeStatus.NEED_WRAP ||
            handshake == HandshakeStatus.NEED_TASK) {
            if (++rounds > MAX_ROUNDS) throw SSLException("TLS wrap exceeded its progress limit")
            handshake = runTasks(handshake)
            if (!source.hasRemaining() && handshake != HandshakeStatus.NEED_WRAP) break
            // Application writes cannot wait for input while holding the engine monitor. Keep the
            // session from silently dropping an AAP message when a provider requests renegotiation.
            if (handshake == HandshakeStatus.NEED_UNWRAP) {
                throw SSLException("TLS application write requires additional peer handshake data")
            }
            network.clear()
            val previous = handshake
            val result = engine.wrap(source, network)
            requireOk(result.status)
            handshake = runTasks(result.handshakeStatus)
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0 && handshake == previous) {
                throw SSLException("TLS wrap made no progress")
            }
            if (output.size() + network.position() > MAX_ENCRYPTED_BODY) {
                throw SSLException("TLS output exceeds the AAP 16-bit body length")
            }
            output.write(network.array(), 0, network.position())
        }
        return output.toByteArray()
    }

    private fun runTasks(initial: HandshakeStatus): HandshakeStatus {
        var handshake = initial
        var tasks = 0
        while (handshake == HandshakeStatus.NEED_TASK) {
            if (++tasks > MAX_ROUNDS) throw SSLException("TLS delegated tasks did not settle")
            val task = engine.delegatedTask ?: throw SSLException("TLS requested a missing delegated task")
            task.run()
            handshake = engine.handshakeStatus
        }
        return handshake
    }

    private fun requireOk(status: Status) {
        if (status != Status.OK) throw SSLException("TLS application operation returned $status")
    }

    private fun bytes(buffer: ByteBuffer): ByteArray {
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        private val EMPTY = ByteBuffer.allocate(0)
        private const val MAX_ROUNDS = 256
        const val MAX_ENCRYPTED_BODY = 65535
    }
}
