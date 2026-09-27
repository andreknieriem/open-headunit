package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A byte pipe to the phone, through the head unit's external Bluetooth module.
 *
 * On `bttype:extra` units the module is a separate chip that Android never exposes, so
 * `BluetoothSocket` cannot reach it — the phone opens an Android Auto RFCOMM channel to the module
 * and our own radio transmits nothing. What can reach it is the vendor daemon `gocsdk_zj`, which
 * owns the module's UART and listens on `127.0.0.1:3152`. This class speaks its protocol and turns
 * the one part we need into an [InputStream] and an [OutputStream].
 *
 * **Message `0x105` is the byte channel, in both directions, and its body is the raw RFCOMM payload
 * with no protobuf around it.** In the vendor library `libzbt_rfcomm_data_send` builds a `0x105`
 * frame straight from the caller's pointer and length, and the receive dispatcher hands a `0x105`
 * body straight to the callback that `libzbt_rfcomm_data_recv_CB_init` registers; `gocsdk_zj`
 * resolves both of those symbols, so the daemon both writes and reads this id. The daemon owns the
 * Android Auto RFCOMM server on the module itself, which is why nothing has to be registered first:
 * `RequestInit` with `enable_type = 2` is the entire setup.
 *
 * Everything else on the socket is control traffic — link state, the module's identity — and goes to
 * [onControlFrame] rather than into the byte stream.
 *
 * **One reader, many writers.** The demultiplexer has no thread of its own and is driven by whoever
 * consumes it: [input] reads frames until it has bytes to return, dispatching control frames on the
 * way, and [pumpOnce] does the same thing on a deadline for a caller that wants to watch rather than
 * read. Those are two doors into the same loop, so exactly one thread may be behind them at a time.
 * Writing is different: the handshake sends from its own thread while a wake or a poll may go out
 * from another, so every frame write takes [writeLock] and reaches the socket whole. A frame
 * interleaved with another frame's bytes would desynchronise the daemon permanently.
 *
 * [bufferRfcommData] picks how received bytes are delivered, and a caller must take one or the
 * other. A transport reads them through [input], so they are buffered; a caller that only watches
 * takes them from [onRfcommData] and must not buffer, or the backlog grows to [MAX_PENDING_BYTES]
 * and kills a channel that was working perfectly.
 *
 * Deliberately shaped as streams because that is the seam the handshake already has:
 * `NativeAaHandshakeManager.sendProtobuf`/`readProtobuf` take a bare `OutputStream`/`DataInputStream`,
 * `WppFraming` never sees a socket, and `WppHandshakeSession` has no imports at all. A ZBT backend
 * therefore needs none of that changed — if any of it has to change, the seam is in the wrong place.
 */
