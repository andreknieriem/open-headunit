package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import java.io.IOException

/**
 * Reassembles protobuf/audio messages before any handler reads their type or timestamp.
 * Video DATA/CSD remains streamed to the video thread's assembler to avoid an extra frame copy;
 * the reader's plaintext audit validates its length before the last fragment is dispatched.
 * A direct/streamed result still borrows TLS storage until the next decrypt on ANY channel.
 * A copied result owns a newly assembled array. Callers use the conservative borrowed-buffer
 * contract for both and copy before an asynchronous handoff.
 *
 * FIRST's declared total includes the two-byte type and any DATA timestamp exactly once.
 * Continuations contribute all their plaintext bytes, starting at zero. Require an exact total
 * at LAST before parsing a copied message; bytes from different channels must never share a run.
 */
internal class AapMessageReassembler {
    private class Run(val first: AapMessage, val total: Int, val video: Boolean, var bytes: ByteArray?) {
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
            // Preserve the streamed video path only when FIRST has enough prefix for its
            // downstream parser: type (2), possible timestamp (8), start code (4), and a byte
            // beyond it (1). A 14-byte FIRST can end exactly at the start code. Buffer such
            // short prefixes here and emit a COMPLETE message at LAST instead of letting the
            // video parser discard a valid run. CONTROL traffic never takes this shortcut.
            val video = channel == Channel.ID_VID && flags and AapMessageFraming.FLAG_BIT_CONTROL == 0 &&
                fragment.type in 0..1 && fragment.size >= 15
            // Album art can span roughly 1 MiB of metadata. Grow copied messages as bytes
            // arrive instead of reserving the peer's entire declared total up front.
            val bytes = if (video) null else ByteArray(0)
            runs[channel] = Run(fragment, declaredTotal, video, bytes)
        }
        val run = runs[channel] ?: return null // An orphan has no type; never interpret its bytes.
        // FIRST/LAST change across the run; CONTROL/ENCRYPTED must not. Do not let a
        // continuation reinterpret an existing service payload using a different routing class.
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
        val required = run.used + fragment.size
        val previous = run.bytes!!
        if (required > previous.size) {
            val capacity = minOf(run.total, maxOf(required, maxOf(1024, previous.size * 2)),
                previous.size + MAX_RESERVED_BYTES - reserved)
            if (capacity < required) {
                release(channel)
                throw IOException("AAP reassembly budget exhausted")
            }
            run.bytes = previous.copyOf(capacity)
            reserved += capacity - previous.size
        }
        val bytes = run.bytes!!
        fragment.data.copyInto(bytes, run.used, 0, fragment.size)
        run.used += fragment.size
        if (!last) return null
        release(channel)
        if (run.used != run.total) throw IOException("Incomplete AAP message on channel $channel")
        val type = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        return AapMessage(channel, (flags or 3).toByte(), type, 2, run.used, bytes)
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
