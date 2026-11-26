package com.andrerinas.openheadunit.connection.carplaynative.codec

/**
 * RTSP request parsing and response building for the AirPlay control channel.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate livi-cp, `rtsp.rs`.
 */
object Rtsp {
    /** A larger body is refused before any buffer grows to hold it. */
    const val MAX_BODY = 1 shl 20

    /** A header section longer than this, ended or not, is refused. */
    const val MAX_HEADER = 1 shl 16

    /** [headers] has lower-case names. */
    class Request(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    class Response(
        val status: Int = 200,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        val protocol: String? = null,
        val statusText: String? = null,
    )

    sealed class Parsed {
        /** The whole requests at the front of the buffer, and the partial rest to keep. */
        class Ok(val requests: List<Request>, val rest: ByteArray) : Parsed()
        object TooLarge : Parsed()
    }

    private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun parse(buf: ByteArray): Parsed {
        val requests = ArrayList<Request>()
        var offset = 0
        while (offset < buf.size) {
            val headEnd = find(buf, HEADER_END, offset)
            if ((if (headEnd < 0) buf.size else headEnd) - offset > MAX_HEADER) return Parsed.TooLarge
            if (headEnd < 0) break
            val head = String(CharArray(headEnd - offset) { (buf[offset + it].toInt() and 0x7f).toChar() })
            val lines = head.split("\r\n")
            val requestLine = lines[0].split(' ')
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon >= 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = contentLength(headers["content-length"]) ?: return Parsed.TooLarge
            val bodyStart = headEnd + HEADER_END.size
            val bodyEnd = bodyStart + length
            if (bodyEnd > buf.size) break
            requests.add(
                Request(
                    method = requestLine[0],
                    path = requestLine.getOrElse(1) { "" },
                    protocol = requestLine.getOrElse(2) { "RTSP/1.0" },
                    headers = headers,
                    body = buf.copyOfRange(bodyStart, bodyEnd),
                ),
            )
            offset = bodyEnd
        }
        return Parsed.Ok(requests, buf.copyOfRange(offset, buf.size))
    }

    /** Echoes the request CSeq and sets Content-Length to the body size. */
    fun buildResponse(request: Request, response: Response): ByteArray {
        val protocol = response.protocol ?: request.protocol
        val text = response.statusText ?: statusText(response.status)
        val headers = ArrayList(response.headers)
        fun set(name: String, value: String) {
            val at = headers.indexOfFirst { it.first == name }
            if (at >= 0) headers[at] = name to value else headers.add(name to value)
        }
        request.headers["cseq"]?.let { set("CSeq", it) }
        set("Content-Length", response.body.size.toString())
        val head = buildString {
            append(protocol).append(' ').append(response.status).append(' ').append(text).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + response.body
    }

    /** Reads the leading digits. No digits means no body. Null when the value is over [MAX_BODY]. */
    internal fun contentLength(value: String?): Int? {
        val digits = value?.takeWhile { it in '0'..'9' }.orEmpty()
        if (digits.isEmpty()) return 0
        val n = digits.toBigIntegerOrNull() ?: return null
        return if (n.compareTo(java.math.BigInteger.valueOf(MAX_BODY.toLong())) > 0) null else n.toInt()
    }

    private fun statusText(status: Int): String = when (status) {
        400 -> "Bad Request"
        404 -> "Not Found"
        470 -> "Connection Authorization Required"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun find(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        for (i in start..haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
        }
        return -1
    }
}
