package com.andrerinas.openheadunit.connection.wifi.modes.helper

import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [Socket] whose two halves are Nearby stream payloads that arrive independently.
 *
 * The outgoing half is ours and is attached as soon as we send our payload. The incoming half only
 * exists once the phone sends its own payload back, so a read issued before that has to wait. The
 * wait is bounded: a phone that never completes the tunnel used to park the handshake thread
 * indefinitely, and the fault surfaced minutes later as an unexplained Nearby payload failure with
 * nothing in the log connecting the two. A bounded wait names the peer as the cause at the moment
 * it becomes true.
 */
class NearbySocket : Socket() {
    private val streamLock = Any()
    @Volatile private var internalInputStream: InputStream? = null
    @Volatile private var internalOutputStream: OutputStream? = null

    private val inputLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)
    @Volatile private var retired = false

    private companion object {
        /**
         * Comfortably longer than a working handshake and shorter than the ~16 s Nearby itself takes
         * to fail the payload, so the log records why the link died rather than only that it did.
         * A phone that answers at all answers in well under a second; a measured session completes
         * SSL at 1.2-1.3 s.
         *
         * It must also stay strictly inside `AapTransport.HANDSHAKE_TIMEOUT_MS` (10 s). At 12 s it
         * sat outside that budget, so the handshake always abandoned the attempt first and the
         * explanation below -- the entire reason the wait is bounded rather than indefinite -- could
         * never be reached.
         */
        const val STREAM_WAIT_MS = 8_000L
    }

    var inputStreamWrapper: InputStream?
        get() = internalInputStream
        set(value) {
            val accepted = synchronized(streamLock) {
                if (retired) false else {
                    internalInputStream = value
                    if (value != null) inputLatch.countDown()
                    true
                }
            }
            // Payload callbacks and socket retirement run on different threads. A late payload
            // still transfers ownership here, so close it even if its caller saw an open socket.
            if (!accepted) closeStream(value, "late inbound")
        }

    var outputStreamWrapper: OutputStream?
        get() = internalOutputStream
        set(value) {
            val accepted = synchronized(streamLock) {
                if (retired) false else {
                    internalOutputStream = value
                    if (value != null) outputLatch.countDown()
                    true
                }
            }
            if (!accepted) closeStream(value, "late outbound")
        }

    override fun isConnected() = !retired
    override fun isClosed() = retired

    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()

    /**
     * Closes the two Nearby streams this socket is made of.
     *
     * The `close()` overrides further down belong to the wrapper objects handed out by
     * [getInputStream] and [getOutputStream], and `java.net.Socket.close()` does not call them --
     * it closes a file descriptor this socket does not have. Without this, both Nearby streams
     * survived every teardown that went through the socket.
     *
     * Each close is independent: the second must still run when the first throws, and a stream that
     * is already gone is not a failure worth propagating out of a teardown path.
     */
    override fun close() {
        // A Nearby stream can arrive after AAP starts reading. Retiring the attempt must wake
        // that read immediately; closing Socket's own descriptor does not release these latches.
        val streams = synchronized(streamLock) {
            if (retired) return
            retired = true
            val owned = internalInputStream to internalOutputStream
            internalInputStream = null
            internalOutputStream = null
            inputLatch.countDown()
            outputLatch.countDown()
            owned
        }
        // Underlying close may block or call back into the socket. Publish retirement first,
        // then release the monitor so late attachments can be rejected independently.
        // Socket options can initialize the inherited SocketImpl even for a Nearby tunnel.
        // Let Socket.close set its own closed state before potentially blocking payload cleanup.
        try { super.close() } finally {
            closeStream(streams.first, "inbound")
            closeStream(streams.second, "outbound")
        }
    }

    private fun closeStream(stream: java.io.Closeable?, direction: String) {
        try { stream?.close() } catch (e: Exception) {
            AppLog.d("NearbySocket: $direction stream close: ${e.message}")
        }
    }

    override fun getInputStream(): InputStream {
        AppLog.d("NearbySocket: getInputStream() called")
        return object : InputStream() {
            private fun waitForStream(): InputStream {
                if (retired) throw SocketException("Nearby tunnel closed")
                if (inputLatch.count > 0L) {
                    AppLog.i(
                        "NearbySocket: Blocking read until InputStream is AVAILABLE via Nearby Payload (up to ${STREAM_WAIT_MS}ms)..."
                    )
                }
                if (!inputLatch.await(STREAM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    AppLog.e(
                        "NearbySocket: phone never sent its half of the stream tunnel within ${STREAM_WAIT_MS}ms. " +
                                "The Nearby link is up but the phone-side helper never registered its payload — " +
                                "nothing can be read, so the handshake is abandoned here."
                    )
                    throw IOException("Nearby stream tunnel incomplete: no inbound payload from phone")
                }
                if (retired) throw SocketException("Nearby tunnel closed")
                return internalInputStream ?: throw SocketException("Nearby input stream unavailable")
            }

            override fun read(): Int {
                val b = waitForStream().read()
                return b
            }

            override fun read(b: ByteArray): Int = read(b, 0, b.size)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val readValue = waitForStream().read(b, off, len)
                return readValue
            }
            override fun available(): Int {
                if (retired) throw SocketException("Nearby tunnel closed")
                return if (inputLatch.count == 0L) internalInputStream?.available() ?: 0 else 0
            }
            override fun close() = this@NearbySocket.close()
        }
    }

    override fun getOutputStream(): OutputStream {
        AppLog.d("NearbySocket: getOutputStream() called")
        return object : OutputStream() {
            private fun waitForStream(): OutputStream {
                if (retired) throw SocketException("Nearby tunnel closed")
                if (outputLatch.count > 0L) {
                    AppLog.d("NearbySocket: Waiting for outputLatch...")
                }
                // Ours to attach, so this should never actually wait -- but it is reached from the
                // AAP send HandlerThread, and parking that thread forever costs the teardown path
                // its only chance to run.
                if (!outputLatch.await(STREAM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("Nearby stream tunnel incomplete: outbound payload never registered")
                }
                if (retired) throw SocketException("Nearby tunnel closed")
                return internalOutputStream ?: throw SocketException("Nearby output stream unavailable")
            }

            override fun write(b: Int) {
                AppLog.v("NearbySocket: writing 1 byte to pipe")
                waitForStream().write(b)
            }

            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                AppLog.v("NearbySocket: writing $len bytes to pipe")
                waitForStream().write(b, off, len)
                // Force flush since GMS Nearby Stream payloads might buffer a lot
                waitForStream().flush()
            }
            override fun flush() {
                AppLog.v("NearbySocket: flush() called")
                if (retired) throw SocketException("Nearby tunnel closed")
                if (outputLatch.count == 0L) internalOutputStream?.flush()
            }
            override fun close() = this@NearbySocket.close()
        }
    }
}
