package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.decoder.video.ParameterSetInspector
import java.io.ByteArrayOutputStream

/**
 * The avcC record of a screen config and the length-prefixed NAL units of a frame, as Annex B.
 * Follows xcertplay airplay/ScreenStream.kt `ScreenCodec` (https://github.com/shilapi/xcertplay).
 */
object AnnexB {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** [parameterSets] holds every SPS, then every PPS, each behind a start code. */
    class Avcc(val nalLengthSize: Int, val parameterSets: ByteArray)

    /** Returns null for a record that is cut short, has no SPS or PPS, or names a NAL length of 3 bytes. */
    fun parseAvcc(record: ByteArray): Avcc? {
        if (record.size < 7) return null
        val lengthSize = (record[4].toInt() and 3) + 1
        if (lengthSize == 3) return null
        val out = ByteArrayOutputStream()
        var at = 5

        fun readSets(count: Int): Boolean {
            repeat(count) {
                if (at + 2 > record.size) return false
                val length = ((record[at].toInt() and 0xff) shl 8) or (record[at + 1].toInt() and 0xff)
                at += 2
                if (length == 0 || at + length > record.size) return false
                out.write(START_CODE)
                out.write(record, at, length)
                at += length
            }
            return true
        }

        val spsCount = record[at++].toInt() and 0x1f
        if (spsCount == 0 || !readSets(spsCount) || at >= record.size) return null
        val ppsCount = record[at++].toInt() and 0xff
        if (ppsCount == 0 || !readSets(ppsCount)) return null
        return Avcc(lengthSize, out.toByteArray())
    }

    /** The picture size of the first SPS in [parameterSets] (start code, then the NAL). Null when it does not parse. */
    fun firstSpsSize(parameterSets: ByteArray): Pair<Int, Int>? {
        if (parameterSets.size <= START_CODE.size + 1 || (parameterSets[START_CODE.size].toInt() and 0x1f) != 7) return null
        var end = parameterSets.size
        for (i in START_CODE.size + 1..parameterSets.size - START_CODE.size) {
            if (parameterSets[i].toInt() == 0 && parameterSets[i + 1].toInt() == 0 && parameterSets[i + 2].toInt() == 0 && parameterSets[i + 3].toInt() == 1) { end = i; break }
        }
        val sps = ParameterSetInspector.parseH264Sps(parameterSets, START_CODE.size, end - START_CODE.size) ?: return null
        return sps.width to sps.height
    }

    /**
     * Swaps each length prefix for a start code. A NAL of length 0 is skipped.
     * Returns null when a length runs past the end, or [lengthSize] is not 1, 2 or 4.
     */
    fun convert(payload: ByteArray, lengthSize: Int): ByteArray? {
        if (lengthSize != 1 && lengthSize != 2 && lengthSize != 4) return null
        val out = ByteArrayOutputStream(payload.size + 16)
        var at = 0
        while (at < payload.size) {
            if (at + lengthSize > payload.size) return null
            var length = 0L
            for (i in 0 until lengthSize) length = (length shl 8) or (payload[at + i].toLong() and 0xff)
            at += lengthSize
            if (length > payload.size - at) return null
            if (length == 0L) continue
            out.write(START_CODE)
            out.write(payload, at, length.toInt())
            at += length.toInt()
        }
        return out.toByteArray()
    }
}
