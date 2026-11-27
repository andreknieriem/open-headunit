package com.andrerinas.openheadunit.connection.carplaynative.codec

/**
 * The TLV8 of the AirPlay pairing messages: values over 255 bytes split in fragments,
 * and two neighbours of one type take a 0xFF separator.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate livi-cp, `tlv8.rs`.
 */
object Tlv8 {
    private const val SEPARATOR = 0xff
    private const val MAX_FRAGMENT = 255

    class Item(val type: Int, val value: ByteArray)

    fun encode(items: List<Item>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var previous = -1
        for (item in items) {
            if (item.type == previous) {
                out.write(SEPARATOR)
                out.write(0)
            }
            var offset = 0
            do {
                val length = minOf(MAX_FRAGMENT, item.value.size - offset)
                out.write(item.type)
                out.write(length)
                out.write(item.value, offset, length)
                offset += length
            } while (offset < item.value.size)
            previous = item.type
        }
        return out.toByteArray()
    }

    /** A later item of one type replaces an earlier one, unless the earlier one ended on a full fragment. */
    fun decode(buf: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var p = 0
        var lastType = -1
        var lastLength = -1
        while (p + 2 <= buf.size) {
            val type = buf[p].toInt() and 0xff
            val length = buf[p + 1].toInt() and 0xff
            val value = buf.copyOfRange(p + 2, minOf(p + 2 + length, buf.size))
            p += 2 + length
            val previous = out[type]
            out[type] = if (type == lastType && lastLength == MAX_FRAGMENT && previous != null) previous + value else value
            lastType = type
            lastLength = length
        }
        return out
    }
}
