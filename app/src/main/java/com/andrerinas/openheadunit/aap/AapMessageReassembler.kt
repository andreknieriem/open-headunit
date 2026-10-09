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
internal class AapMessageReassembler(
    private val onDroppedVideoPayload: () -> Unit = {},
    private val onDroppedMediaData: (Int) -> Unit = {},
    private val onDrop: (String) -> Unit = {}
) {
    private class Run(val first: AapMessage, val total: Int, val video: Boolean, var bytes: ByteArray?) {
        var used = 0
        var discarded = false
        var typeBytes = 0
        var type = 0

        fun observeType(fragment: AapMessage) {
            var offset = 0
            while (typeBytes < 2 && offset < fragment.size) {
                type = (type shl 8) or (fragment.data[offset++].toInt() and 0xff)
                typeBytes++
            }
        }
    }
    private val runs = arrayOfNulls<Run>(256)
    private var reserved = 0

    fun accept(fragment: AapMessage, declaredTotal: Int): AapMessage? {
        val channel = fragment.channel
        if (channel !in runs.indices) throw IOException("Invalid AAP channel $channel")
        val flags = fragment.flags.toInt() and 0xff
        val first = AapMessageFraming.carriesMessageType(flags)
        val last = AapMessageFraming.isLast(flags)
        // TLS can consume a control record without producing a new service message.
        // Empty continuations still belong to their run, including a legal empty LAST.
        if (first && fragment.size == 0) return null
        if (first) {
            release(channel, abandoned = true)
            if (last) {
                if (fragment.size < 2) return drop(channel, "message has no complete type")
                return fragment
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
            // Routing changes are not payload-length errors. Keep the existing fatal policy
            // rather than guessing which service owns the remainder or its DATA credit.
            release(channel)
            throw IOException("AAP fragment routing changed on channel $channel")
        }
        run.observeType(fragment)
        if (run.discarded) {
            if (last) release(channel)
            return null
        }
        if (first && (declaredTotal < 2 || declaredTotal > MAX_MESSAGE_BYTES || fragment.size > declaredTotal)) {
            return discard(channel, run, last, "invalid message length $declaredTotal")
        }
        if (run.video) {
            if (last) release(channel)
            return AapMessage(channel, fragment.flags, run.first.type, fragment.dataOffset,
                fragment.size, fragment.data)
        }
        if (fragment.size > run.total - run.used) {
            return discard(channel, run, last, "fragments exceed declared size")
        }
        val required = run.used + fragment.size
        val previous = run.bytes!!
        if (required > previous.size) {
            val capacity = minOf(run.total, maxOf(required, maxOf(1024, previous.size * 2)),
                previous.size + MAX_RESERVED_BYTES - reserved)
            if (capacity < required) return discard(channel, run, last, "reassembly budget exhausted")
            run.bytes = previous.copyOf(capacity)
            reserved += capacity - previous.size
        }
        val bytes = run.bytes!!
        fragment.data.copyInto(bytes, run.used, 0, fragment.size)
        run.used += fragment.size
        if (!last) return null
        if (run.used != run.total) return discard(channel, run, last, "incomplete message")
        release(channel)
        val type = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        return AapMessage(channel, (flags or 3).toByte(), type, 2, run.used, bytes)
    }

    private fun discard(channel: Int, run: Run, last: Boolean, reason: String): AapMessage? {
        // Free payload storage immediately, but retain the two-byte type and routing until LAST or a replacement FIRST.
        // DATA consumes one sender credit even when its payload cannot be delivered.
        releaseBytes(run)
        run.discarded = true
        onDrop("$reason on channel $channel")
        if (last) release(channel)
        return null
    }

    private fun drop(channel: Int, reason: String): AapMessage? {
        // A standalone message without a complete type has no DATA identity to acknowledge.
        // Its frame and TLS record are already consumed, so the next message remains readable.
        release(channel)
        onDrop("$reason on channel $channel")
        return null
    }

    private fun release(channel: Int, abandoned: Boolean = false) {
        val run = runs[channel] ?: return
        // Clear ownership before callbacks: LAST and a replacement FIRST both retire this run,
        // and each rejected DATA message returns at most one credit to its current session.
        runs[channel] = null
        releaseBytes(run)
        // Replacement FIRST abandons even a previously valid run. Its LAST will never
        // reach a handler, so this is the only place that can return that DATA credit.
        // Successful completion leaves credit delivery to the handler.
        if ((!run.discarded && !abandoned) || run.typeBytes != 2 ||
            run.first.flags.toInt() and AapMessageFraming.FLAG_BIT_CONTROL != 0) return
        // Losing CSD also needs video recovery, but only DATA consumes a sender credit.
        // A valid abandoned run was already reported as truncated by the reader audit.
        if (run.discarded && channel == Channel.ID_VID && run.type in 0..1) onDroppedVideoPayload()
        if ((Channel.isAudio(channel) || channel == Channel.ID_VID) && run.type == 0) onDroppedMediaData(channel)
    }

    private fun releaseBytes(run: Run) {
        run.bytes?.let { reserved -= it.size }
        run.bytes = null
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_RESERVED_BYTES = 16 * 1024 * 1024
    }
}
