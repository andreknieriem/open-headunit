package com.andrerinas.openheadunit.connection.carplaybox

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The box's 16-byte frame header (magic, length, type, ~type, all little-endian) and a streaming reader. */
object CarPlayBoxFrameCodec {

    const val MAGIC = 0x55AA55AA
    const val MAGIC_ENCRYPTED = 0x55BB55BB
    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 1048576
    const val WRITE_CHUNK = 16384

    class Frame(val type: Int, val payload: ByteArray)

    sealed class Decoded {
        class Message(val frame: Frame) : Decoded()
        /** A 0x55BB55BB frame: skipped whole and never decoded. */
        class Encrypted(val type: Int) : Decoded()
        /** The header declares more than [MAX_PAYLOAD]; the stream cannot be trusted any more. */
        class Overlong(val declared: Long) : Decoded()
    }

    fun encode(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC).putInt(payload.size).putInt(type).putInt(type.inv()).put(payload)
        return buf.array()
    }

    /** Splits one frame into writes of at most [size] bytes. */
    fun chunks(frame: ByteArray, size: Int = WRITE_CHUNK): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < frame.size) {
            val end = minOf(frame.size, pos + size)
            out.add(frame.copyOfRange(pos, end))
            pos = end
        }
        return out
    }

    /** Takes any byte run and returns the frames it completes; a frame may span many reads. */
    class Decoder {
        private var buf = ByteArray(HEADER_SIZE * 4)
        private var start = 0
        private var end = 0

        fun feed(bytes: ByteArray, off: Int = 0, len: Int = bytes.size): List<Decoded> {
            append(bytes, off, len)
            val out = ArrayList<Decoded>()
            while (parseOne(out)) { /* keep going */ }
            return out
        }

        private fun append(bytes: ByteArray, off: Int, len: Int) {
            if (end + len > buf.size) {
                val live = end - start
                val grown = if (live + len > buf.size) ByteArray(maxOf(buf.size * 2, live + len)) else buf
                System.arraycopy(buf, start, grown, 0, live)
                buf = grown
                start = 0
                end = live
            }
            System.arraycopy(bytes, off, buf, end, len)
            end += len
        }

        private fun int(at: Int): Int =
            (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8) or
                ((buf[at + 2].toInt() and 0xFF) shl 16) or ((buf[at + 3].toInt() and 0xFF) shl 24)

        /** Returns true when it consumed something and another pass may find more. */
        private fun parseOne(out: MutableList<Decoded>): Boolean {
            val live = end - start
            if (live == 0) return false
            // A prefix that cannot start a magic is dropped one byte at a time.
            val probe = minOf(live, 4)
            if (!prefixOfMagic(probe)) {
                start++
                return true
            }
            if (live < HEADER_SIZE) return false
            val magic = int(start)
            val declared = int(start + 4).toLong() and 0xFFFFFFFFL
            val type = int(start + 8)
            val check = int(start + 12)
            if (check != type.inv()) {
                start++
                return true
            }
            if (declared > MAX_PAYLOAD) {
                out.add(Decoded.Overlong(declared))
                start = 0
                end = 0
                return false
            }
            val total = HEADER_SIZE + declared.toInt()
            if (live < total) return false
            if (magic == MAGIC_ENCRYPTED) {
                out.add(Decoded.Encrypted(type))
            } else {
                out.add(Decoded.Message(Frame(type, buf.copyOfRange(start + HEADER_SIZE, start + total))))
            }
            start += total
            return true
        }

        private fun prefixOfMagic(count: Int): Boolean {
            return prefixMatches(MAGIC, count) || prefixMatches(MAGIC_ENCRYPTED, count)
        }

        private fun prefixMatches(magic: Int, count: Int): Boolean {
            for (i in 0 until count) {
                if (buf[start + i] != ((magic ushr (8 * i)) and 0xFF).toByte()) return false
            }
            return true
        }
    }
}
