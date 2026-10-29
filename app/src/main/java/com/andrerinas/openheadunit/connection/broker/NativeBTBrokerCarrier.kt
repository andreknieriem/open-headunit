package com.andrerinas.openheadunit.connection.broker

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink.BlinkAutoLine
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Auto handshake over the ESP32 Native BT Broker, the same role FYT BLINK plays with
 * `/dev/auto_serial`.
 *
 * The broker owns Classic RFCOMM/HFP. This carrier watches BLE events (`EVT_PHONE_LINK` ≈ JH,
 * `EVT_RFCOMM_OPEN` ≈ AA), speaks first on the open channel, and moves WPP frames as
 * `DATA_RFCOMM_TX` / `DATA_RFCOMM_RX`.
 *
 * apply_hfp: HFP is only a wake. Once the phone opens AA (and when projection is up), the
 * broker disconnects HFP so call audio is not stuck on a device with no SCO path.
 */
class NativeBTBrokerCarrier(
    private val serve: suspend (HandshakeLink) -> Unit,
    private val isRunning: () -> Boolean,
    private val isFinishedForSession: () -> Boolean,
    private val mayServeHandshake: () -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    private val ensureLink: () -> Unit,
    private val sendToPhone: (ByteArray) -> Boolean,
    private val grantCredit: (Int) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ExternalModuleCarrier {

    companion object {
        private const val POLL_MS = 200L
        private const val CREDIT_TOP_UP = 4
    }

    override val sendsWake: Boolean get() = false
    override val rearmsWithoutAndroidRadio: Boolean get() = true

    @Volatile private var stopped = false
    @Volatile private var carrierJob: Job? = null
    @Volatile private var phoneAddress: String? = null
    private val channelOpenedAt = AtomicLong(0L)
    private var current: FrameStream? = null
    private var attempts = 0
    private var framesIn = 0
    private var wakeHintLogged = false

    /**
     * Local GATT write failure or broker [NativeBTBrokerProtocol.EVT_ERROR].
     * Ends the handshake stream so both sides can restart rather than continue with gaps.
     */
    fun onTransportError(reason: String) {
        AppLog.w("NativeAA: [Broker] transport error ($reason) — ending the handshake stream.")
        channelOpenedAt.set(0L)
        current?.close()
        current = null
    }

    /** BLE frames from [NativeBTBrokerSession]. Safe from the GATT callback thread. */
    fun onFrame(frame: NativeBTBrokerProtocol.Frame) {
        when (frame.type) {
            NativeBTBrokerProtocol.EVT_PHONE_LINK -> {
                if (frame.payload.size >= 6) {
                    phoneAddress = frame.payload.take(6).joinToString(":") { b ->
                        "%02X".format(b.toInt() and 0xFF)
                    }
                    AppLog.i("NativeAA: [Broker] hands-free link up to $phoneAddress.")
                }
            }
            NativeBTBrokerProtocol.EVT_RFCOMM_OPEN -> {
                AppLog.i("NativeAA: [Broker] the phone opened Android Auto on the broker.")
                val live = current
                if (live != null && !live.closed) {
                    // Duplicate OPEN while WPP is in flight — keep the stream.
                    channelOpenedAt.set(now())
                    grantCredit(CREDIT_TOP_UP)
                    onPhoneEvidence()
                    return
                }
                // Create the stream before granting credit so early WPP bytes are not discarded.
                current?.close()
                current = FrameStream(sendToPhone)
                channelOpenedAt.set(now())
                grantCredit(CREDIT_TOP_UP)
                onPhoneEvidence()
            }
            NativeBTBrokerProtocol.EVT_RFCOMM_CLOSE -> {
                AppLog.i("NativeAA: [Broker] the phone closed the Android Auto channel.")
                channelOpenedAt.set(0L)
                current?.close()
                current = null
            }
            NativeBTBrokerProtocol.EVT_ERROR -> {
                val code = if (frame.payload.size >= 2) {
                    (frame.payload[0].toInt() and 0xFF) or (frame.payload[1].toInt() and 0xFF shl 8)
                } else {
                    -1
                }
                onTransportError("broker EVT_ERROR code=$code")
            }
            NativeBTBrokerProtocol.DATA_RFCOMM_RX -> {
                framesIn++
                val live = current
                if (live != null && !live.closed) {
                    grantCredit(1)
                    live.deliver(frame.payload)
                    return
                }
                val reply = BlinkAutoLine.idleReply(frame.payload)
                if (reply != null) {
                    grantCredit(1)
                    if (!sendToPhone(reply)) {
                        AppLog.w("NativeAA: [Broker] idle reply failed")
                    }
                } else {
                    // Do not return credit for discarded bytes — that would ask for more loss.
                    AppLog.d(
                        "NativeAA: [Broker] [RX] unowned frame " +
                            frame.payload.joinToString("") { "%02X".format(it) }
                    )
                }
            }
            else -> Unit
        }
    }

    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        AppLog.i("NativeAA: [Broker] carrier listening — RFCOMM is relayed over BLE write/indicate.")
        while (keepRunning()) {
            ensureLink()
            if (channelOpenedAt.get() != 0L && isFinishedForSession()) {
                channelOpenedAt.set(0L)
                // Drop the early stream so idle replies can own the channel again.
                current?.close()
                current = null
                AppLog.i(
                    "NativeAA: [Broker] the phone reopened Android Auto while a session is up; " +
                        "not starting another handshake."
                )
            } else if (channelOpenedAt.get() != 0L && mayServeHandshake()) {
                if (channelOpenedAt.getAndSet(0L) != 0L) serveOnce()
            } else {
                delay(POLL_MS)
            }
        }
        AppLog.i(
            "NativeAA: [Broker] carrier stopped after $attempts handshake(s), " +
                "$framesIn frame(s) from the phone."
        )
    }

    private suspend fun serveOnce() {
        attempts++
        val stream = current?.takeIf { !it.closed } ?: FrameStream(sendToPhone).also { current = it }
        AppLog.i(
            "NativeAA: [Broker] the phone${phoneAddress?.let { " ($it)" } ?: ""} opened Android Auto " +
                "on the broker — handshake #$attempts."
        )
        grantCredit(CREDIT_TOP_UP)
        try {
            serve(BrokerLink(stream, phoneAddress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: [Broker] handshake ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            stream.close()
            if (current === stream) current = null
            wakeHintLogged = false
        }
    }

    override fun requestWake(userAsked: Boolean) {
        AppLog.i(
            "NativeAA: [Broker] intentional wake — HFP_ON then AA_ENABLE; " +
                "the phone opens Android Auto, then HFP_OFF returns calls to the phone."
        )
        // ensureLink alone only refreshes BLE/credentials and must not wake HFP after projection.
        NativeBTBrokerSession.requestAaWake()
    }

    override fun close() {
        stopped = true
        current?.close()
        current = null
    }

    private fun keepRunning(): Boolean =
        !stopped && isRunning() && carrierJob?.isActive != false

    private class BrokerLink(
        private val stream: FrameStream,
        override val peerAddress: String?,
    ) : HandshakeLink {
        override val input: InputStream get() = stream.input
        override val output: OutputStream get() = stream.output
        override val peerName: String? = null
        override val radioLabel: String = "Bluetooth relay (BLE RFCOMM)"
        override val persistPeerForAutoStart: Boolean = false
        override val peerReportedPresent: Boolean = true
        override val retransmitsWhileSilent: Boolean = false

        override fun close() {
            stream.close()
        }
    }

    /**
     * One handshake's view of the BLE RFCOMM relay. Closing ends only this view; the BLE session
     * stays up for pings and the next channel.
     */
    private class FrameStream(private val sendFrame: (ByteArray) -> Boolean) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var head: ByteArray? = null
        private var headOffset = 0

        @Volatile
        var closed = false
            private set

        private val END = ByteArray(0)
        private val pendingOut = ByteArrayOutputStream()

        fun deliver(frame: ByteArray) {
            if (!closed) queue.put(frame)
        }

        fun close() {
            synchronized(pendingOut) {
                if (closed) return
                closed = true
                pendingOut.reset()
            }
            queue.put(END)
        }

        val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    val current = head
                    if (current != null && headOffset < current.size) {
                        val take = minOf(len, current.size - headOffset)
                        System.arraycopy(current, headOffset, b, off, take)
                        headOffset += take
                        return take
                    }
                    head = null
                    headOffset = 0
                    if (closed && queue.isEmpty()) return -1
                    val next = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                    if (next === END) return -1
                    head = next
                }
            }

            override fun available(): Int = (head?.size ?: 0) - headOffset
            override fun close() = this@FrameStream.close()
        }

        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the broker handshake stream is closed")
                    pendingOut.write(b)
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(pendingOut) {
                    if (closed) throw IOException("the broker handshake stream is closed")
                    pendingOut.write(b, off, len)
                }
            }

            override fun flush() {
                val bytes = synchronized(pendingOut) {
                    if (closed) throw IOException("the broker handshake stream is closed")
                    val all = pendingOut.toByteArray()
                    pendingOut.reset()
                    all
                }
                if (bytes.isEmpty()) return
                // Whole WPP frames when possible; otherwise send the buffered stream as one write.
                var at = 0
                while (bytes.size - at >= WppFraming.HEADER_SIZE) {
                    val size = WppFraming.decodePayloadSize(bytes.copyOfRange(at, at + WppFraming.HEADER_SIZE))
                    val end = at + WppFraming.HEADER_SIZE + size
                    if (end > bytes.size) break
                    if (!sendFrame(bytes.copyOfRange(at, end))) {
                        throw IOException("broker RFCOMM write failed")
                    }
                    at = end
                }
                if (at < bytes.size) {
                    if (!sendFrame(bytes.copyOfRange(at, bytes.size))) {
                        throw IOException("broker RFCOMM write failed")
                    }
                }
            }

            override fun close() = this@FrameStream.close()
        }
    }
}
