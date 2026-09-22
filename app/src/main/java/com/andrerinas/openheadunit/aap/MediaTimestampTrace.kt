package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.decoder.video.VideoFragmentAssembler

/**
 * Reads the phone's media timestamp, which the forwarded lane drops, once per codec restart so a
 * log shows its unit and whether the clock resets. Diagnostic only: nothing reads the result.
 */
class MediaTimestampTrace {

    private var lastTimestamp: Long? = null
    private var lastArrivalMs = 0L
    private var restartPending = false

    /** A log line when this message is the first media unit after a codec config, else null. */
    fun onMessage(type: Int, flags: Int, data: ByteArray, length: Int, nowMs: Long): String? {
        if (flags != VideoFragmentAssembler.FLAG_FIRST && flags != VideoFragmentAssembler.FLAG_SINGLE) return null
        if (type == TYPE_CONFIG) {
            restartPending = true
            return null
        }
        if (type != TYPE_MEDIA || length < VideoFragmentAssembler.OFFSET_TIMESTAMP_INDICATION) return null
        var next = 0L
        for (i in 0 until 8) next = (next shl 8) or (data[VideoFragmentAssembler.OFFSET_MEDIA_INDICATION + i].toLong() and 0xFF)
        val previous = lastTimestamp
        val sinceMs = nowMs - lastArrivalMs
        lastTimestamp = next
        lastArrivalMs = nowMs
        if (!restartPending) return null
        restartPending = false
        return if (previous == null) {
            "AapVideo: forwarded stream media timestamp first=${next}us"
        } else {
            "AapVideo: forwarded stream restarted, media timestamp ${previous}us -> ${next}us " +
                "(step ${next - previous}us), ${sinceMs}ms since its last unit"
        }
    }

    private companion object {
        const val TYPE_MEDIA = 0
        const val TYPE_CONFIG = 1
    }
}
