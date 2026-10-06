package com.andrerinas.openheadunit.aap

/**
 * The writer monitor orders complete batches on the wire. The engine monitor is held only while
 * draining control records and encrypting the following application message, never during I/O.
 * Thus a blocked socket cannot prevent TLS retirement or incoming record processing.
 */
internal class AapTlsWriter(
    private val ssl: AapSsl,
    private val write: (ByteArray, Int) -> Int,
) {
    private var failed = false

    @Synchronized fun send(data: ByteArray, length: Int): Boolean {
        if (failed) return false
        val (control, encrypted) = synchronized(ssl) {
            ssl.drainControlRecords() to ssl.encrypt(4, length - 4, data)
        }
        if (encrypted == null) { failed = true; return false }
        encrypted.data[0] = data[0]
        encrypted.data[1] = data[1]
        val bodySize = encrypted.limit - 4
        encrypted.data[2] = (bodySize ushr 8).toByte()
        encrypted.data[3] = bodySize.toByte()
        for (record in control) if (!sendControl(record)) return false
        return sendFrame(encrypted.data, encrypted.limit)
    }

    @Synchronized fun flushControl(): Boolean {
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
