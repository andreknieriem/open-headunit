package com.andrerinas.openheadunit.secondscreen.rtp

/** The typed address and port of the RTP receiver, and the session description that opens its stream. */
object RtpDestinationPolicy {

    const val DEFAULT_PORT = 5004
    const val DEFAULT_DESTINATION = ""
    const val FFPLAY_COMMAND = "ffplay -protocol_whitelist file,udp,rtp -i stream.sdp"

    /** The 4 octets of a unicast IPv4 address, or null for anything else, a host name included. */
    fun parse(text: String): ByteArray? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.length !in 1..3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[i] = value.toByte()
        }
        val first = out[0].toInt() and 0xFF
        if (first in 224..239) return null
        if (out.all { it.toInt() == 0 } || out.all { it.toInt() == -1 }) return null
        return out
    }

    fun portOrDefault(port: Int): Int = if (port in 1..65535) port else DEFAULT_PORT

    /** A complete SDP for [destination], which a receiver opens with no line added. */
    fun sdp(destination: String, port: Int): String {
        val ip = destination.trim().ifEmpty { "0.0.0.0" }
        return listOf(
            "v=0",
            "o=- 0 0 IN IP4 $ip",
            "s=Open Headunit second screen",
            "c=IN IP4 $ip",
            "t=0 0",
            "m=video $port RTP/AVP 96",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 packetization-mode=1",
        ).joinToString("\n")
    }
}
