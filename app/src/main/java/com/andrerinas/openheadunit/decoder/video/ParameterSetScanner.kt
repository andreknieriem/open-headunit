package com.andrerinas.openheadunit.decoder.video

/**
 * Walks an access unit and reports parameter-set ranges only, without copying picture data.
 * Start-code search reads one byte in three: a byte that is neither 0 nor 1 rules out three offsets.
 */
internal object ParameterSetScanner {
    /** First `i` in `[from, limit - 3)` that starts `00 00 01` or `00 00 00 01`, else -1. */
    internal fun nextStartCode(buffer: ByteArray, from: Int, limit: Int): Int {
        var i = from
        val end = limit - 3
        while (i < end) {
            val c = buffer[i + 2].toInt()
            if (c > 1 || c < 0) {
                i += 3
            } else if (c == 1) {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0) return i
                i += 3
            } else {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0 && buffer[i + 3].toInt() == 1) return i
                i += 1
            }
        }
        return -1
    }

    internal inline fun forEachParameterSet(
        buffer: ByteArray, offset: Int, size: Int, hevc: Boolean, callback: (Int, Int, Int) -> Unit
    ) {
        var currentPos = offset
        val limit = offset + size
        while (currentPos < limit - 3) {
            val nalStart = nextStartCode(buffer, currentPos, limit)
            if (nalStart == -1) break
            val startCodeLen = if (buffer[nalStart + 2].toInt() == 1) 3 else 4
            val next = nextStartCode(buffer, nalStart + startCodeLen, limit)
            val nalEnd = if (next == -1) limit else next
            val header = nalStart + startCodeLen
            if (header < nalEnd) {
                val first = buffer[header].toInt() and 0xFF
                val kind = if (hevc) (first and 0x7E) shr 1 else first and 0x1F
                val parameterSet = if (hevc) kind in 32..34 else kind == 7 || kind == 8
                if (parameterSet) callback(header, nalEnd - header, kind)
            }
            currentPos = nalEnd
        }
    }
}
