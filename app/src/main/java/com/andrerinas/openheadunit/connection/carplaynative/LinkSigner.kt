package com.andrerinas.openheadunit.connection.carplaynative

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The bytes of the LIVI Link MFi service (plain TCP, port 5000).
 * Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-mfi.
 */
object LinkCodec {

    const val PORT = 5000
    const val OP_GET_CERT = 0x01
    const val OP_SIGN = 0x02
    const val OP_PROTOCOL_MAJOR = 0x03
    const val STATUS_OK = 0

    sealed class Reply {
        class Ok(val data: ByteArray) : Reply()
        class Refused(val status: Int) : Reply()
    }

    fun certificateRequest(): ByteArray = byteArrayOf(OP_GET_CERT.toByte())

    fun protocolMajorRequest(): ByteArray = byteArrayOf(OP_PROTOCOL_MAJOR.toByte())

    fun signRequest(challenge: ByteArray): ByteArray {
        val n = challenge.size
        require(n in MfiLimits.CHALLENGE_MIN..MfiLimits.CHALLENGE_MAX) { "challenge must be 1..128 bytes, got $n" }
        return byteArrayOf(OP_SIGN.toByte(), (n shr 8).toByte(), n.toByte()) + challenge
    }

    /** Status byte, `u16` big-endian length, data. The data is read even when the status refuses. */
    fun readReply(input: InputStream): Reply {
        val header = readFully(input, 3)
        val length = ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
        val data = readFully(input, length)
        val status = header[0].toInt() and 0xff
        return if (status == STATUS_OK) Reply.Ok(data) else Reply.Refused(status)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = input.read(out, done, count - done)
            if (n < 0) throw EOFException("link closed after $done of $count bytes")
            done += n
        }
        return out
    }
}

/** The two halves of one open link connection. */
class LinkStreams(val input: InputStream, val output: OutputStream) {
    fun close() {
        try { input.close() } catch (_: IOException) { }
        try { output.close() } catch (_: IOException) { }
    }
}

/**
 * [CarPlaySigner] that asks a LIVI Link over [connect], which may throw IOException.
 * One connection serves every request until a failure drops it; the next request connects again.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-mfi.
 */
class LinkSigner(
    private val connect: () -> LinkStreams,
    private val sleepMillis: (Long) -> Unit,
) : CarPlaySigner {

    private var streams: LinkStreams? = null
    private var major = 0

    @Synchronized
    override fun protocolMajor(): Int {
        if (major != 0) return major
        val data = request(LinkCodec.protocolMajorRequest())
        val value = if (data.isEmpty()) 0 else data[0].toInt() and 0xff
        if (value != 2 && value != 3) throw SignerException("the link reports protocol major $value")
        major = value
        return value
    }

    @Synchronized
    override fun certificate(): ByteArray = request(LinkCodec.certificateRequest())

    @Synchronized
    override fun sign(challenge: ByteArray): ByteArray = request(LinkCodec.signRequest(challenge))

    @Synchronized
    fun close() {
        streams?.close()
        streams = null
    }

    /** One retry after a short wait, for an I/O failure only. A refusal from the Link is final. */
    private fun request(bytes: ByteArray): ByteArray {
        val first = try {
            return exchange(bytes)
        } catch (e: IOException) {
            e
        }
        sleepMillis(RETRY_WAIT_MILLIS)
        try {
            return exchange(bytes)
        } catch (second: IOException) {
            throw SignerException("link i/o: ${first.message}; retry: ${second.message}")
        }
    }

    private fun exchange(bytes: ByteArray): ByteArray {
        val open = streams ?: connect().also { streams = it }
        val reply = try {
            open.output.write(bytes)
            open.output.flush()
            LinkCodec.readReply(open.input)
        } catch (e: IOException) {
            // A connection that failed once is not used again.
            close()
            throw e
        }
        return when (reply) {
            is LinkCodec.Reply.Ok -> reply.data
            is LinkCodec.Reply.Refused -> throw SignerException("the link refused (status ${reply.status})")
        }
    }

    companion object {
        const val RETRY_WAIT_MILLIS = 500L
    }
}