class ZbtByteChannel(
    private val source: InputStream,
    private val sink: OutputStream,
    private val onControlFrame: (msgId: Int, body: ByteArray) -> Unit = { _, _ -> },
    private val onRfcommData: (ByteArray) -> Unit = {},
    private val onClose: Closeable? = null,
    /**
     * Whether received RFCOMM bytes are kept for [input].
     *
     * True for anything reading the stream. False for a caller that consumes [onRfcommData] and
     * never reads [input] — buffering for a reader that will never come is what
     * [MAX_PENDING_BYTES] exists to catch, and it would report a dead channel on a live one.
     */
    private val bufferRfcommData: Boolean = true
) : Closeable {

    /**
     * The daemon's port could not be opened. [verdict] keeps a refusal, which means there is no
     * daemon here, apart from a port that is held and not accepting, which on some units is only
     * a phone being linked to the module right now.
     */
    class NotConnected(
        message: String,
        cause: Throwable,
        val verdict: ZbtReachabilityPolicy.Verdict
    ) : IOException(message, cause)

    companion object {
        /** Where the daemon listens. The address is a constant in the vendor library. */
        const val HOST = "127.0.0.1"

        /**
         * The daemon's control and RFCOMM port.
         *
         * It also binds 57677, which is **not** a fallback for this one: the vendor library's second
         * connect path dials 57677 and pumps message `0x121` over it, a different byte channel for
         * the vendor's own `zj` protocol. Trying it when 3152 refuses would connect to something
         * that cannot carry Android Auto.
         */
        const val PORT = 3152

        private const val CONNECT_TIMEOUT_MS = 4000

        /**
         * Socket read timeout, and so the granularity at which a watching caller notices its
         * deadline. Short enough to stay responsive, long enough not to spin.
         */
        const val READ_TIMEOUT_MS = 3000

        /**
         * Refuse a declared body larger than this rather than allocating it.
         *
         * [ZbtFraming.decodeBodySize] rejects only negative lengths, so a desynchronised stream can
         * declare any positive 31-bit number. The largest control message this protocol carries is 65
         * bytes and an RFCOMM payload is bounded by the link MTU, so a length past a megabyte means
         * frame sync is lost — a reason to stop, not to allocate.
         */
        const val MAX_BODY_BYTES = 1 shl 20

        /**
         * Largest RFCOMM payload put in one frame.
         *
         * The btsnoop capture from this hardware negotiated an RFCOMM frame size of 990; the daemon
         * does its own segmentation below us, so this only has to stay clear of that rather than
         * match it exactly.
         */
        const val MAX_WRITE_CHUNK = 960

        /**
         * How long to keep waiting for the rest of a frame once part of it has arrived.
         *
         * Abandoning a half-read frame desynchronises the stream for everything after it, whereas
         * waiting only costs time.
         */
        private const val MID_FRAME_TIMEOUT_MS = 10_000L

        /**
         * How many unread RFCOMM bytes may pile up before the channel gives up.
         *
         * Bytes arrive whether or not anyone is reading [input], and dropping them silently would
         * corrupt a stream whose whole job is to be exact. So a backlog this large is treated as a
         * consumer that has stopped consuming — an error to report, not one to paper over. Nothing
         * legitimate comes close: this channel carries the wireless handshake, a few hundred bytes,
         * while the projection itself goes over WiFi.
         */
        const val MAX_PENDING_BYTES = 256 * 1024

        /**
         * The `id` field of our `RequestInit`.
         *
         * This protocol's convention is that field 1 repeats the header's message id, and the daemon
         * follows it in every reply. It does not enforce it, though, and `id = 1` is what was on the
         * wire in the one exchange a real daemon has ever accepted — so it stays 1. There is nothing
         * to gain by departing from a known-good message.
         */
        const val REQUEST_INIT_ID = 1

        /**
         * Open a session: connect, then send `RequestInit` for [enableType].
         *
         * @throws NotConnected if the port could not be opened, carrying the verdict that says
         *   whether that was a refusal or a daemon that is there and not accepting.
         */
        fun open(
            host: String = HOST,
            port: Int = PORT,
            enableType: Int = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO,
            onControlFrame: (msgId: Int, body: ByteArray) -> Unit = { _, _ -> },
            onRfcommData: (ByteArray) -> Unit = {},
            bufferRfcommData: Boolean = true
        ): ZbtByteChannel {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
            } catch (e: Exception) {
                try { socket.close() } catch (ignored: Exception) { /* best effort */ }
                val verdict = ZbtReachabilityPolicy.classify(e.javaClass.simpleName, answered = false)
                val what = if (verdict == ZbtReachabilityPolicy.Verdict.NOTHING_LISTENING) {
                    "no ZBT daemon on $host:$port"
                } else {
                    "the ZBT daemon on $host:$port is there but did not accept a connection"
                }
                throw NotConnected("$what: ${e.javaClass.simpleName}: ${e.message}", e, verdict)
            }
            return try {
                ZbtByteChannel(
                    source = socket.getInputStream(),
                    sink = socket.getOutputStream(),
                    onControlFrame = onControlFrame,
                    onRfcommData = onRfcommData,
                    onClose = socket,
                    bufferRfcommData = bufferRfcommData
                ).also { it.sendRequestInit(enableType) }
            } catch (e: Exception) {
                // A socket that accepted a connection and then would not take our first message is
                // not a channel. Close it here rather than handing back one nothing can use, or
                // leaking it because the failure happened between connect and the first write.
                try { socket.close() } catch (ignored: Exception) { /* best effort */ }
                throw IOException("ZBT daemon on $host:$port would not take RequestInit", e)
            }
        }
    }

    /** How one [pumpOnce] finished. */
    enum class Pump {
        /** One frame was read and dispatched. */
        FRAME,

        /** Nothing arrived within a read timeout. Ordinary; the deadline has not passed. */
        QUIET,

        /** The deadline passed, or the caller asked to stop. */
        EXPIRED,

        /** The stream closed, or frame sync was lost. The channel is finished either way. */
        ENDED
    }

    /**
     * The module's own Bluetooth address, as `InitInfo` reports it, once one has been seen.
     *
     * Worth surfacing on its own: `BluetoothAdapter.getAddress()` returns `02:00:00:00:00:00` to a
     * non-privileged app, and a masked address is what makes the phone reject wireless credentials.
     * On this hardware the daemon simply hands us the real one.
     */
    @Volatile
    var moduleMac: String? = null
        private set

    /** True once the stream has ended or desynchronised. Nothing further will arrive. */
    @Volatile
    var isFinished: Boolean = false
        private set

    /**
     * Why the channel ended, once it has.
     *
     * Every way this channel can stop — the daemon hanging up, a lost frame boundary, a body too
     * large to be real, a backlog, an exception — arrives at the reader as the same thing: [input]
     * returns -1 and [pumpOnce] returns [Pump.ENDED]. A caller deciding whether to reopen or give up
     * needs to tell a clean goodbye from a protocol failure, so the reason is kept here rather than
     * left only in the log.
     */
    @Volatile
    var closeReason: String? = null
        private set

    /** RFCOMM bytes that have arrived and not yet been read through [input]. */
    private val pending = ArrayDeque<ByteArray>()
    private var pendingOffset = 0

    /** Volatile only so [InputStream.available] can be asked from off the reading thread; every
     *  write to it happens on that one thread. */
    @Volatile
    private var pendingBytes = 0

    private val writeBuffer = ByteArrayOutputStream()

    /** Held across each whole frame — see the class KDoc on why a split frame is unrecoverable. */
    private val writeLock = Any()

    // -------------------------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------------------------

    /** Write one framed control message. */
    fun sendControl(msgId: Int, body: ByteArray) {
        val frame = ZbtFraming.encodeFrame(body, msgId)
        AppLog.i("ZbtByteChannel: [TX] id=0x${Integer.toHexString(msgId)} ${ZbtMessages.describe(msgId, body)}")
        synchronized(writeLock) {
            sink.write(frame)
            sink.flush()
        }
    }

    /** `RequestInit` — opens the session and tells the daemon which link we are interested in. */
    fun sendRequestInit(enableType: Int = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO) {
        sendControl(ZbtMessages.REQUEST_INIT, ZbtMessages.encodeRequestInit(REQUEST_INIT_ID, enableType))
    }

    /** Ask the daemon to re-send link state. Read-only: its handler ignores the body. */
    fun requestLinkInfo() {
        sendControl(ZbtMessages.LINK_INFO_REQUEST, ZbtMessages.encodeLinkInfoRequest())
    }

    /**
     * Ask the module to bring Bluetooth up for [enableType].
     *
     * The one lever here that acts on the module rather than observing it, and the module-side
     * equivalent of the wake poke — which cannot help on this hardware, because it goes out over the
     * radio the phone is ignoring. It changes state while the vendor's own client is also connected,
     * so callers should make it a deliberate act rather than part of a routine sequence.
     */
    fun requestReconnect(enableType: Int = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO) {
        sendControl(
            ZbtMessages.REQUEST_RECONN,
            ZbtMessages.encodeRequestReconn(ZbtMessages.REQUEST_RECONN, isBtEnable = 1, enableType = enableType)
        )
    }

    /** Put [len] bytes on the RFCOMM channel, as one `0x105` frame per [MAX_WRITE_CHUNK]. */
    fun sendRfcomm(bytes: ByteArray, offset: Int = 0, len: Int = bytes.size - offset) {
        synchronized(writeLock) {
            var at = offset
            val end = offset + len
            while (at < end) {
                val take = minOf(MAX_WRITE_CHUNK, end - at)
                val body = bytes.copyOfRange(at, at + take)
                sink.write(ZbtFraming.encodeFrame(body, ZbtMessages.RFCOMM_DATA))
                at += take
            }
            sink.flush()
        }
    }

    /**
     * The RFCOMM channel as an [OutputStream].
     *
     * Buffers, and turns each `flush()` into one frame, so a caller that writes a header and then a
     * body — which is what `sendProtobuf` does — puts one message on the wire rather than two. A
     * buffer that grows past [MAX_WRITE_CHUNK] goes out on its own without waiting for the flush.
     */
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            writeBuffer.write(b)
            if (writeBuffer.size() >= MAX_WRITE_CHUNK) flush()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writeBuffer.write(b, off, len)
            if (writeBuffer.size() >= MAX_WRITE_CHUNK) flush()
        }

        // Taking the frame out of the buffer and putting it on the wire is one step: another
        // thread's control frame may land before it or after it, but not inside it.
        override fun flush() {
            synchronized(writeLock) {
                if (writeBuffer.size() == 0) return
                val body = writeBuffer.toByteArray()
                writeBuffer.reset()
                sendRfcomm(body)
            }
        }

        override fun close() = this@ZbtByteChannel.close()
    }

    // -------------------------------------------------------------------------------------------
    // Receiving
    // -------------------------------------------------------------------------------------------

    /**
     * Read and dispatch at most one frame, giving up at [deadlineMs] or when [keepGoing] turns false.
     *
     * This is the whole receive loop. [input] calls it too, so a caller that only wants the byte
     * stream needs nothing else.
     */
    fun pumpOnce(deadlineMs: Long, keepGoing: () -> Boolean = { true }): Pump {
        if (isFinished) return Pump.ENDED
        try {
            val header = ByteArray(ZbtFraming.HEADER_SIZE)
            when (fill(header, deadlineMs, keepGoing)) {
                Fill.EXPIRED -> return Pump.EXPIRED
                Fill.QUIET -> return Pump.QUIET
                Fill.DESYNC -> {
                    // Part of a header is in hand and the rest never came. Returning EXPIRED here
                    // would look like an ordinary timeout and the next pump would resume in the
                    // middle of a frame, reading the tail of one header as the start of the next.
                    AppLog.w("ZbtByteChannel: [RX] a header never arrived in full — frame sync lost")
                    return finish("a frame header never arrived in full")
                }
                Fill.FILLED -> Unit
            }

            if (!ZbtFraming.hasValidMagic(header)) {
                // Either the layout is wrong or we have lost frame sync. Nothing after this point can
                // be trusted, so stop rather than log noise against a stream we are misreading.
                AppLog.e(
                    "ZbtByteChannel: [RX] bad magic 0x${Integer.toHexString(ZbtFraming.decodeMagic(header))} " +
                        "— expected 0x${Integer.toHexString(ZbtFraming.MAGIC)}. Header: " +
                        ZbtMessages.hex(header, limit = ZbtFraming.HEADER_SIZE)
                )
                return finish(
                    "bad frame magic 0x${Integer.toHexString(ZbtFraming.decodeMagic(header))} " +
                        "— either the layout is wrong or frame sync was lost"
                )
            }

            val msgId = ZbtFraming.decodeMsgId(header)
            val size = ZbtFraming.decodeBodySize(header)
            if (size < 0 || size > MAX_BODY_BYTES) {
                AppLog.e(
                    "ZbtByteChannel: [RX] refused a declared body of $size bytes — frame sync lost. " +
                        "Header: ${ZbtMessages.hex(header, limit = ZbtFraming.HEADER_SIZE)}"
                )
                return finish("a frame declared a body of $size bytes — frame sync lost")
            }

            val body = ByteArray(size)
            if (size > 0) {
                // The body follows the header as a separate write, so it may arrive in pieces — and a
                // body we cannot finish reading leaves the stream mid-frame, which is a desync rather
                // than a quiet spell. The deadline is extended without being allowed to wrap: [input]
                // passes Long.MAX_VALUE to mean "block", and adding to that would produce a deadline
                // already in the past and abandon every frame with a body.
                if (fill(body, plusSaturating(deadlineMs, MID_FRAME_TIMEOUT_MS), keepGoing) != Fill.FILLED) {
                    AppLog.w("ZbtByteChannel: [RX] a body of $size bytes never arrived in full")
                    return finish("a frame body of $size bytes never arrived in full")
                }
            }

            dispatch(msgId, body)
            if (pendingBytes > MAX_PENDING_BYTES) {
                AppLog.e(
                    "ZbtByteChannel: $pendingBytes bytes of RFCOMM data are unread — nothing is " +
                        "consuming the stream. Stopping rather than dropping bytes."
                )
                return finish("$pendingBytes bytes of RFCOMM data went unread")
            }
            return Pump.FRAME
        } catch (e: EOFException) {
            AppLog.i("ZbtByteChannel: the daemon closed the connection")
            return finish("the daemon closed the connection")
        } catch (e: Exception) {
            AppLog.w("ZbtByteChannel: read failed: ${e.javaClass.simpleName}: ${e.message}")
            return finish("read failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun plusSaturating(base: Long, add: Long): Long =
        if (base > Long.MAX_VALUE - add) Long.MAX_VALUE else base + add

    private fun dispatch(msgId: Int, body: ByteArray) {
        if (ZbtMessages.isRawBytes(msgId)) {
            if (body.isNotEmpty() && bufferRfcommData) {
                pending.addLast(body)
                pendingBytes += body.size
            }
            onRfcommData(body)
            return
        }
        if (msgId == ZbtMessages.INIT_INFO) {
            ZbtMessages.parse(body)?.string(4)?.takeIf { it.isNotEmpty() }?.let { moduleMac = it }
        }
        onControlFrame(msgId, body)
    }

    /** End the channel, recording [reason] as [closeReason] if nothing has claimed it already. */
    private fun finish(reason: String): Pump {
        if (closeReason == null) closeReason = reason
        isFinished = true
        return Pump.ENDED
    }

    /**
     * The RFCOMM channel as an [InputStream].
     *
     * Blocks until bytes arrive, driving the demultiplexer itself and dispatching control frames as
     * it goes, and returns -1 once the channel is finished. Socket read timeouts are swallowed rather
     * than surfaced: this has to behave like the `BluetoothSocket` input stream it replaces, and that
     * one blocks.
     */
    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pending.isEmpty()) {
                if (isFinished) return -1
                if (pumpOnce(Long.MAX_VALUE) == Pump.ENDED) return -1
            }
            val head = pending.first()
            val take = minOf(len, head.size - pendingOffset)
            System.arraycopy(head, pendingOffset, b, off, take)
            pendingOffset += take
            pendingBytes -= take
            if (pendingOffset >= head.size) {
                pending.removeFirst()
                pendingOffset = 0
            }
            return take
        }

        override fun available(): Int = pendingBytes

        override fun close() = this@ZbtByteChannel.close()
    }

    /**
     * How one buffer-filling attempt finished.
     *
     * [EXPIRED] and [DESYNC] are the same event seen from two sides — the caller stopped waiting —
     * and they are kept apart because only one of them is recoverable. Giving up with nothing in
     * hand leaves the stream on a frame boundary and the next attempt can start cleanly; giving up
     * part way through a frame does not.
     */
    private enum class Fill { FILLED, QUIET, EXPIRED, DESYNC }

    /**
     * Fill [buf] completely, tolerating read timeouts that land in the middle of it.
     *
     * `DataInputStream.readFully` cannot be used here. The socket timeout fires routinely — it is
     * what lets a watching caller notice its deadline — and `readFully` throws away whatever it had
     * already taken when it throws, so retrying it would resume mid-frame and desynchronise the
     * stream for the rest of the session. Accumulating instead is also what the vendor's own reader
     * does, and for the same reason: the header and the body are separate writes, so neither is
     * guaranteed to arrive whole.
     *
     * [deadlineMs] bounds the wait only while nothing has been taken. Once bytes are in hand the rest
     * of the frame is worth waiting [MID_FRAME_TIMEOUT_MS] for.
     */
    private fun fill(buf: ByteArray, deadlineMs: Long, keepGoing: () -> Boolean): Fill {
        var have = 0
        var midFrameDeadline = 0L
        while (have < buf.size) {
            if (!keepGoing()) {
                if (have == 0) return Fill.EXPIRED
                AppLog.w("ZbtByteChannel: stopped with $have of ${buf.size} bytes in hand")
                return Fill.DESYNC
            }
            if (have == 0 && System.currentTimeMillis() >= deadlineMs) return Fill.EXPIRED
            if (have > 0 && System.currentTimeMillis() >= midFrameDeadline) {
                AppLog.w("ZbtByteChannel: gave up with $have of ${buf.size} bytes — the peer stopped mid-frame")
                return Fill.DESYNC
            }
            // A statement rather than an expression, so the `continue` is plainly a jump out of the
            // loop body and not a value the try has to produce.
            var read = 0
            try {
                read = source.read(buf, have, buf.size - have)
            } catch (e: SocketTimeoutException) {
                if (have == 0) return Fill.QUIET
                continue  // mid-frame: the rest is still coming
            }
            if (read < 0) throw EOFException("stream ended with $have of ${buf.size} bytes read")
            if (have == 0 && read > 0) midFrameDeadline = System.currentTimeMillis() + MID_FRAME_TIMEOUT_MS
            have += read
        }
        return Fill.FILLED
    }

    override fun close() {
        if (closeReason == null) closeReason = "closed locally"
        isFinished = true
        // The socket goes first and without taking [writeLock]. Closing it is what interrupts a
        // reader blocked in `source.read`, which is the only way to stop one, and waiting on the
        // lock here would put that behind whichever thread is writing — including one blocked in a
        // write that only closing the socket would release.
        try { onClose?.close() } catch (e: Exception) { /* best effort */ }
        // Never flushed: a frame is only whole once its caller has finished writing it, and this is
        // usually another thread. Sending a partial frame would desynchronise the daemon, which is
        // worse than dropping it — so say what was dropped, and drop it. `size()` is safe off the
        // lock; a stale count in a log line costs nothing.
        val unsent = writeBuffer.size()
        if (unsent > 0) {
            AppLog.w("ZbtByteChannel: closed with $unsent unsent bytes buffered — they are dropped")
        }
    }
}
