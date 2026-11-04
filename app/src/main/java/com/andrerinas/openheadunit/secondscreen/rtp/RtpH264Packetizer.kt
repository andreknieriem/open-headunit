package com.andrerinas.openheadunit.secondscreen.rtp

import com.andrerinas.openheadunit.secondscreen.H264Units

/**
 * Splits Annex-B access units into RFC 6184 packets: single NAL, or FU-A when a NAL exceeds one
 * datagram. A keyframe without its own SPS and PPS gets the last ones in front, so a late receiver starts.
 */
class RtpH264Packetizer(
    private val send: (buf: ByteArray, off: Int, len: Int) -> Unit,
    initialSequence: Int,
    private val ssrc: Int,
) {
    var packets = 0L; private set
    var bytes = 0L; private set
    var keyframes = 0; private set
    var keyframesWithOwnParameterSets = 0; private set

    private var sequence = initialSequence and 0xFFFF
    private val datagram = ByteArray(MAX_DATAGRAM)
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun resetCounters() {
        packets = 0; bytes = 0; keyframes = 0; keyframesWithOwnParameterSets = 0
    }

    /** Sends one access unit and returns whether it held an IDR. */
    fun packetize(buf: ByteArray, off: Int, len: Int, timestamp: Int): Boolean {
        var count = 0
        var hasSps = false
        var hasPps = false
        var hasIdr = false
        H264Units.forEachNal(buf, off, len) { type, start, end ->
            count++
            when (type) {
                H264Units.NAL_SPS -> { hasSps = true; sps = buf.copyOfRange(start, end) }
                H264Units.NAL_PPS -> { hasPps = true; pps = buf.copyOfRange(start, end) }
                NAL_IDR -> hasIdr = true
            }
        }
        if (count == 0) return false
        if (hasIdr) {
            keyframes++
            if (hasSps && hasPps) keyframesWithOwnParameterSets++
            if (!hasSps && !hasPps) {
                sps?.let { emit(it, 0, it.size, timestamp, false) }
                pps?.let { emit(it, 0, it.size, timestamp, false) }
            }
        }
        var index = 0
        H264Units.forEachNal(buf, off, len) { _, start, end ->
            index++
            emit(buf, start, end, timestamp, index == count)
        }
        return hasIdr
    }

    private fun emit(src: ByteArray, start: Int, end: Int, timestamp: Int, lastNal: Boolean) {
        val size = end - start
        if (size <= 0) return
        if (size <= MAX_PAYLOAD) {
            System.arraycopy(src, start, datagram, HEADER, size)
            flush(HEADER + size, timestamp, lastNal)
            return
        }
        val header = src[start].toInt() and 0xFF
        val indicator = (header and 0xE0) or FU_A
        val type = header and 0x1F
        var pos = start + 1
        while (pos < end) {
            val n = minOf(MAX_FRAGMENT, end - pos)
            val first = pos == start + 1
            val last = pos + n == end
            datagram[HEADER] = indicator.toByte()
            datagram[HEADER + 1] = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte()
            System.arraycopy(src, pos, datagram, HEADER + 2, n)
            flush(HEADER + 2 + n, timestamp, lastNal && last)
            pos += n
        }
    }

    private fun flush(length: Int, timestamp: Int, marker: Boolean) {
        datagram[0] = 0x80.toByte()
        datagram[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        datagram[2] = (sequence shr 8).toByte()
        datagram[3] = sequence.toByte()
        for (i in 0..3) datagram[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        for (i in 0..3) datagram[8 + i] = (ssrc shr (24 - 8 * i)).toByte()
        sequence = (sequence + 1) and 0xFFFF
        packets++
        bytes += length
        send(datagram, 0, length)
    }

    internal companion object {
        const val NAL_IDR = 5
        const val FU_A = 28
        const val PAYLOAD_TYPE = 96
        const val HEADER = 12
        const val MAX_DATAGRAM = 1400
        const val MAX_PAYLOAD = MAX_DATAGRAM - HEADER
        const val MAX_FRAGMENT = MAX_PAYLOAD - 2
    }
}
