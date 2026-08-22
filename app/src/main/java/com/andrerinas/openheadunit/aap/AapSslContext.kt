package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.ssl.NoCheckTrustManager
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

class AapSslContext(keyManager: SingleKeyKeyManager): AapSsl {
    private val sslContext: SSLContext = createSslContext(keyManager)
    private lateinit var sslEngine: SSLEngine
    private lateinit var txBuffer: ByteBuffer
    private lateinit var rxBuffer: ByteBuffer
    
    @Volatile var isUserDisconnect = false

    override fun performHandshake(connection: AccessoryConnection): Boolean {
        if (prepare() < 0) return false

        // Buffer for unencrypted TLS records extracted from AAP messages.
        // We use a local queue or buffer to keep track of bytes ready for the SSLEngine.
        var pendingTlsData = ByteArray(0)
        
        // Hard cap on the entire SSL phase.
        val deadline = android.os.SystemClock.elapsedRealtime() + SSL_HANDSHAKE_TIMEOUT_MS

        while (getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED &&
                getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                AppLog.e("SSL Handshake: Timed out after ${SSL_HANDSHAKE_TIMEOUT_MS}ms")
                return false
            }

            when (getHandshakeStatus()) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // If we don't have enough data for a meaningful unwrap, read a full AAP message
                    if (pendingTlsData.isEmpty()) {
                        val messageData = readAapMessage(connection) ?: return false
                        pendingTlsData = messageData
                    }

                    rxBuffer.clear()
                    val data = ByteBuffer.wrap(pendingTlsData)
                    val result = sslEngine.unwrap(data, rxBuffer)
                    runDelegatedTasks(result, sslEngine)

                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            // Keep any unconsumed bytes (e.g. next TLS record already in the buffer).
                            pendingTlsData = if (data.hasRemaining())
                                ByteArray(data.remaining()).also { data.get(it) }
                            else ByteArray(0)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            // The current pendingTlsData doesn't contain a full TLS record.
                            // Read another AAP message and append it.
                            val nextMessage = readAapMessage(connection) ?: return false
                            pendingTlsData += nextMessage
                            AppLog.d("SSL Handshake: buffered ${pendingTlsData.size} B after underflow")
                        }
                        else -> {
                            AppLog.e("SSL Handshake: unwrap failed with status ${result.status}")
                            return false
                        }
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val handshakeData = handshakeRead()
                    val bio = Messages.createRawMessage(0, 3, 3, handshakeData)
                    if (connection.sendBlocking(bio, bio.size, 2000) < 0) {
                        AppLog.e("SSL Handshake: Send failed")
                        return false
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                }

