package com.andrerinas.openheadunit.aap

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/** Consumes every TLS record of one AAP payload, preserving all produced plaintext. */
internal object TlsUnwrapLoop {
    fun decode(engine: SSLEngine, input: ByteBuffer, destination: ByteBuffer,
               tasks: (SSLEngineResult) -> Boolean): ByteBuffer {
        var output = destination
        output.clear()
        while (input.hasRemaining()) {
            val result = engine.unwrap(input, output)
            val taskCompleted = tasks(result)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    if (output.capacity() >= MAX_PLAINTEXT_BYTES) throw SSLException("AAP TLS plaintext exceeds limit")
                    val grown = ByteBuffer.allocate(minOf(MAX_PLAINTEXT_BYTES, maxOf(1, output.capacity() * 2)))
                    output.flip()
                    grown.put(output)
                    output = grown
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                    throw SSLException("Incomplete TLS record in AAP payload")
                SSLEngineResult.Status.CLOSED -> throw SSLException("TLS peer closed the session")
                SSLEngineResult.Status.OK -> {
                    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                        val next = engine.handshakeStatus
                        val canUnwrap = next == SSLEngineResult.HandshakeStatus.NEED_UNWRAP ||
                            next == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                        if (!taskCompleted || result.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_TASK || !canUnwrap)
                            throw SSLException("TLS unwrap made no progress: ${result.handshakeStatus} -> $next")
                    }
                }
                else -> throw SSLException("Unknown TLS unwrap status")
            }
        }
        return output
    }

    // AAP's encrypted payload length is an unsigned short; plaintext cannot exceed it.
    private const val MAX_PLAINTEXT_BYTES = 65535
}
