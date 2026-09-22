package com.andrerinas.openheadunit.secondscreen

import com.andrerinas.openheadunit.decoder.video.VideoKeyframeScanner

/**
 * What a forwarded H.264 access unit carries, so a receiver that joins late can start on a picture.
 *
 * The second sink is only ever announced as H.264, so there is no H.265 half.
 */
object H264Units {

    const val NAL_SPS = 7
    const val NAL_PPS = 8

    fun isKeyframe(buf: ByteArray, offset: Int, length: Int): Boolean =
        VideoKeyframeScanner.containsKeyframe(buf, offset, length, isHevc = false)

    /**
     * The SPS and PPS in this unit, each with a 4-byte start code, or null when it carries neither.
     * A new receiver is sent these first, since the phone only repeats them with a new stream.
     */
    fun parameterSets(buf: ByteArray, offset: Int, length: Int): ByteArray? {
        val end = (offset + length).coerceAtMost(buf.size)
        val out = java.io.ByteArrayOutputStream()
        var nal = nextNal(buf, offset, end)
        while (nal != null) {
            val (payloadStart, _) = nal
            val following = nextNal(buf, payloadStart, end)
            val payloadEnd = following?.let { startCodeBefore(buf, it.first) } ?: end
            val type = buf[payloadStart].toInt() and 0x1F
            if (type == NAL_SPS || type == NAL_PPS) {
                out.write(START_CODE)
                out.write(buf, payloadStart, payloadEnd - payloadStart)
            }
            nal = following
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** The next NAL after [from]: the index of its header byte, and the start code's length. */
    private fun nextNal(buf: ByteArray, from: Int, end: Int): Pair<Int, Int>? {
        var i = from
        while (i + 3 <= end) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                if (buf[i + 2].toInt() == 1 && i + 3 < end) return (i + 3) to 3
                if (i + 4 < end && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1) return (i + 4) to 4
            }
            i++
        }
        return null
    }

    /** Where the start code in front of the NAL header at [headerPos] begins. */
    private fun startCodeBefore(buf: ByteArray, headerPos: Int): Int =
        if (headerPos >= 4 && buf[headerPos - 4].toInt() == 0 && buf[headerPos - 3].toInt() == 0 &&
            buf[headerPos - 2].toInt() == 0) headerPos - 4 else headerPos - 3
}