                else -> {
                    AppLog.e("SSL Handshake: Unexpected status ${getHandshakeStatus()}")
                    return false
                }
            }
        }
        
        val sessionId = sslEngine.session?.id
        if (sessionId != null && sessionId.isNotEmpty()) {
            AppLog.i("SSL handshake complete. Session id: ${android.util.Base64.encodeToString(sessionId, android.util.Base64.NO_WRAP)}")
        } else {
            AppLog.i("SSL handshake complete. No session id (full handshake).")
        }
        return true
    }

    /**
     * Reads a single complete AAP message from the connection.
     * This ensures that we always respect AAP framing boundaries.
     */
    private fun readAapMessage(connection: AccessoryConnection): ByteArray? {
        val header = ByteArray(6)
        // Read exactly 6 bytes for the AAP header
        if (connection.recvBlocking(header, 6, 2000, true) != 6) {
            AppLog.e("SSL Handshake: Failed to read AAP header")
            return null
        }

        // AAP Header: [0]=Channel, [1]=Flags, [2..3]=Length (Big Endian), [4..5]=Type
        // The length in the header includes the 4 bytes of channel/flags/length itself? 
        // No, in Messages.kt: size + 2 is stored in bytes 2..3. 
        // So payload length = (header[2]*256 + header[3]) - 2.
        val totalLength = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payloadLength = totalLength - 2 // Minus the 2 bytes for the type field (bytes 4-5)

        if (payloadLength < 0 || payloadLength > Messages.DEF_BUFFER_LENGTH) {
            AppLog.e("SSL Handshake: Invalid AAP payload length: $payloadLength")
            return null
        }

        val payload = ByteArray(payloadLength)
        if (connection.recvBlocking(payload, payloadLength, 2000, true) != payloadLength) {
            AppLog.e("SSL Handshake: Failed to read AAP payload ($payloadLength bytes)")
            return null
        }

        return payload
    }

    private fun prepare(): Int {
        // Use a consistent (host, port) key so JSSE's ClientSessionContext can find and reuse
        // the session from the previous connection.  The values are arbitrary — they are never
        // used for DNS resolution; they just serve as the cache lookup key.
        sslEngine = sslContext.createSSLEngine("android-auto", 5277).apply {
            useClientMode = true
            session.also {
                val appBufferMax = it.applicationBufferSize
                val netBufferMax = it.packetBufferSize

                txBuffer = ByteBuffer.allocateDirect(netBufferMax)
                rxBuffer = ByteBuffer.allocateDirect(Messages.DEF_BUFFER_LENGTH.coerceAtLeast(appBufferMax + 50))
                // One plaintext buffer for the whole session, sized to what rxBuffer can hold, which
                // is the ceiling on what a single unwrap can produce. See decrypt() for why it is
                // reused rather than allocated per message.
                plaintextBuffer = ByteArray(rxBuffer.capacity())
            }
        }
        sslEngine.beginHandshake()
        return 0
    }

    override fun postHandshakeReset() {
        // Clear buffers. In this implementation, the buffers are re-created for each wrap/unwrap
        // operation (implicitly by ByteBuffer.wrap), but clearing them ensures no stale data.
        txBuffer.clear()
        rxBuffer.clear()
    }

    override fun release() {
        // No-op for SSLEngine (garbage collection handles it)
    }

    private fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus {
        return sslEngine.handshakeStatus
    }

    private fun runDelegatedTasks() {
        if (sslEngine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = sslEngine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = sslEngine.delegatedTask
            }
            val hsStatus = sslEngine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    private fun handshakeRead(): ByteArray {
        txBuffer.clear()
        val result = sslEngine.wrap(emptyArray(), txBuffer)
        runDelegatedTasks(result, sslEngine)
        val resultBuffer = ByteArray(result.bytesProduced())
        txBuffer.flip()
        txBuffer.get(resultBuffer)
        return resultBuffer
    }

    private fun handshakeWrite(start: Int, length: Int, buffer: ByteArray): Int {
        rxBuffer.clear()
        val receivedHandshakeData = ByteArray(length)
        System.arraycopy(buffer, start, receivedHandshakeData, 0, length)

        val data = ByteBuffer.wrap(receivedHandshakeData)
        while (data.hasRemaining()) {
            val result = sslEngine.unwrap(data, rxBuffer)
            runDelegatedTasks(result, sslEngine)
            // Break on any non-OK status (especially BUFFER_UNDERFLOW on a partial TLS record)
            // to prevent an infinite loop. performHandshake() no longer calls this method for
            // NEED_UNWRAP — it handles fragmented records directly via pendingTlsData.
            if (result.status != SSLEngineResult.Status.OK) break
        }
        return receivedHandshakeData.size
    }

    /**
     * Plaintext of the message currently being handled, reused across messages.
     *
     * This used to be `ByteArray(result.bytesProduced())` per call, and the call is per AAP *message*
     * - so per video *fragment*, not per frame. At 50fps with two or three fragments a frame that is
     * 150-250 short-lived arrays a second, each tens of kilobytes, which is large enough to land in
     * the large-object space. It is the churn behind #839's collector running every five to ten
     * seconds, freeing 84-208 large objects a cycle and once pausing for 1.4 seconds on a unit whose
     * decoder was keeping up the whole time.
     *
     * Reusing it is only safe because every consumer of the returned array finishes with it before
     * the next message is decrypted: the poll thread reads, decrypts, dispatches and returns, all
     * synchronously, and the three paths that could have outlived that all copy first - the video
     * feed queue arraycopies into a pooled frame, the audio path arraycopies into its own pooled
     * chunk, and protobuf parsing copies. Every consumer also bounds its reads by the message's
     * `size` field rather than by `data.size`, which is what makes a buffer larger than the payload
     * safe; that was checked call site by call site, and the one place that did not - the short-payload
     * guard in AapMessageIncoming - is fixed alongside this.
     *
     * If anything is ever added that hands `AapMessage.data` to another thread without copying, this
     * is the field that makes it a corruption bug rather than a slow one.
     */
    private lateinit var plaintextBuffer: ByteArray

    /** Reused wrapper, so the per-message allocation is zero rather than one small object. */
    private val plaintextHolder = ByteArrayWithLimit(ByteArray(0), 0)

    /**
     * Print budget for the zero-produce unwrap line, in the shape [AuditReportPolicy] governs.
     *
     * A single empty unwrap is legal TLS (a record can carry only handshake or alert bytes), so a
     * handful at session start are ordinary and an unthrottled WARN would be a storm. A *run* of
     * them mid-session is the first symptom of a desynced stream, and it used to print only at
     * DEBUG, below the level reporter captures keep - so the log told that story from the
     * disconnect instead of from the cause.
     */
    private var zeroUnwrapReports = 0
    private var zeroUnwrapLastLogMs = 0L
    private var zeroUnwrapSuppressed = 0

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        // The status line is built under the lock but emitted outside it. encrypt() takes this same
        // monitor, so logging in here put the whole logging pipeline — formatting, the caller-name
        // stack walk, the file writer — between the poll thread and the send thread once per
        // decrypted packet, which at verbose is several times per video frame.
        var statusLine: String? = null
        var zeroProduceLine: String? = null
        val decrypted = synchronized(this) {
            if (!::sslEngine.isInitialized || !::rxBuffer.isInitialized || !::plaintextBuffer.isInitialized) {
                AppLog.w("SSL Decrypt: Not initialized yet")
                return null
            }
            try {
                rxBuffer.clear()
                val encrypted = ByteBuffer.wrap(buffer, start, length)
                val result = sslEngine.unwrap(encrypted, rxBuffer)
                runDelegatedTasks(result, sslEngine)

                if (AppLog.LOG_VERBOSE) {
                    statusLine = "SSL Decrypt Status: ${result.status}, Produced: ${result.bytesProduced()}, Consumed: ${result.bytesConsumed()}"
                }
                if (result.bytesProduced() == 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (AuditReportPolicy.shouldReport(zeroUnwrapReports, zeroUnwrapLastLogMs, now)) {
                        val suppressed = zeroUnwrapSuppressed
                        zeroUnwrapSuppressed = 0
                        zeroUnwrapReports++
                        zeroUnwrapLastLogMs = now
                        val suffix =
                            if (suppressed > 0) " (and $suppressed more since the last report)" else ""
                        zeroProduceLine = "SSL Decrypt: unwrap produced no application data " +
                            "(status ${result.status}, consumed ${result.bytesConsumed()} of " +
                            "$length bytes)$suffix"
                    } else {
                        zeroUnwrapSuppressed++
                    }
                }

                val produced = result.bytesProduced()
                if (produced > plaintextBuffer.size) {
                    // Cannot happen: rxBuffer is what unwrap writes into and plaintextBuffer is its
                    // capacity. Checked anyway, because silently truncating a message here would look
                    // exactly like stream corruption further down.
                    AppLog.e("SSL Decrypt: produced $produced bytes, larger than the ${plaintextBuffer.size}-byte plaintext buffer")
                    plaintextBuffer = ByteArray(produced)
                }
                rxBuffer.flip()
                rxBuffer.get(plaintextBuffer, 0, produced)
                plaintextHolder.data = plaintextBuffer
                plaintextHolder.limit = produced
                plaintextHolder
            } catch (e: Exception) {
                // Check for Magic Garbage disconnect signal from Wireless Helper
                if (length >= 16) {
                    var allFF = true
                    for (i in 0 until 16) {
                        if (buffer[start + i] != 0xFF.toByte()) {
                            allFF = false
                            break
                        }
                    }
                    if (allFF) {
                        AppLog.i("SSL Decrypt: Magic Garbage detected. Marking as clean user disconnect.")
                        isUserDisconnect = true
                    }
                }
                
                if (!isUserDisconnect) {
                    AppLog.e("SSL Decrypt failed", e)
                }
                null
            }
        }
        statusLine?.let { AppLog.d(it) }
        zeroProduceLine?.let { AppLog.w(it) }
        return decrypted
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::txBuffer.isInitialized) {
                AppLog.w("SSL Encrypt: Not initialized yet")
                return null
            }
            try {
                txBuffer.clear()
                val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
                val result = sslEngine.wrap(byteBuffer, txBuffer)
                runDelegatedTasks(result, sslEngine)
                val resultBuffer = ByteArray(result.bytesProduced() + offset)
                txBuffer.flip()
                txBuffer.get(resultBuffer, offset, result.bytesProduced())
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AppLog.e("SSL Encrypt failed", e)
                return null
            }
        }
    }

    private fun runDelegatedTasks(result: SSLEngineResult, engine: SSLEngine) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = engine.delegatedTask
            }
            val hsStatus = engine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    companion object {
        // Maximum wall-clock time for the entire SSL handshake loop. Caps worst-case stall at
        // 15 s regardless of how many round-trips remain when the phone stops responding.
        private const val SSL_HANDSHAKE_TIMEOUT_MS = 15_000L

        private fun createSslContext(keyManager: SingleKeyKeyManager): SSLContext {
            val providerName = ConscryptInitializer.getProviderName()

            val sslContext = if (providerName != null) {
                try {
                    AppLog.d("Creating SSLContext with Conscrypt provider")
                    SSLContext.getInstance("TLS", providerName)
                } catch (e: Exception) {
                    AppLog.w("Failed to create SSLContext with Conscrypt, using default", e)
                    SSLContext.getInstance("TLS")
                }
            } else {
                AppLog.d("Creating SSLContext with default provider")
                SSLContext.getInstance("TLS")
            }

            return sslContext.apply {
                init(arrayOf(keyManager), arrayOf(NoCheckTrustManager()), null)
                // Keep the default session cache (size 10, timeout 86400 s) so that a
                // reconnect within the same app session can use an abbreviated handshake.
            }
        }
    }
}
