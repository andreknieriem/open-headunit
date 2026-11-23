package com.andrerinas.openheadunit.thirdparty

/**
 * Removes every occurrence of one field from a protobuf message without needing its schema.
 *
 * Used to take the album art (field 4 of MediaMetaData) out of a payload before it leaves the app. A payload that does not
 * parse is not passed on at all (null): "strip what cannot be read" would leak it.
 */
object ProtoFieldStripper {

    fun removeField(payload: ByteArray, field: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(payload.size)
        var i = 0
        while (i < payload.size) {
            val start = i
            val (key, afterKey) = readVarint(payload, i) ?: return null
            i = afterKey
            val number = (key ushr 3).toInt()
            i = when ((key and 7).toInt()) {
                0 -> (readVarint(payload, i) ?: return null).second
                1 -> i + 8
                2 -> {
                    val (len, afterLen) = readVarint(payload, i) ?: return null
                    if (len < 0 || len > payload.size - afterLen) return null
                    afterLen + len.toInt()
                }
                5 -> i + 4
                else -> return null
            }
            if (i > payload.size) return null
            if (number != field) out.write(payload, start, i - start)
        }
        return out.toByteArray()
    }

    private fun readVarint(b: ByteArray, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < b.size && shift < 64) {
            val c = b[i++].toInt() and 0xFF
            result = result or ((c and 0x7F).toLong() shl shift)
            if (c and 0x80 == 0) return Pair(result, i)
            shift += 7
        }
        return null
    }
}
