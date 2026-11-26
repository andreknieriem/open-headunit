package com.andrerinas.openheadunit.connection.carplaynative.codec

import java.io.ByteArrayOutputStream

/**
 * Apple binary property list (bplist00) for the AirPlay control channel.
 * Output is byte for byte what LIVI writes: keys then values, in insertion order.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate livi-cp, and xcertplay (https://github.com/shilapi/xcertplay).
 */
object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
    private const val TRAILER = 32
    private const val MIN_CALLS = 65_536L

    fun decode(buf: ByteArray): PlistValue {
        if (buf.size < MAGIC.size + TRAILER || !buf.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw PlistException("bplist: bad magic or too short")
        }
        val t = buf.size - TRAILER
        val offsetSize = buf[t + 6].toInt() and 0xff
        val refSize = buf[t + 7].toInt() and 0xff
        if (offsetSize !in 1..8 || refSize !in 1..8) throw PlistException("bplist: bad trailer sizes")
        val numObjects = readBe(buf, t + 8, 8)
        val top = readBe(buf, t + 16, 8)
        val table = readBe(buf, t + 24, 8)
        if (numObjects < 0 || numObjects > buf.size / offsetSize) throw PlistException("bplist: offset table out of range")
        val reader = Reader(buf, refSize, LongArray(numObjects.toInt()), maxOf(MIN_CALLS, numObjects * 4))
        for (i in reader.offsets.indices) reader.offsets[i] = reader.sized(table + i.toLong() * offsetSize, offsetSize)
        return reader.obj(top, 0)
    }

    // Shared references can make the walk exponential, so [callsLeft] caps the objects one decode reads.
    private class Reader(val buf: ByteArray, val refSize: Int, val offsets: LongArray, var callsLeft: Long) {
        // Compare without addition: a 64-bit position plus a size can wrap.
        fun sized(at: Long, size: Int): Long {
            if (at < 0 || at > buf.size || size > buf.size - at) throw PlistException("bplist: out of bounds")
            return readBe(buf, at.toInt(), size)
        }

        fun slice(at: Long, len: Long): ByteArray {
            if (at < 0 || len < 0 || at > buf.size || len > buf.size - at) throw PlistException("bplist: out of bounds")
            return buf.copyOfRange(at.toInt(), (at + len).toInt())
        }

        fun obj(index: Long, depth: Int): PlistValue {
            if (depth > PlistValue.MAX_DEPTH) throw PlistException("bplist: nested too deep")
            if (--callsLeft < 0) throw PlistException("bplist: too many object reads")
            if (index < 0 || index >= offsets.size) throw PlistException("bplist: bad object reference")
            var p = offsets[index.toInt()]
            val marker = sized(p, 1).toInt()
            val kind = marker ushr 4
            val nib = marker and 0x0f
            p++

            // A count of 15 or more sits in a following integer object. Moves p past it.
            fun count(): Long {
                if (nib != 0x0f) return nib.toLong()
                val size = 1 shl (sized(p, 1).toInt() and 0x0f)
                if (size > 8) throw PlistException("bplist: count too large")
                val c = sized(p + 1, size)
                // A count past the buffer cannot be real, and the cap keeps n * refSize from wrapping.
                if (c < 0 || c > buf.size) throw PlistException("bplist: count out of range")
                p += 1 + size
                return c
            }

            return when (kind) {
                0x0 -> when (nib) {
                    0x08 -> PlistValue.Bool(false)
                    0x09 -> PlistValue.Bool(true)
                    else -> throw PlistException("bplist: unsupported primitive 0x0$nib")
                }
                0x1 -> when {
                    nib <= 3 -> PlistValue.Integer(sized(p, 1 shl nib))
                    // An unsigned value of 2^63 or more comes as 16 bytes: keep the low 64 bits, as LIVI does.
                    nib == 4 && sized(p, 8) == 0L -> PlistValue.Integer(sized(p + 8, 8))
                    else -> throw PlistException("bplist: integer too large")
                }
                0x2 -> when (1 shl nib) {
                    4 -> PlistValue.Real(java.lang.Float.intBitsToFloat(sized(p, 4).toInt()).toDouble())
                    8 -> PlistValue.Real(java.lang.Double.longBitsToDouble(sized(p, 8)))
                    else -> throw PlistException("bplist: unsupported real size")
                }
                // A date reads as its seconds.
                0x3 -> if (nib == 3) PlistValue.Real(java.lang.Double.longBitsToDouble(sized(p, 8)))
                else throw PlistException("bplist: unsupported date")
                0x4 -> {
                    val n = count()
                    PlistValue.Data(slice(p, n))
                }
                0x5 -> {
                    val n = count()
                    val raw = slice(p, n)
                    PlistValue.Text(String(CharArray(raw.size) { (raw[it].toInt() and 0x7f).toChar() }))
                }
                0x6 -> {
                    val n = count()
                    PlistValue.Text(String(slice(p, n * 2), Charsets.UTF_16BE))
                }
                0xa -> {
                    val n = count()
                    val items = ArrayList<PlistValue>(minOf(n, 1024L).toInt())
                    for (i in 0 until n) items.add(obj(sized(p + i * refSize, refSize), depth + 1))
                    PlistValue.Items(items)
                }
                0xd -> {
                    val n = count()
                    val entries = ArrayList<Pair<String, PlistValue>>(minOf(n, 1024L).toInt())
                    for (i in 0 until n) {
                        val key = when (val k = obj(sized(p + i * refSize, refSize), depth + 1)) {
                            is PlistValue.Text -> k.value
                            is PlistValue.Integer -> java.lang.Long.toUnsignedString(k.value)
                            is PlistValue.Real -> k.value.toString()
                            is PlistValue.Bool -> k.value.toString()
                            else -> ""
                        }
                        entries.add(key to obj(sized(p + (n + i) * refSize, refSize), depth + 1))
                    }
                    PlistValue.Dict(entries)
                }
                else -> throw PlistException("bplist: unsupported object type 0x${kind.toString(16)}")
            }
        }
    }

    private class Node(val body: ByteArray, val refs: IntArray? = null)

    fun encode(root: PlistValue): ByteArray {
        val nodes = ArrayList<Node>()
        val top = add(nodes, root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        val offsets = IntArray(nodes.size)
        for ((i, node) in nodes.withIndex()) {
            offsets[i] = out.size()
            out.write(node.body)
            node.refs?.forEach { out.write(be(it.toLong(), refSize)) }
        }
        val table = out.size()
        val offsetSize = when {
            table > 0xffff -> 4
            table > 0xff -> 2
            else -> 1
        }
        for (o in offsets) out.write(be(o.toLong(), offsetSize))
        val trailer = ByteArray(TRAILER)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        be(nodes.size.toLong(), 8).copyInto(trailer, 8)
        be(top.toLong(), 8).copyInto(trailer, 16)
        be(table.toLong(), 8).copyInto(trailer, 24)
        out.write(trailer)
        return out.toByteArray()
    }

    private fun add(nodes: ArrayList<Node>, value: PlistValue): Int {
        val index = nodes.size
        nodes.add(Node(ByteArray(0)))
        nodes[index] = when (value) {
            is PlistValue.Bool -> Node(byteArrayOf(if (value.value) 0x09 else 0x08))
            is PlistValue.Integer -> Node(intBytes(value.value))
            is PlistValue.Real -> Node(byteArrayOf(0x23) + be(java.lang.Double.doubleToRawLongBits(value.value), 8))
            is PlistValue.Text ->
                if (value.value.all { it.code < 0x80 }) {
                    Node(marker(0x5, value.value.length) + value.value.toByteArray(Charsets.US_ASCII))
                } else {
                    Node(marker(0x6, value.value.length) + value.value.toByteArray(Charsets.UTF_16BE))
                }
            is PlistValue.Data -> Node(marker(0x4, value.bytes.size) + value.bytes)
            is PlistValue.Items -> {
                val refs = IntArray(value.items.size) { add(nodes, value.items[it]) }
                Node(marker(0xa, refs.size), refs)
            }
            is PlistValue.Dict -> {
                val n = value.entries.size
                val refs = IntArray(n * 2)
                for (i in 0 until n) refs[i] = add(nodes, PlistValue.Text(value.entries[i].first))
                for (i in 0 until n) refs[n + i] = add(nodes, value.entries[i].second)
                Node(marker(0xd, n), refs)
            }
        }
        return index
    }

    private fun marker(kind: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((kind shl 4) or count).toByte())
        val (size, log) = when {
            count > 0xffff -> 4 to 2
            count > 0xff -> 2 to 1
            else -> 1 to 0
        }
        return byteArrayOf(((kind shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) + be(count.toLong(), size)
    }

    /** Unsigned by size; a value with the high bit set takes eight bytes. */
    private fun intBytes(n: Long): ByteArray {
        val (size, log) = when {
            n < 0 || n > 0xffff_ffffL -> 8 to 3
            n > 0xffff -> 4 to 2
            n > 0xff -> 2 to 1
            else -> 1 to 0
        }
        return byteArrayOf((0x10 or log).toByte()) + be(n, size)
    }

    private fun be(value: Long, size: Int): ByteArray =
        ByteArray(size) { (value ushr (8 * (size - 1 - it))).toByte() }

    private fun readBe(b: ByteArray, at: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }
}
