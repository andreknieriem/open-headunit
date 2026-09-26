package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** DATA carries a big-endian timestamp in microseconds; CODEC_CONFIG starts with CSD. */
internal object AudioMediaPayload {
    fun isMedia(type: Int): Boolean = type == Media.MsgType.MEDIA_MESSAGE_DATA_VALUE ||
        type == Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE

    // The phone acquires a flow-control permit for DATA, but not for CODEC_CONFIG.
    fun requiresAck(type: Int): Boolean = type == Media.MsgType.MEDIA_MESSAGE_DATA_VALUE

    /** Offset into the borrowed transport buffer, or -1 for an invalid/empty payload. */
    fun offset(message: AapMessage): Int {
        if (!isMedia(message.type) || message.dataOffset < 0 ||
            message.size > message.data.size || message.dataOffset > message.size) return -1
        val timestampBytes = if (requiresAck(message.type)) 8 else 0
        if (message.size - message.dataOffset <= timestampBytes) return -1
        return message.dataOffset + timestampBytes
    }

    fun timestampUs(message: AapMessage): Long {
        require(requiresAck(message.type) && offset(message) >= 0)
        var value = 0L
        for (index in message.dataOffset until message.dataOffset + 8) {
            value = (value shl 8) or (message.data[index].toLong() and 0xffL)
        }
        return value
    }
}
