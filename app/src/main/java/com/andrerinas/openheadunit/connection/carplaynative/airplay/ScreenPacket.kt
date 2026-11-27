package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces

/** A screen message with a body larger than the cap. The caller drops the connection. */
class ScreenStreamException(message: String) : Exception(message)

enum class ScreenCodec { H264, H265 }

/**
 * Reads the TCP data stream of a screen: a 128-byte header (body size, opcode), then a clear
 * config or a sealed frame. The header is the AEAD data and a frame counter is the nonce.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-screen-stream, with DiPlay's drop of an unsealed frame.
 */
class ScreenPacketReader(private val key: ByteArray) {
    sealed class Event {
        /** [record] is the avcC or hvcC record, without its fourcc. */
        class Config(val codec: ScreenCodec, val record: ByteArray) : Event()

        /** The plain frame: NAL units behind their length prefixes. */
        class Frame(val payload: ByteArray) : Event()

        /** A frame failed its tag and was dropped. [first] is true once per reader, for one log line. */
        class AuthFailed(val first: Boolean) : Event()
    }

    private var buffer = ByteArray(INITIAL_BUFFER)
    private var buffered = 0
    private var counter = 0L
    private var failureLogged = false

    /** Starts a new connection: forgets a half message and the frame counter. */
    fun reset() {
        buffered = 0
        counter = 0
    }

    /** Throws [ScreenStreamException] for a body over 8 MiB. Events before it are lost; the caller ends the stream. */
    fun push(chunk: ByteArray, length: Int = chunk.size): List<Event> {
        ensure(buffered + length)
        chunk.copyInto(buffer, buffered, 0, length)
        buffered += length
        val events = ArrayList<Event>()
        var at = 0
        while (buffered - at >= HEADER_LEN) {
            val bodySize = readU32Le(buffer, at)
            if (bodySize < 0 || bodySize > MAX_BODY) throw ScreenStreamException("implausible screen body size $bodySize")
            if (buffered - at < HEADER_LEN + bodySize) break
            handle(at, bodySize, events)
            at += HEADER_LEN + bodySize
        }
        if (at > 0) {
            buffer.copyInto(buffer, 0, at, buffered)
            buffered -= at
        }
        return events
    }

    private fun handle(at: Int, bodySize: Int, events: MutableList<Event>) {
        val bodyStart = at + HEADER_LEN
        when (buffer[at + OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_CONFIG -> events += config(buffer.copyOfRange(bodyStart, bodyStart + bodySize))
            OP_VIDEO_FRAME -> {
                val body = buffer.copyOfRange(bodyStart, bodyStart + bodySize)
                // A body too short for a tag cannot be sealed, so it never reaches the decoder.
                if (body.size < CarPlayCrypto.TAG_SIZE) return
                val header = buffer.copyOfRange(at, bodyStart)
                val plain = CarPlayCrypto.chachaOpen(key, Nonces.counter(counter), body, header)
                if (plain == null) {
                    events += Event.AuthFailed(!failureLogged)
                    failureLogged = true
                } else {
                    counter++
                    events += Event.Frame(plain)
                }
            }
        }
    }

    private fun ensure(size: Int) {
        if (size > buffer.size) buffer = buffer.copyOf(maxOf(size, buffer.size * 2))
    }

    companion object {
        const val HEADER_LEN = 128
        const val MAX_BODY = 8 * 1024 * 1024
        private const val INITIAL_BUFFER = 64 * 1024
        private const val OPCODE_OFFSET = 4
        private const val OP_VIDEO_FRAME = 0
        private const val OP_VIDEO_CONFIG = 1

        /** The fourcc `avcC` or `hvcC` names the codec. Without one, a record that looks like avcC is H.264. */
        internal fun config(payload: ByteArray): Event.Config {
            var i = 4
            while (i + 4 <= payload.size) {
                val fourcc = String(payload, i, 4, Charsets.US_ASCII)
                if (fourcc == "hvcC") return Event.Config(ScreenCodec.H265, payload.copyOfRange(i + 4, payload.size))
                if (fourcc == "avcC") return Event.Config(ScreenCodec.H264, payload.copyOfRange(i + 4, payload.size))
                i++
            }
            return Event.Config(if (looksLikeAvcc(payload)) ScreenCodec.H264 else ScreenCodec.H265, payload)
        }

        private fun looksLikeAvcc(a: ByteArray): Boolean {
            if (a.size < 9 || (a[5].toInt() and 0x1f) < 1) return false
            val spsLength = ((a[6].toInt() and 0xff) shl 8) or (a[7].toInt() and 0xff)
            return 8 + spsLength <= a.size && (a[8].toInt() and 0x1f) == 7
        }

        private fun readU32Le(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
                ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
    }
}
