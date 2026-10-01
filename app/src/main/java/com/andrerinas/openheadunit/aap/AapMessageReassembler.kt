package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import java.io.IOException

/**
 * Reassembles protobuf/audio messages before any handler reads their type or timestamp.
 * Video DATA/CSD remains streamed to the video thread's assembler to avoid an extra frame copy;
 * the reader's plaintext audit validates its length before the last fragment is dispatched.
 * Returned buffers are borrowed until the next accept call on this channel.
 */
internal class AapMessageReassembler {
    private class Run(val first: AapMessage, val total: Int, val video: Boolean, val bytes: ByteArray?) {
        var used = 0
    }
    private val runs = arrayOfNulls<Run>(256)
    private var reserved = 0

    fun accept(fragment: AapMessage, declaredTotal: Int): AapMessage? {
        val channel = fragment.channel
        if (channel !in runs.indices) throw IOException("Invalid AAP channel $channel")
        val flags = fragment.flags.toInt() and 0xff
        val first = AapMessageFraming.carriesMessageType(flags)
        val last = AapMessageFraming.isLast(flags)
        if (first) {
            release(channel)
            if (last) {
                if (fragment.size < 2) throw IOException("AAP message has no complete type")
                return fragment
            }
            if (declaredTotal < 2 || declaredTotal > MAX_MESSAGE_BYTES || fragment.size > declaredTotal) {
                throw IOException("Invalid AAP message length $declaredTotal on channel $channel")
            }
            // The legacy video assembler inspects the timestamp/start-code prefix in FIRST.
            // If it spans fragments, reassemble here before handing a complete unit to that thread.
            val video = channel == Channel.ID_VID && flags and AapMessageFraming.FLAG_BIT_CONTROL == 0 &&
                fragment.type in 0..1 && fragment.size >= 14
            val bytes = if (video) null else {
                if (declaredTotal > MAX_RESERVED_BYTES - reserved) throw IOException("AAP reassembly budget exhausted")
                ByteArray(declaredTotal).also { reserved += it.size }
            }
            runs[channel] = Run(fragment, declaredTotal, video, bytes)
        }
        val run = runs[channel] ?: return null // An orphan has no type; never interpret its bytes.
        if ((flags and 0x0c) != (run.first.flags.toInt() and 0x0c)) {
            release(channel)
            throw IOException("AAP fragment routing changed on channel $channel")
        }
        if (run.video) {
            if (last) release(channel)
            return AapMessage(channel, fragment.flags, run.first.type, fragment.dataOffset,
                fragment.size, fragment.data)
        }
        if (fragment.size > run.total - run.used) {
            release(channel)
            throw IOException("AAP fragments exceed declared size on channel $channel")
        }
        fragment.data.copyInto(run.bytes!!, run.used, 0, fragment.size)
        run.used += fragment.size
        if (!last) return null
        release(channel)
        if (run.used != run.total) throw IOException("Incomplete AAP message on channel $channel")
        val type = ((run.bytes[0].toInt() and 0xff) shl 8) or (run.bytes[1].toInt() and 0xff)
        return AapMessage(channel, (flags or 3).toByte(), type, 2, run.used, run.bytes)
    }

    private fun release(channel: Int) {
        runs[channel]?.bytes?.let { reserved -= it.size }
        runs[channel] = null
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_RESERVED_BYTES = 16 * 1024 * 1024
    }
}
