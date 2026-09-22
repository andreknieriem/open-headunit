package com.andrerinas.openheadunit.secondscreen.network

/** The pure half of the network stream: what a receiver sent, and what it is answered with. */
object NetworkStreamPolicy {

    const val DEFAULT_PORT = 5000

    /** About three seconds at 30 fps; past that a receiver is behind, not jittery. */
    const val MAX_QUEUED_UNITS = 90

    /** Enough for several 1080p keyframes, and a hard ceiling on what one slow receiver holds. */
    const val MAX_QUEUED_BYTES = 4 * 1024 * 1024

    /** How long to wait for an HTTP request line before treating the client as raw TCP. */
    const val REQUEST_PEEK_MS = 1000

    /** VLC and browsers ask over HTTP; ffplay and gstreamer read raw bytes and say nothing. */
    fun isHttpRequest(buf: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        val head = String(buf, 0, minOf(length, 5), Charsets.US_ASCII)
        return head.startsWith("GET ") || head.startsWith("HEAD ")
    }

    val HTTP_RESPONSE_HEADER: ByteArray = (
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: video/h264\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** Below 1024 needs privileges the app does not have, so a typed port there is not taken. */
    fun portOrDefault(port: Int): Int = if (port in 1024..65535) port else DEFAULT_PORT

    /** What the settings screen tells the user to run on the receiving computer. */
    fun ffplayCommand(host: String, port: Int): String =
        "ffplay -f h264 -fflags nobuffer -flags low_delay -framedrop tcp://$host:$port"
}
