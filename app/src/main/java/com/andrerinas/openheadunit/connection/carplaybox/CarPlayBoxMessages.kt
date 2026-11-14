package com.andrerinas.openheadunit.connection.carplaybox

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Message types, builders and parsers for the box protocol. Every builder returns a whole frame. */
object CarPlayBoxMessages {

    const val TYPE_OPEN = 0x01
    const val TYPE_PLUGGED = 0x02
    const val TYPE_PHASE = 0x03
    const val TYPE_UNPLUGGED = 0x04
    const val TYPE_TOUCH = 0x05
    const val TYPE_VIDEO = 0x06
    const val TYPE_AUDIO = 0x07
    const val TYPE_COMMAND = 0x08
    const val TYPE_MULTI_TOUCH = 0x17
    const val TYPE_BOX_SETTINGS = 0x19
    const val TYPE_SEND_FILE = 0x99
    const val TYPE_HEARTBEAT = 0xAA
    const val TYPE_SOFTWARE_VERSION = 0xCC

    const val CMD_MIC_CAR = 7
    const val CMD_KEYFRAME = 12
    /** Audio over USB. Command 22 stays unsent: with it AutoKit's reader drops every audio frame. */
    const val CMD_AUDIO_OVER_USB = 23

    const val AUDIO_TYPE_MIC = 3
    const val DECODE_TYPE_MIC = 5
    const val PHONE_WORK_MODE_CARPLAY = 2
    const val VIDEO_HEADER_SIZE = 20
    const val AUDIO_HEADER_SIZE = 12

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun open(width: Int, height: Int, fps: Int, format: Int, packetMax: Int, boxVersion: Int): ByteArray {
        val b = le(28)
        b.putInt(width).putInt(height).putInt(fps).putInt(format).putInt(packetMax)
            .putInt(boxVersion).putInt(PHONE_WORK_MODE_CARPLAY)
        return CarPlayBoxFrameCodec.encode(TYPE_OPEN, b.array())
    }

    fun heartbeat(): ByteArray = CarPlayBoxFrameCodec.encode(TYPE_HEARTBEAT)

    fun command(n: Int): ByteArray =
        CarPlayBoxFrameCodec.encode(TYPE_COMMAND, le(4).putInt(n).array())

    fun sendFile(path: String, content: ByteArray): ByteArray {
        val name = path.toByteArray(Charsets.UTF_8) + 0
        val b = le(4 + name.size + 4 + content.size)
        b.putInt(name.size).put(name).putInt(content.size).put(content)
        return CarPlayBoxFrameCodec.encode(TYPE_SEND_FILE, b.array())
    }

    fun sendFileInt(path: String, value: Int): ByteArray = sendFile(path, le(4).putInt(value).array())

    fun touch(action: Int, x: Int, y: Int, flags: Int): ByteArray =
        CarPlayBoxFrameCodec.encode(TYPE_TOUCH, le(16).putInt(action).putInt(x).putInt(y).putInt(flags).array())

    fun micAudio(pcm: ByteArray, length: Int = pcm.size): ByteArray {
        val b = le(AUDIO_HEADER_SIZE + length)
        b.putInt(DECODE_TYPE_MIC).putFloat(0.0f).putInt(AUDIO_TYPE_MIC).put(pcm, 0, length)
        return CarPlayBoxFrameCodec.encode(TYPE_AUDIO, b.array())
    }

    class VideoHeader(val width: Int, val height: Int, val flags: Int) {
        /** The word a touch frame echoes: low half from flag bits 2 and 3, high half from bit 0. */
        val touchFlags: Int
            get() {
                val low = when ((flags ushr 2) and 3) {
                    1 -> 1
                    2 -> 4
                    else -> 2
                }
                return low or ((flags and 1) shl 16)
            }
    }

    fun parseVideo(payload: ByteArray): VideoHeader? {
        if (payload.size < VIDEO_HEADER_SIZE) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return VideoHeader(b.getInt(0), b.getInt(4), b.getInt(8))
    }

    class AudioHeader(val decodeType: Int, val volume: Float, val audioType: Int, val command: Int?)

    fun parseAudio(payload: ByteArray): AudioHeader? {
        if (payload.size < AUDIO_HEADER_SIZE) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val command = if (payload.size == AUDIO_HEADER_SIZE + 1) payload[AUDIO_HEADER_SIZE].toInt() and 0xFF else null
        return AudioHeader(b.getInt(0), b.getFloat(4), b.getInt(8), command)
    }

    /** The phone type word of a Plugged frame, or null when the payload is too short. */
    fun parsePluggedPhoneType(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
    }

    fun parseCommand(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
    }

    fun parseSoftwareVersion(payload: ByteArray): String =
        String(payload, Charsets.UTF_8).trimEnd('\u0000').trim()

    /** The display rounded down to even values, which the box's encoder needs. */
    fun openSize(displayW: Int, displayH: Int): Pair<Int, Int> =
        Pair(maxOf(2, displayW and 1.inv()), maxOf(2, displayH and 1.inv()))
}
