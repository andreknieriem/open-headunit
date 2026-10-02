package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * Audio service payload after AAP/TLS reassembly (offsets below exclude the outer AAP header):
 *
 * - DATA, type 0: [2-byte type][8-byte big-endian timestamp in microseconds][PCM or AAC bytes].
 * - CODEC_CONFIG, type 1: [2-byte type][codec-specific configuration bytes], with no timestamp.
 *
 * Use dataOffset rather than a hard-coded 2/10: outbound and inbound AapMessage views have
 * different outer-header layouts. A short AAC configuration can be only two bytes; skipping
 * a DATA-style timestamp would silently discard it or feed configuration as encoded audio.
 *
 * Only DATA consumes a sender credit. CODEC_CONFIG must neither return an ACK nor announce
 * playback. Timestamps describe source timing, not a packet sequence: AAC units from one capture
 * batch may share one. Never discard data or return extra credits because a timestamp repeats.
 */
internal object AudioMediaPayload {
    // DATA starts with an eight-byte big-endian capture timestamp, then encoded audio/PCM.
    // CSD starts directly with decoder configuration. Its bytes consume no DATA ACK credit.
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
