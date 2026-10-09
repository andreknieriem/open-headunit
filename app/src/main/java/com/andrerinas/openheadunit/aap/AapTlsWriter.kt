package com.andrerinas.openheadunit.aap

import java.util.concurrent.atomic.AtomicReference

/**
 * The writer monitor orders complete batches on the wire. The engine monitor is held only while
 * draining control records and encrypting the following application message, never during I/O.
 * Thus a blocked socket cannot prevent TLS retirement or incoming record processing.
 */
internal class AapTlsWriter(
    private val ssl: AapSsl,
    private val write: (ByteArray, Int) -> Int,
) {
    enum class Result { SENT, NOT_READY, FAILED }
    private enum class State { WAITING, READY, RETIRED }
    private val state = AtomicReference(State.WAITING)
    private var failed = false

    val isReady: Boolean get() = state.get() == State.READY

    // AuthComplete is a plaintext AAP frame sent after the TLS handshake. Application records
    // may enter the writer only after that entire frame has reached the transport.
    fun activate(): Boolean = state.compareAndSet(State.WAITING, State.READY)

    // Retirement must not wait for a blocked socket write, and a late handshake cannot undo it.
    fun retire() { state.set(State.RETIRED) }

    @Synchronized fun send(data: ByteArray, length: Int): Result {
        if (!isReady) return Result.NOT_READY
        if (failed) return Result.FAILED
        val (control, encrypted) = synchronized(ssl) {
            ssl.drainControlRecords() to ssl.encrypt(4, length - 4, data)
        }
        if (encrypted == null) { failed = true; return Result.FAILED }
        encrypted.data[0] = data[0]
        encrypted.data[1] = data[1]
        val bodySize = encrypted.limit - 4
        encrypted.data[2] = (bodySize ushr 8).toByte()
        encrypted.data[3] = bodySize.toByte()
        for (record in control) if (!sendControl(record)) return Result.FAILED
        return if (sendFrame(encrypted.data, encrypted.limit)) Result.SENT else Result.FAILED
    }

    @Synchronized fun flushControl(): Boolean {
        if (!isReady) return true
        if (failed) return false
        val records = synchronized(ssl) { ssl.drainControlRecords() }
        for (record in records) if (!sendControl(record)) return false
        return true
    }

    private fun sendControl(record: ByteArray): Boolean {
        // Complete, encrypted control-channel frame; the TLS bytes are already encrypted and
        // have no AAP message type. The peer's TLS engine consumes them without AAP plaintext.
        val frame = ByteArray(4 + record.size)
        frame[1] = 0x0b
        frame[2] = (record.size ushr 8).toByte()
        frame[3] = record.size.toByte()
        record.copyInto(frame, 4)
        return sendFrame(frame, frame.size)
    }

    private fun sendFrame(data: ByteArray, length: Int): Boolean {
        val sent = try { write(data, length) == length } catch (_: Exception) { false }
        if (!sent) failed = true
        return sent
    }
}
