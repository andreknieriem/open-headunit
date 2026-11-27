package com.andrerinas.openheadunit.connection.carplaynative.iap2

import java.io.ByteArrayOutputStream

/** A malformed CSM frame, parameter or scalar. */
class Iap2FormatException(message: String) : Exception(message)

/** One CSM parameter: `u16 length | u16 id | payload`. Ids may repeat. */
class Iap2Param(val id: Int, val payload: ByteArray)

/** One control message: `0x4040 | u16 length | u16 id | parameters`, big endian, no checksum. */
class Iap2Frame(val messageId: Int, val body: ByteArray) {
    fun encode(): ByteArray = Iap2Csm.frame(messageId, body)

    /** Throws [Iap2FormatException] on a truncated or oversized parameter. */
    fun params(): List<Iap2Param> = Iap2Csm.parseParams(body)

    fun first(id: Int): ByteArray? = params().firstOrNull { it.id == id }?.payload
}

/** Builds a parameter list. Nothing is written for a value the caller leaves out. */
class Iap2Body {
    private val out = ByteArrayOutputStream()

    fun bytes(id: Int, value: ByteArray): Iap2Body {
        require(id in 0..0xFFFF) { "CSM parameter id must fit in u16" }
        require(value.size <= Iap2Csm.MAX_PARAM_PAYLOAD) { "CSM parameter payload too long" }
        val length = value.size + Iap2Csm.PARAM_HEADER
        out.write(length ushr 8); out.write(length)
        out.write(id ushr 8); out.write(id)
        out.write(value)
        return this
    }

    fun void(id: Int) = bytes(id, ByteArray(0))
    fun u8(id: Int, v: Int) = bytes(id, byteArrayOf(v.toByte()))
    fun u16(id: Int, v: Int) = bytes(id, byteArrayOf((v ushr 8).toByte(), v.toByte()))
    fun u32(id: Int, v: Long) = bytes(id, byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    fun u16List(id: Int, values: List<Int>): Iap2Body {
        val b = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            b[i * 2] = (v ushr 8).toByte()
            b[i * 2 + 1] = v.toByte()
        }
        return bytes(id, b)
    }

    fun string(id: Int, value: String): Iap2Body {
        require('\u0000' !in value) { "CSM strings hold no NUL" }
        return bytes(id, value.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
    }

    fun group(id: Int, build: Iap2Body.() -> Unit) = bytes(id, Iap2Body().apply(build).build())

    fun build(): ByteArray = out.toByteArray()
}

/**
 * Frame and parameter codec. Follows LIVI (https://github.com/f-io/LIVI) `iap2-csm/src/lib.rs` and
 * DiPlay (https://github.com/shihabal3amri/DiPlay) `iap2/wire/Iap2WireCodec.kt`, from xcertplay (https://github.com/shilapi/xcertplay).
 */
object Iap2Csm {
    const val START = 0x4040
    const val HEADER = 6
    const val MAX_FRAME = 0xFFFF
    const val PARAM_HEADER = 4
    const val MAX_PARAM_PAYLOAD = 0xFFFF - PARAM_HEADER

    fun frame(messageId: Int, body: ByteArray): ByteArray {
        require(messageId in 0..0xFFFF) { "CSM message id must fit in u16" }
        require(body.size <= MAX_FRAME - HEADER) { "CSM body too long" }
        val length = HEADER + body.size
        val out = ByteArray(length)
        out[0] = (START ushr 8).toByte(); out[1] = START.toByte()
        out[2] = (length ushr 8).toByte(); out[3] = length.toByte()
        out[4] = (messageId ushr 8).toByte(); out[5] = messageId.toByte()
        body.copyInto(out, HEADER)
        return out
    }

    fun build(messageId: Int, block: Iap2Body.() -> Unit = {}): Iap2Frame = Iap2Frame(messageId, Iap2Body().apply(block).build())

    fun parseParams(body: ByteArray): List<Iap2Param> {
        val list = ArrayList<Iap2Param>()
        var offset = 0
        while (offset < body.size) {
            if (body.size - offset < PARAM_HEADER) throw Iap2FormatException("truncated CSM parameter header")
            val length = u16(body, offset)
            if (length < PARAM_HEADER || length > body.size - offset) throw Iap2FormatException("bad CSM parameter length $length")
            list += Iap2Param(u16(body, offset + 2), body.copyOfRange(offset + PARAM_HEADER, offset + length))
            offset += length
        }
        return list
    }

    /** A NUL terminated string. */
    fun readString(payload: ByteArray): String {
        if (payload.isEmpty() || payload[payload.size - 1].toInt() != 0) throw Iap2FormatException("CSM string is not NUL terminated")
        val body = payload.copyOf(payload.size - 1)
        if (body.any { it.toInt() == 0 }) throw Iap2FormatException("CSM string holds a NUL")
        return String(body, Charsets.UTF_8)
    }

    fun readU16List(payload: ByteArray): List<Int> {
        if (payload.size % 2 != 0) throw Iap2FormatException("CSM u16 list has odd length")
        return List(payload.size / 2) { u16(payload, it * 2) }
    }

    fun readU8(payload: ByteArray): Int {
        if (payload.size != 1) throw Iap2FormatException("CSM u8 has ${payload.size} bytes")
        return payload[0].toInt() and 0xFF
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /**
     * Joins control bytes into frames. It skips garbage before `0x4040` and holds at most one frame.
     * Follows DiPlay (https://github.com/shihabal3amri/DiPlay) `iap2/wire/Iap2CsmFramer.kt`, from xcertplay.
     */
    class Framer {
        private var buf = ByteArray(0)

        fun offer(chunk: ByteArray): List<Iap2Frame> {
            val frames = ArrayList<Iap2Frame>()
            buf += chunk
            var pos = 0
            while (true) {
                while (buf.size - pos >= 2 && !(buf[pos].toInt() == 0x40 && buf[pos + 1].toInt() == 0x40)) pos++
                if (buf.size - pos < HEADER) break
                val length = u16(buf, pos + 2)
                if (length < HEADER) {
                    pos++
                    continue
                }
                if (buf.size - pos < length) break
                frames += Iap2Frame(u16(buf, pos + 4), buf.copyOfRange(pos + HEADER, pos + length))
                pos += length
            }
            buf = buf.copyOfRange(pos, buf.size)
            return frames
        }
    }
}
