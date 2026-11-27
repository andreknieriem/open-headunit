package com.andrerinas.openheadunit.connection.carplaynative.usb

import java.util.ArrayDeque

/**
 * Joins bulk reads into whole NTB16 blocks and queues their Ethernet frames. A read may cut or join blocks.
 * A damaged block is skipped up to the next plausible NTB16 header; too many skips in a window fail the link.
 * Follows DiPlay (https://github.com/shihabal3amri/DiPlay) `transport/NcmUsbBridge.kt` `drainFrames` and `resynchronize`.
 */
class NtbReassembler(
    private val packetSize: Int,
    private val clock: () -> Long,
    private val diagnostic: (String) -> Unit = {},
) {
    private var buffered = ByteArray(INITIAL_BYTES)
    private var size = 0
    private var padPossible = false
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var skipped = 0

    val resyncs: Int get() = resyncCount

    /** Adds bytes and extracts every whole block. Throws when the queue or the resync rate passes its bound. */
    fun append(bytes: ByteArray, length: Int = bytes.size) {
        if (size + length > MAX_BUFFERED_BYTES) throw IphoneUsbException.Protocol("NCM input is over its buffer bound")
        if (size + length > buffered.size) buffered = buffered.copyOf(maxOf(size + length, buffered.size * 2))
        bytes.copyInto(buffered, size, 0, length)
        size += length
        drain()
    }

    fun takeFrame(): ByteArray? = frames.pollFirst()?.also { queuedBytes -= it.size }

    private fun drain() {
        while (true) {
            // A block of an exact packet multiple may carry one zero byte to end the transfer. It may arrive later.
            if (padPossible && size > 0) {
                padPossible = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit
                    else -> if (!resynchronize("invalid NTB16 pad")) return
                }
            }
            if (size < NTH_BYTES) return
            if (readU32(0) != Ntb16Codec.NTH16_SIG) { if (!resynchronize("no NTB16 header")) return; continue }
            val blockLength = readU16(8)
            if (blockLength < MIN_BLOCK_BYTES) { if (!resynchronize("block length $blockLength")) return; continue }
            if (size < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueue(frame)
            discard(blockLength)
            padPossible = blockLength % packetSize == 0
            finishResync()
        }
    }

    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = clock()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.first() > RESYNC_WINDOW_MS) resyncTimes.removeFirst()
            if (resyncTimes.size >= MAX_RESYNCS) throw IphoneUsbException.Protocol("NCM stream damaged again and again ($reason)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            skipped = 0
        }
        val next = (1..size - NTH_BYTES).firstOrNull(::plausibleHeaderAt)
        val drop = next ?: (size - (NTH_BYTES - 1)).coerceAtLeast(0)
        discard(drop)
        skipped += drop
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS) diagnostic("CarPlayNative: NCM resynchronized after $reason, skipped $skipped bytes")
    }

    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (readU32(offset) != Ntb16Codec.NTH16_SIG) return false
        val blockLength = readU16(offset + 8)
        val ndpIndex = readU16(offset + 10)
        return readU16(offset + 4) == NTH_BYTES && blockLength >= MIN_BLOCK_BYTES && ndpIndex >= NTH_BYTES &&
            ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun enqueue(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) throw IphoneUsbException.Protocol("NCM frame queue is over its bound")
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, size)
        size -= count
    }

    private fun readU16(offset: Int) = (buffered[offset].toInt() and 0xff) or ((buffered[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(offset: Int) = readU16(offset) or (readU16(offset + 2) shl 16)

    private companion object {
        const val NTH_BYTES = 12
        const val MIN_BLOCK_BYTES = 28
        const val INITIAL_BYTES = 32 * 1024
        const val MAX_BUFFERED_BYTES = 256 * 1024
        const val MAX_QUEUED_FRAMES = 256
        const val MAX_QUEUED_BYTES = 1 shl 20
        const val MAX_RESYNCS = 16
        const val RESYNC_WINDOW_MS = 10_000L
        const val LOGGED_RESYNCS = 5
    }
}
