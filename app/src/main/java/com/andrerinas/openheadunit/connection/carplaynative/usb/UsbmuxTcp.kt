package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec.putU16
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec.putU32
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec.readU16
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxCodec.readU32

/**
 * The small TCP one usbmux connection speaks: sequence, acknowledgement and flags. It returns segments, the caller frames and sends them.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-usbmux `mux.rs` `Conn`.
 */
class UsbmuxTcp(val sourcePort: Int, val destinationPort: Int) {

    /** What one received segment asks for: payload for the reader, segments to send, and the state it changed. */
    class Reaction(val data: ByteArray?, val replies: List<ByteArray>, val connected: Boolean, val reset: Boolean, val closed: Boolean)

    class Segment(val sourcePort: Int, val destinationPort: Int, val sequence: Int, val flags: Int, val payload: ByteArray)

    private var sequence = 0
    private var acknowledgement = 0

    var connected = false
        private set
    var closed = false
        private set

    fun syn(): ByteArray = segment(SYN, EMPTY)

    fun onSegment(flags: Int, seq: Int, payload: ByteArray): Reaction {
        val replies = ArrayList<ByteArray>(2)
        if (flags and SYN != 0 && flags and ACK != 0) {
            sequence += 1
            acknowledgement = seq + 1
            replies.add(segment(ACK, EMPTY))
            connected = !closed
            return Reaction(null, replies, connected, reset = false, closed = closed)
        }
        if (flags and RST != 0) {
            closed = true
            return Reaction(null, replies, connected = false, reset = true, closed = true)
        }
        var data: ByteArray? = null
        if (payload.isNotEmpty() && !closed) {
            acknowledgement += payload.size
            data = payload
            replies.add(segment(ACK, EMPTY))
        }
        if (flags and FIN != 0 && !closed) {
            acknowledgement += 1
            replies.add(segment(ACK, EMPTY))
            closed = true
        }
        return Reaction(data, replies, connected, reset = false, closed = closed)
    }

    /** One ACK segment per chunk of at most [MAX_PAYLOAD] bytes. The sequence moves on with each. */
    fun data(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>((bytes.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD)
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(MAX_PAYLOAD, bytes.size - offset)
            out.add(segment(ACK, bytes.copyOfRange(offset, offset + count)))
            sequence += count
            offset += count
        }
        return out
    }

    /** The FIN once, then null. */
    fun fin(): ByteArray? {
        if (closed) return null
        closed = true
        return segment(FIN or ACK, EMPTY)
    }

    private fun segment(flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        putU16(out, 0, sourcePort)
        putU16(out, 2, destinationPort)
        putU32(out, 4, sequence)
        putU32(out, 8, acknowledgement)
        out[12] = (HEADER_BYTES / 4 shl 4).toByte()
        out[13] = flags.toByte()
        putU16(out, 14, WINDOW)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val ACK = 0x10
        const val MAX_PAYLOAD = 16384

        private const val HEADER_BYTES = 20
        private const val WINDOW = 512
        private val EMPTY = ByteArray(0)

        /** Reads one received mux TCP payload. The data offset says where the payload starts. */
        fun parse(tcp: ByteArray): Segment {
            if (tcp.size < HEADER_BYTES) throw UsbmuxFramingException("USBMUX TCP frame is shorter than its header")
            val headerBytes = ((tcp[12].toInt() ushr 4) and 0x0f) * 4
            if (headerBytes < HEADER_BYTES || headerBytes > tcp.size) throw UsbmuxFramingException("Invalid USBMUX TCP header length")
            return Segment(readU16(tcp, 0), readU16(tcp, 2), readU32(tcp, 4), tcp[13].toInt() and 0xff, tcp.copyOfRange(headerBytes, tcp.size))
        }
    }
}
