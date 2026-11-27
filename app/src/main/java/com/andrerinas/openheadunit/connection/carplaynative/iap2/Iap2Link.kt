package com.andrerinas.openheadunit.connection.carplaynative.iap2

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** The iAP2 link packet: header, payload checksum and the SYN parameters. Ported from LIVI (https://github.com/f-io/LIVI) `iap2-link/src/frame.rs`. */
object Iap2LinkFrame {
    val DETECT_MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
    const val HEADER_LEN = 9
    const val CONTROL_SYN = 0x80
    const val CONTROL_ACK = 0x40
    const val CONTROL_EAK = 0x20
    const val CONTROL_RST = 0x10
    const val SOP_HIGH = 0xFF
    const val SOP_LOW = 0x5A

    /** The byte that makes the sum of [data] in `from until to` zero. */
    fun checksum(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var sum = 0
        for (i in from until to) sum -= data[i].toInt() and 0xFF
        return sum and 0xFF
    }

    fun checksumOk(data: ByteArray, from: Int = 0, to: Int = data.size): Boolean {
        var sum = 0
        for (i in from until to) sum += data[i].toInt() and 0xFF
        return sum and 0xFF == 0
    }

    class Header(val length: Int, val control: Int, val seq: Int, val ack: Int, val sessionId: Int) {
        fun toBytes(): ByteArray {
            val out = byteArrayOf(
                SOP_HIGH.toByte(), SOP_LOW.toByte(), (length ushr 8).toByte(), length.toByte(),
                control.toByte(), seq.toByte(), ack.toByte(), sessionId.toByte(), 0,
            )
            out[8] = checksum(out, 0, 8).toByte()
            return out
        }
    }

    /** Null when the start bytes, the checksum or the length are wrong. */
    fun parseHeader(buf: ByteArray, from: Int): Header? {
        if (buf.size - from < HEADER_LEN) return null
        if ((buf[from].toInt() and 0xFF) != SOP_HIGH || (buf[from + 1].toInt() and 0xFF) != SOP_LOW) return null
        if (!checksumOk(buf, from, from + HEADER_LEN)) return null
        val length = u16(buf, from + 2)
        if (length < HEADER_LEN) return null
        return Header(length, u8(buf, from + 4), u8(buf, from + 5), u8(buf, from + 6), u8(buf, from + 7))
    }

    fun encode(out: ByteArrayOutputStream, control: Int, seq: Int, ack: Int, sessionId: Int, payload: ByteArray?) {
        val length = if (payload == null) HEADER_LEN else HEADER_LEN + payload.size + 1
        out.write(Header(length, control, seq, ack, sessionId).toBytes())
        if (payload != null) {
            out.write(payload)
            out.write(checksum(payload))
        }
    }

    data class SessionInfo(val id: Int, val kind: Int, val version: Int)

    class SyncParams(
        val maxOutstanding: Int,
        val maxPacketLen: Int,
        val retransmitTimeoutMs: Int,
        val ackTimeoutMs: Int,
        val maxRetransmits: Int,
        val maxCumulativeAcks: Int,
        val sessions: List<SessionInfo>,
    ) {
        fun toBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(VERSION)
            out.write(maxOutstanding)
            out.write(maxPacketLen ushr 8); out.write(maxPacketLen)
            out.write(retransmitTimeoutMs ushr 8); out.write(retransmitTimeoutMs)
            out.write(ackTimeoutMs ushr 8); out.write(ackTimeoutMs)
            out.write(maxRetransmits)
            out.write(maxCumulativeAcks)
            for (s in sessions) { out.write(s.id); out.write(s.kind); out.write(s.version) }
            return out.toByteArray()
        }

        /** The peer resends and acknowledges. When false the link tracks no packet. */
        val reliable: Boolean get() = maxRetransmits > 0 && retransmitTimeoutMs > 0

        val maxPayload: Int get() = maxOf(1, maxPacketLen - (HEADER_LEN + 1))

        override fun equals(other: Any?): Boolean = other is SyncParams && toBytes().contentEquals(other.toBytes())
        override fun hashCode(): Int = toBytes().contentHashCode()

        companion object {
            const val VERSION = 1
            private const val FIXED_LEN = 10

            fun parse(p: ByteArray): SyncParams? {
                if (p.size < FIXED_LEN || p[0].toInt() != VERSION) return null
                val sessions = ArrayList<SessionInfo>()
                var i = FIXED_LEN
                while (i + 3 <= p.size) {
                    sessions += SessionInfo(u8(p, i), u8(p, i + 1), u8(p, i + 2))
                    i += 3
                }
                return SyncParams(u8(p, 1), u16(p, 2), u16(p, 4), u16(p, 6), u8(p, 8), u8(p, 9), sessions)
            }
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
}

/** Why a link ended. A clean end of stream has no error. */
sealed class Iap2LinkError(val text: String) {
    object NotIap2 : Iap2LinkError("peer does not speak iAP2")
    object NoMarker : Iap2LinkError("no iAP2 marker from the peer")
    object NegotiationTimeout : Iap2LinkError("link synchronisation timed out")
    object Reset : Iap2LinkError("peer reset the link")
    object Overflow : Iap2LinkError("link buffer limit exceeded")
    class Unacknowledged(val psn: Int) : Iap2LinkError("packet $psn never acknowledged")
}

/**
 * The iAP2 link layer: SYN negotiation, sequence numbers, ACK, EAK and resend, with no I/O and no thread.
 * The caller writes [takeOutput], feeds received bytes to [feed] and calls [advanceTime] with a millisecond clock.
 * Ported from LIVI (https://github.com/f-io/LIVI) `iap2-link/src/lib.rs`; the output and event caps follow
 * DiPlay (https://github.com/shihabal3amri/DiPlay) `Iap2LinkEngine.kt`.
 */
class Iap2Link(config: Config = Config()) {
    /** What this side advertises. The rest is fixed. */
    data class Config(val maxOutstanding: Int = 4, val maxPacketLen: Int = 0xFFFF)

    enum class State { Idle, DetectIap2Support, Negotiate, Normal, Dead }

    sealed class Event {
        class Control(val bytes: ByteArray) : Event()
        class FileTransfer(val bytes: ByteArray) : Event()
        class Dead(val error: Iap2LinkError?) : Event()
    }

    private class InFlight(val psn: Int, val sessionId: Int, val data: ByteArray, var deadline: Long, var retries: Int)
    private class Outgoing(val sessionId: Int, val data: ByteArray)
    private class Received(val psn: Int, val sessionId: Int, val data: ByteArray)
    private class Parsed(val consumed: Int, val header: Iap2LinkFrame.Header?, val payload: ByteArray?)

    var state = State.Idle
        private set

    val localParams = Iap2LinkFrame.SyncParams(
        maxOutstanding = config.maxOutstanding,
        maxPacketLen = config.maxPacketLen,
        retransmitTimeoutMs = RETRANSMIT_TIMEOUT_MS,
        ackTimeoutMs = ACK_TIMEOUT_MS,
        maxRetransmits = MAX_RETRANSMITS,
        maxCumulativeAcks = MAX_CUMULATIVE_ACKS,
        sessions = listOf(
            Iap2LinkFrame.SessionInfo(CONTROL_SESSION_ID, 0, 1),
            Iap2LinkFrame.SessionInfo(EA_SESSION_ID, 2, 1),
            Iap2LinkFrame.SessionInfo(FILE_TRANSFER_SESSION_ID, 1, 2),
        ),
    )

    var linkParams: Iap2LinkFrame.SyncParams = localParams
        private set

    private var markerMode = true
    private var rx = ByteArray(0)

    private var txPsn = 0
    private var txAcked = 0
    private val inFlight = ArrayList<InFlight>()
    private val queue = ArrayDeque<Outgoing>()
    private var queuedBytes = 0

    private var rxPsn = 0
    private var rxSynced = false
    private val rxPending = ArrayList<Received>()
    private var rxUnacked = 0
    private var ackAt: Long? = null

    private var detectAt: Long? = null
    private var detectTries = 0
    private var synAt: Long? = null
    private var synTries = 0

    private val output = ByteArrayOutputStream()
    private val events = ArrayDeque<Event>()

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (events.isEmpty()) null else events.removeFirst()

    /** The next time [advanceTime] has work, or null. */
    fun nextDeadline(): Long? {
        var best: Long? = null
        for (t in listOf(detectAt, synAt, ackAt) + inFlight.map { it.deadline }) {
            if (t != null && (best == null || t < best)) best = t
        }
        return best
    }

    /** [initiateNegotiate] is true on the wired link: send the marker and the SYN at once. */
    fun start(initiateNegotiate: Boolean, now: Long) {
        if (state != State.Idle) return
        state = State.DetectIap2Support
        emit(Iap2LinkFrame.DETECT_MARKER)
        detectAt = now + DETECT_INTERVAL_MS
        if (initiateNegotiate) {
            markerMode = false
            enterNegotiate(now)
        }
    }

    fun feedEof() = die(null)

    fun feed(data: ByteArray, now: Long) {
        if (state == State.Dead) return
        rx += data
        var pos = 0
        if (markerMode) {
            val marker = Iap2LinkFrame.DETECT_MARKER
            if (rx.size < marker.size) return
            if (!marker.indices.all { rx[it] == marker[it] }) {
                die(Iap2LinkError.NotIap2)
                return
            }
            pos = marker.size
            markerMode = false
            enterNegotiate(now)
        }
        while (true) {
            val parsed = nextFrame(rx, pos) ?: break
            pos += parsed.consumed
            val header = parsed.header ?: continue
            process(header, parsed.payload, now)
            if (state == State.Dead) {
                rx = ByteArray(0)
                return
            }
        }
        rx = rx.copyOfRange(pos, rx.size)
    }

    fun advanceTime(now: Long) {
        if (state == State.Dead) return
        if (detectAt.let { it != null && it <= now }) {
            if (detectTries >= DETECT_TRIES) {
                die(Iap2LinkError.NoMarker)
                return
            }
            detectTries++
            emit(Iap2LinkFrame.DETECT_MARKER)
            detectAt = now + DETECT_INTERVAL_MS
        }
        if (synAt.let { it != null && it <= now }) {
            if (synTries >= SYN_TRIES) {
                die(Iap2LinkError.NegotiationTimeout)
                return
            }
            synTries++
            sendSyn(now)
        }
        if (ackAt.let { it != null && it <= now }) sendAck()
        retransmit(now) { it.deadline <= now }
    }

    /** Queues [data] for [sessionId]. A payload over the peer's limit goes out in several packets. */
    fun send(sessionId: Int, data: ByteArray, now: Long) {
        if (state == State.Dead || data.isEmpty()) return
        if (queuedBytes + data.size > MAX_QUEUED_BYTES) {
            overflow()
            return
        }
        queue.addLast(Outgoing(sessionId, data))
        queuedBytes += data.size
        flush(now)
    }

    private fun emit(bytes: ByteArray) {
        if (state == State.Dead) return
        if (output.size() + bytes.size > MAX_OUTPUT_BYTES) {
            overflow()
            return
        }
        output.write(bytes)
    }

    private fun emitFrame(control: Int, seq: Int, ack: Int, sessionId: Int, payload: ByteArray?) {
        val frame = ByteArrayOutputStream()
        Iap2LinkFrame.encode(frame, control, seq, ack, sessionId, payload)
        emit(frame.toByteArray())
    }

    private fun overflow() {
        output.reset()
        die(Iap2LinkError.Overflow)
    }

    private fun enterNegotiate(now: Long) {
        state = State.Negotiate
        detectAt = null
        sendSyn(now)
    }

    private fun sendSyn(now: Long) {
        emitFrame(Iap2LinkFrame.CONTROL_SYN, txPsn, rxPsn, 0, localParams.toBytes())
        synAt = now + SYN_INTERVAL_MS
    }

    private fun sendAck() {
        emitFrame(Iap2LinkFrame.CONTROL_ACK, txPsn, rxPsn, 0, null)
        ackedNow()
    }

    private fun ackedNow() {
        rxUnacked = 0
        ackAt = null
    }

    private fun die(error: Iap2LinkError?) {
        if (state == State.Dead) return
        state = State.Dead
        detectAt = null
        synAt = null
        ackAt = null
        inFlight.clear()
        queue.clear()
        queuedBytes = 0
        events.addLast(Event.Dead(error))
    }

    private fun push(event: Event) {
        if (state == State.Dead) return
        if (events.size >= MAX_EVENTS) {
            die(Iap2LinkError.Overflow)
            return
        }
        events.addLast(event)
    }

    private fun flush(now: Long) {
        if (state != State.Normal) return
        val window = linkParams.maxOutstanding
        val maxPayload = linkParams.maxPayload
        while (ahead(txAcked, txPsn) < window && state == State.Normal) {
            val item = queue.pollFirst() ?: break
            queuedBytes -= item.data.size
            var data = item.data
            if (data.size > maxPayload) {
                val rest = data.copyOfRange(maxPayload, data.size)
                data = data.copyOfRange(0, maxPayload)
                queue.addFirst(Outgoing(item.sessionId, rest))
                queuedBytes += rest.size
            }
            transmit(item.sessionId, data, now)
        }
    }

    private fun transmit(sessionId: Int, data: ByteArray, now: Long) {
        txPsn = (txPsn + 1) and 0xFF
        emitFrame(Iap2LinkFrame.CONTROL_ACK, txPsn, rxPsn, sessionId, data)
        ackedNow()
        if (linkParams.reliable) {
            inFlight += InFlight(txPsn, sessionId, data, now + linkParams.retransmitTimeoutMs, 0)
        } else {
            txAcked = txPsn
        }
    }

    private fun retransmit(now: Long, due: (InFlight) -> Boolean) {
        var resent = false
        var failed = -1
        for (p in inFlight) {
            if (!due(p)) continue
            if (p.retries >= linkParams.maxRetransmits) {
                failed = p.psn
                break
            }
            p.retries++
            p.deadline = now + linkParams.retransmitTimeoutMs
            emitFrame(Iap2LinkFrame.CONTROL_ACK, p.psn, rxPsn, p.sessionId, p.data)
            // An output overflow kills the link and clears this list: stop before the next step.
            if (state == State.Dead) return
            resent = true
        }
        if (failed >= 0) die(Iap2LinkError.Unacknowledged(failed)) else if (resent) ackedNow()
    }

    private fun process(header: Iap2LinkFrame.Header, payload: ByteArray?, now: Long) {
        val control = header.control
        if (control and Iap2LinkFrame.CONTROL_RST != 0) {
            die(Iap2LinkError.Reset)
            return
        }
        if (control and Iap2LinkFrame.CONTROL_SYN != 0) {
            val params = payload?.let { Iap2LinkFrame.SyncParams.parse(it) }
            if (params != null) handleSyn(params, header.seq)
            if (control and Iap2LinkFrame.CONTROL_ACK != 0) handleAck(header.ack, now)
            return
        }
        if (control and Iap2LinkFrame.CONTROL_ACK != 0) handleAck(header.ack, now)
        if (control and Iap2LinkFrame.CONTROL_EAK != 0) {
            if (payload != null && state == State.Normal) {
                val missing = payload.map { it.toInt() and 0xFF }
                retransmit(now) { it.psn in missing }
            }
            return
        }
        if (payload != null && state == State.Normal) handleData(header.seq, header.sessionId, payload, now)
    }

    private fun handleSyn(peer: Iap2LinkFrame.SyncParams, psn: Int) {
        // In Normal the peer may still wait for our ACK of its SYN: answer a repeat, change nothing.
        val adopt = when {
            state == State.Negotiate -> true
            state == State.Normal && !rxSynced -> true
            state == State.Normal && psn == rxPsn -> false
            else -> return
        }
        if (adopt) {
            linkParams = Iap2LinkFrame.SyncParams(
                peer.maxOutstanding.coerceIn(1, PSN_HALF - 1), peer.maxPacketLen, peer.retransmitTimeoutMs,
                peer.ackTimeoutMs, peer.maxRetransmits, peer.maxCumulativeAcks, peer.sessions,
            )
            rxPsn = psn
            rxSynced = true
        }
        sendAck()
    }

    private fun handleAck(ack: Int, now: Long) {
        if (state == State.Negotiate && ack == txPsn) {
            state = State.Normal
            synAt = null
            flush(now)
        } else if (state == State.Normal) {
            val acked = ahead(txAcked, ack)
            if (acked == 0 || acked > ahead(txAcked, txPsn)) return
            txAcked = ack
            repeat(minOf(acked, inFlight.size)) { inFlight.removeAt(0) }
            flush(now)
        }
    }

    private fun handleData(psn: Int, sessionId: Int, data: ByteArray, now: Long) {
        if (!rxSynced) {
            rxPsn = (psn - 1) and 0xFF
            rxSynced = true
        }
        val distance = ahead(rxPsn, psn)
        if (distance == 0 || distance >= PSN_HALF || distance > linkParams.maxOutstanding) {
            sendAck()
            return
        }
        if (distance > 1) {
            if (rxPending.any { it.psn == psn }) return
            val newest = rxPending.maxOfOrNull { ahead(rxPsn, it.psn) } ?: 0
            rxPending += Received(psn, sessionId, data)
            // Only a newly opened gap is reported: every EAK costs the sender a retry.
            if (distance > newest + 1) sendEak(psn)
            return
        }
        deliver(sessionId, data)
        rxPsn = psn
        rxUnacked = minOf(rxUnacked + 1, 0xFF)
        while (true) {
            val next = rxPending.firstOrNull { it.psn == ((rxPsn + 1) and 0xFF) } ?: break
            rxPending.remove(next)
            rxPsn = next.psn
            rxUnacked = minOf(rxUnacked + 1, 0xFF)
            deliver(next.sessionId, next.data)
        }
        val base = rxPsn
        rxPending.removeAll { ahead(base, it.psn) !in 1 until PSN_HALF }

        if (linkParams.maxCumulativeAcks == 0) return
        if (rxUnacked >= linkParams.maxCumulativeAcks || linkParams.ackTimeoutMs == 0) {
            sendAck()
        } else if (ackAt == null) {
            ackAt = now + linkParams.ackTimeoutMs
        }
    }

    private fun sendEak(newest: Int) {
        val missing = (1 until ahead(rxPsn, newest))
            .map { (rxPsn + it) and 0xFF }
            .filter { psn -> rxPending.none { it.psn == psn } }
            .map { it.toByte() }
            .toByteArray()
        emitFrame(Iap2LinkFrame.CONTROL_ACK or Iap2LinkFrame.CONTROL_EAK, txPsn, rxPsn, 0, missing)
        ackedNow()
    }

    private fun deliver(sessionId: Int, data: ByteArray) {
        when (sessionId) {
            CONTROL_SESSION_ID -> push(Event.Control(data))
            FILE_TRANSFER_SESSION_ID -> push(Event.FileTransfer(data))
        }
    }

    companion object {
        const val CONTROL_SESSION_ID = 10
        const val EA_SESSION_ID = 11
        const val FILE_TRANSFER_SESSION_ID = 12

        private const val DETECT_INTERVAL_MS = 1000L
        private const val DETECT_TRIES = 30
        private const val SYN_INTERVAL_MS = 500L
        private const val SYN_TRIES = 20
        private const val RETRANSMIT_TIMEOUT_MS = 1500
        private const val ACK_TIMEOUT_MS = 100
        private const val MAX_RETRANSMITS = 5
        private const val MAX_CUMULATIVE_ACKS = 2
        private const val PSN_HALF = 128
        private const val MAX_OUTPUT_BYTES = 1 shl 20
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val MAX_EVENTS = 256

        private fun ahead(from: Int, to: Int) = (to - from) and 0xFF

        private fun nextFrame(buf: ByteArray, from: Int): Parsed? {
            val n = buf.size - from
            if (n <= 0) return null
            var start = -1
            for (i in 0 until n - 1) {
                if ((buf[from + i].toInt() and 0xFF) == Iap2LinkFrame.SOP_HIGH &&
                    (buf[from + i + 1].toInt() and 0xFF) == Iap2LinkFrame.SOP_LOW
                ) {
                    start = i
                    break
                }
            }
            if (start < 0) {
                val keep = if ((buf[buf.size - 1].toInt() and 0xFF) == Iap2LinkFrame.SOP_HIGH) 1 else 0
                return if (n > keep) Parsed(n - keep, null, null) else null
            }
            if (start > 0) return Parsed(start, null, null)
            if (n < Iap2LinkFrame.HEADER_LEN) return null
            val header = Iap2LinkFrame.parseHeader(buf, from) ?: return Parsed(1, null, null)
            val len = header.length
            if (n < len) return null
            if (len == Iap2LinkFrame.HEADER_LEN) return Parsed(len, header, null)
            if (!Iap2LinkFrame.checksumOk(buf, from + Iap2LinkFrame.HEADER_LEN, from + len)) return Parsed(len, null, null)
            return Parsed(len, header, buf.copyOfRange(from + Iap2LinkFrame.HEADER_LEN, from + len - 1))
        }
    }
}
