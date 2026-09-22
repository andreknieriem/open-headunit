package com.andrerinas.openheadunit.secondscreen.usbdisplay

/**
 * The Open Headunit USB display protocol, version 1: the pure half, shared with the tests.
 *
 * The spec is tools/second-screen/USB-DISPLAY-PROTOCOL.md; any device that follows it is a second
 * screen, whatever its USB ids, and it decodes the H.264 itself.
 */
object UsbDisplayProtocol {

    const val INTERFACE_CLASS = 0xFF
    const val INTERFACE_SUBCLASS = 0x4F // 'O'
    const val INTERFACE_PROTOCOL = 0x44 // 'D'

    const val REQUEST_TYPE_IN = 0xC1  // device-to-host, vendor, interface
    const val REQUEST_TYPE_OUT = 0x41 // host-to-device, vendor, interface
    const val REQ_GET_INFO = 0x01
    const val REQ_START = 0x02
    const val REQ_STOP = 0x03

    const val INFO_LENGTH = 32
    const val INFO_MIN_LENGTH = 14
    const val VERSION = 1
    const val CODEC_H264 = 0x01
    const val INFO_FLAG_KEYFRAME_REQUESTS = 0x01

    const val HEADER_LENGTH = 16
    const val FRAME_FLAG_KEYFRAME = 0x01
    const val FRAME_FLAG_CONFIG = 0x02

    /** Any byte from the device's IN endpoint with this value asks for a keyframe. */
    const val DEVICE_REQUEST_KEYFRAME = 0x01

    private val INFO_MAGIC = "OHUD".toByteArray(Charsets.US_ASCII)
    private val FRAME_MAGIC = "OHUF".toByteArray(Charsets.US_ASCII)

    /** What a display says about itself in its GET_INFO answer. */
    data class Info(
        val version: Int,
        val codecs: Int,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val maxFps: Int,
        val flags: Int,
    ) {
        val sendsKeyframeRequests: Boolean get() = flags and INFO_FLAG_KEYFRAME_REQUESTS != 0
    }

    fun isDisplayInterface(ifaceClass: Int, subclass: Int, protocol: Int): Boolean =
        ifaceClass == INTERFACE_CLASS && subclass == INTERFACE_SUBCLASS && protocol == INTERFACE_PROTOCOL

    /** Null for an answer this host cannot use: wrong magic, too short, no H.264, or no size. */
    fun parseInfo(buf: ByteArray, length: Int): Info? {
        if (length < INFO_MIN_LENGTH || buf.size < INFO_MIN_LENGTH) return null
        if (!INFO_MAGIC.indices.all { buf[it] == INFO_MAGIC[it] }) return null
        val info = Info(
            version = u8(buf, 4),
            codecs = u8(buf, 5),
            widthPx = u16(buf, 6),
            heightPx = u16(buf, 8),
            densityDpi = u16(buf, 10),
            maxFps = u8(buf, 12),
            flags = u8(buf, 13),
        )
        if (info.version < 1 || info.codecs and CODEC_H264 == 0) return null
        if (info.widthPx < 64 || info.heightPx < 64) return null
        return info
    }

    /** The GET_INFO answer for [info]; the receiver's side of [parseInfo], kept here for the tests. */
    fun encodeInfo(info: Info): ByteArray = ByteArray(INFO_LENGTH).also { out ->
        INFO_MAGIC.copyInto(out, 0)
        out[4] = info.version.toByte()
        out[5] = info.codecs.toByte()
        putU16(out, 6, info.widthPx)
        putU16(out, 8, info.heightPx)
        putU16(out, 10, info.densityDpi)
        out[12] = info.maxFps.toByte()
        out[13] = info.flags.toByte()
    }

    /** The 16-byte header in front of each access unit on the bulk OUT endpoint. */
    fun frameHeader(payloadLength: Int, timestampMs: Long, keyframe: Boolean, config: Boolean): ByteArray =
        ByteArray(HEADER_LENGTH).also { out ->
            FRAME_MAGIC.copyInto(out, 0)
            putU32(out, 4, payloadLength.toLong())
            putU32(out, 8, timestampMs and 0xFFFFFFFFL)
            val flags = (if (keyframe) FRAME_FLAG_KEYFRAME else 0) or (if (config) FRAME_FLAG_CONFIG else 0)
            putU16(out, 12, flags)
        }

    private fun u8(buf: ByteArray, at: Int) = buf[at].toInt() and 0xFF
    private fun u16(buf: ByteArray, at: Int) = u8(buf, at) or (u8(buf, at + 1) shl 8)

    private fun putU16(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value shr 8).toByte()
    }

    private fun putU32(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) out[at + i] = (value shr (8 * i)).toByte()
    }
}
