package com.andrerinas.openheadunit.connection

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
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
    private var internalInputStream: InputStream? = null
    private var internalOutputStream: OutputStream? = null

    private val inputLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)

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
            internalInputStream = value
            if (value != null) {
                com.andrerinas.openheadunit.utils.AppLog.i("NearbySocket: InputStream is now AVAILABLE. Releasing latch.")
                inputLatch.countDown()
            }
        }

    var outputStreamWrapper: OutputStream?
        get() = internalOutputStream
        set(value) {
            internalOutputStream = value
            if (value != null) outputLatch.countDown()
        }

    override fun isConnected() = true

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
        try { internalInputStream?.close() } catch (e: Exception) {
            com.andrerinas.openheadunit.utils.AppLog.d("NearbySocket: inbound stream close: ${e.message}")
        }
        try { internalOutputStream?.close() } catch (e: Exception) {
            com.andrerinas.openheadunit.utils.AppLog.d("NearbySocket: outbound stream close: ${e.message}")
        }
        super.close()
    }

    override fun getInputStream(): InputStream {
        com.andrerinas.openheadunit.utils.AppLog.d("NearbySocket: getInputStream() called")
        return object : InputStream() {
            private fun waitForStream(): InputStream {
                if (inputLatch.count > 0L) {
                    com.andrerinas.openheadunit.utils.AppLog.i(
                        "NearbySocket: Blocking read until InputStream is AVAILABLE via Nearby Payload (up to ${STREAM_WAIT_MS}ms)..."
                    )
                }
                if (!inputLatch.await(STREAM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    com.andrerinas.openheadunit.utils.AppLog.e(
                        "NearbySocket: phone never sent its half of the stream tunnel within ${STREAM_WAIT_MS}ms. " +
                                "The Nearby link is up but the phone-side helper never registered its payload — " +
                                "nothing can be read, so the handshake is abandoned here."
                    )
                    throw IOException("Nearby stream tunnel incomplete: no inbound payload from phone")
                }
                return internalInputStream!!
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
            override fun available(): Int = if (inputLatch.count == 0L) internalInputStream!!.available() else 0
            override fun close() = if (inputLatch.count == 0L) internalInputStream!!.close() else Unit
        }
    }

    override fun getOutputStream(): OutputStream {
        com.andrerinas.openheadunit.utils.AppLog.d("NearbySocket: getOutputStream() called")
        return object : OutputStream() {
            private fun waitForStream(): OutputStream {
                if (outputLatch.count > 0L) {
                    com.andrerinas.openheadunit.utils.AppLog.d("NearbySocket: Waiting for outputLatch...")
                }
                // Ours to attach, so this should never actually wait -- but it is reached from the
                // AAP send HandlerThread, and parking that thread forever costs the teardown path
                // its only chance to run.
                if (!outputLatch.await(STREAM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("Nearby stream tunnel incomplete: outbound payload never registered")
                }
                return internalOutputStream!!
            }

            override fun write(b: Int) {
                com.andrerinas.openheadunit.utils.AppLog.v("NearbySocket: writing 1 byte to pipe")
                waitForStream().write(b)
            }
            
            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                com.andrerinas.openheadunit.utils.AppLog.v("NearbySocket: writing $len bytes to pipe")
                waitForStream().write(b, off, len)
                // Force flush since GMS Nearby Stream payloads might buffer a lot
                waitForStream().flush()
            }
            override fun flush() {
                com.andrerinas.openheadunit.utils.AppLog.v("NearbySocket: flush() called")
                if (outputLatch.count == 0L) internalOutputStream!!.flush()
            }
            override fun close() = if (outputLatch.count == 0L) internalOutputStream!!.close() else Unit
        }
    }
}
