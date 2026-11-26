package com.andrerinas.openheadunit.connection.carplaynative.usb

/** Raised when the usbmux stream is out of step. The message names the declared length. */
class UsbmuxFramingException(message: String) : Exception(message)

/**
 * usbmux version 2 framing: the VERSION packet, the 16-byte mux header and the receive framer with its padding rule.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-usbmux `mux.rs`, and DiPlay (https://github.com/shihabal3amri/DiPlay) `transport/UsbMuxFrameBuffer.kt`.
 */
object UsbmuxCodec {
    const val PROTOCOL_VERSION = 0
    const val PROTOCOL_SETUP = 2
    const val PROTOCOL_TCP = 6
    const val VERSION = 2
    const val SETUP_VALUE = 0x07
    const val LOCKDOWN_PORT = 62078
    const val MUX_MAGIC = 0xfeedface.toInt()
    const val HEADER_BYTES = 16
    const val VERSION_BYTES = 20

    /** One received mux frame. [word8] is the magic word, [sequence] the sender's counter. */
    class Frame(val protocol: Int, val length: Int, val word8: Int, val sequence: Int, val payload: ByteArray)

    /** The 20-byte VERSION packet: protocol 0, length 20, version 2, eight zero bytes. */
    fun versionPacket(): ByteArray = ByteArray(VERSION_BYTES).also {
        putU32(it, 0, PROTOCOL_VERSION)
        putU32(it, 4, VERSION_BYTES)
        putU32(it, 8, VERSION)
    }

    /** A mux frame: protocol, total length, magic, our counter, the counter of the last frame we read. */
    fun frame(protocol: Int, sequence: Int, acknowledgement: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        putU32(out, 0, protocol)
        putU32(out, 4, out.size)
        putU32(out, 8, MUX_MAGIC)
        putU16(out, 12, sequence)
        putU16(out, 14, acknowledgement)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    fun setupFrame(sequence: Int, acknowledgement: Int): ByteArray = frame(PROTOCOL_SETUP, sequence, acknowledgement, byteArrayOf(SETUP_VALUE.toByte()))

    fun isVersionReply(frame: Frame): Boolean = frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == VERSION

    internal fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    internal fun putU32(target: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) target[offset + i] = (value ushr (24 - i * 8)).toByte()
    }

    internal fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    internal fun readU32(source: ByteArray, offset: Int): Int =
        (readU16(source, offset) shl 16) or readU16(source, offset + 2)

    /** Incremental receive framing. The caller serializes access. */
    class FrameBuffer(private val diagnostic: (String) -> Unit = {}) {
        private var bytes = ByteArray(0)
        private var optionalReplyPadding: Frame? = null
        private var paddingReports = 0
        private var lastUsbReadBytes = 0
        val bufferedBytes: Int get() = bytes.size

        fun append(transfer: ByteArray) {
            lastUsbReadBytes = transfer.size
            bytes += transfer
        }

        fun takeFrame(): Frame? {
            if (bytes.size < HEADER_BYTES) return null
            var length = readU32(bytes, 4)
            if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
                val previous = optionalReplyPadding
                // Some iPhones add four bytes after a VERSION, TCP or diagnostic reply.
                // Only that one boundary is skipped, and only before a validated next header;
                // a split or normal next header at offset zero is always kept.
                if (previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                    bytes.size < PADDING_BYTES + HEADER_BYTES) return null
                val followingProtocol = readU32(bytes, PADDING_BYTES)
                val followingLength = readU32(bytes, PADDING_BYTES + 4)
                val candidateHeader = previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                    readU32(bytes, PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
                val validTarget = when {
                    candidateHeader && followingProtocol == PROTOCOL_TCP &&
                        followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES -> {
                        if (bytes.size < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
                        val tcpHeaderBytes = ((bytes[PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
                        tcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                            readU16(bytes, PADDING_BYTES + HEADER_BYTES) != 0 &&
                            readU16(bytes, PADDING_BYTES + HEADER_BYTES + 2) != 0
                    }
                    candidateHeader && followingProtocol == PROTOCOL_DIAGNOSTIC &&
                        followingLength in (HEADER_BYTES + 2)..(HEADER_BYTES + MAX_DIAGNOSTIC_PAYLOAD_BYTES) -> {
                        // The whole bounded diagnostic payload must validate before the skip.
                        if (bytes.size < PADDING_BYTES + followingLength) return null
                        isCapturedDiagnosticPayload(bytes, PADDING_BYTES + HEADER_BYTES, followingLength - HEADER_BYTES)
                    }
                    // A VERSION reply with its own trailer after the previous trailer: its header is fixed.
                    previous != null && readU32(bytes, 0) != PROTOCOL_TCP && followingProtocol == PROTOCOL_VERSION &&
                        followingLength == VERSION_BYTES && readU32(bytes, PADDING_BYTES + 8) == VERSION -> true
                    else -> false
                }
                if (previous != null && validTarget) {
                    bytes = bytes.copyOfRange(PADDING_BYTES, bytes.size)
                    optionalReplyPadding = null
                    if (paddingReports++ < MAX_PADDING_REPORTS) report(
                        "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                            "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                            "nextProtocol=$followingProtocol nextLength=$followingLength " +
                            "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=${bytes.size}")
                    if (bytes.size < HEADER_BYTES) return null
                    length = readU32(bytes, 4)
                } else {
                    report("USBMUX framing rejected declaredLength=$length bufferedBytes=${bytes.size} " +
                        "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}")
                    throw UsbmuxFramingException("Invalid USBMUX frame length $length")
                }
            }
            if (bytes.size < length) return null
            val frame = Frame(
                protocol = readU32(bytes, 0), length = length, word8 = readU32(bytes, 8),
                sequence = ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff),
                payload = bytes.copyOfRange(HEADER_BYTES, length),
            )
            bytes = bytes.copyOfRange(length, bytes.size)
            optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
            return frame
        }

        private fun canHaveOptionalReplyPadding(frame: Frame): Boolean {
            if (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == VERSION) return true
            if (frame.protocol == PROTOCOL_DIAGNOSTIC && frame.word8 == CAPTURED_REPLY_MAGIC) {
                return isCapturedDiagnosticPayload(frame.payload, 0, frame.payload.size)
            }
            if (frame.protocol != PROTOCOL_TCP || frame.word8 != CAPTURED_REPLY_MAGIC ||
                frame.payload.size < TCP_HEADER_BYTES) return false
            val tcpHeaderBytes = ((frame.payload[12].toInt() ushr 4) and 0x0f) * 4
            return tcpHeaderBytes in TCP_HEADER_BYTES..frame.payload.size &&
                readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0
        }

        /** Only the captured diagnostic subtype followed by bounded printable ASCII qualifies. */
        private fun isCapturedDiagnosticPayload(source: ByteArray, offset: Int, length: Int): Boolean =
            length in 2..MAX_DIAGNOSTIC_PAYLOAD_BYTES && source[offset].toInt() == DIAGNOSTIC_TEXT_SUBTYPE &&
                (offset + 1 until offset + length).all { source[it].toInt() in 0x20..0x7e }

        private fun report(line: String) { runCatching { diagnostic(line) } }

        private companion object {
            const val TCP_HEADER_BYTES = 20
            const val MAX_FRAME_BYTES = 65_536
            const val PADDING_BYTES = 4
            const val MAX_PADDING_REPORTS = 4
            const val PROTOCOL_DIAGNOSTIC = 1
            const val DIAGNOSTIC_TEXT_SUBTYPE = 4
            const val MAX_DIAGNOSTIC_PAYLOAD_BYTES = 1_024
            val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
        }
    }
}
