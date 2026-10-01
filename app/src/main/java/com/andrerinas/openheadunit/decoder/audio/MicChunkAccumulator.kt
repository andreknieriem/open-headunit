package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat

/**
 * Gathers microphone reads into the fixed-size messages the protocol asks for.
 *
 * Google's head unit guide gives the microphone a 2048-frame buffer and requires frames to be a
 * multiple of it; AudioRecord hands back whatever the device feels like, and reporter logs show 640
 * and 768 frames. The old sender also dropped any read of 64 bytes or fewer outright, so a short
 * read became a hole rather than a wait.
 *
 * Stateful, and two details are the reason this is an object rather than four lines in the
 * transport. A chunk is stamped with the capture time of its **first** byte, not of the read that
 * completed it, because a chunk is 128 ms long and stamping at the end would put every message that
 * far out. And the tail left over when capture stops is **discarded, never padded**: zero padding
 * injects silence the microphone never heard into a speech stream, and a short final message
 * violates the multiple-of-2048 rule.
 *
 * Pure: no Android, no logging, no clock of its own.
 */
class MicChunkAccumulator(private val chunkBytes: Int = MicCaptureFormat.CHUNK_BYTES) {

    init {
        require(chunkBytes > 0 && chunkBytes % MicCaptureFormat.FRAME_BYTES == 0) {
            "chunk must be a whole number of frames, was $chunkBytes bytes"
        }
    }

    private val chunk = ByteArray(chunkBytes)
    private var pending = 0
    private var chunkStartedUs = 0L
    private var chunkPeak = 0

    /** Bytes held back, waiting for the rest of a chunk. */
    val residueBytes: Int get() = pending

    /**
     * Feed one read and emit every whole chunk it completes.
     *
     * [capturedAtUs] is when this read's first byte was captured; [peak] its loudest sample. The
     * buffer handed to [emit] is reused, so a caller that keeps it has to copy.
     */
    fun offer(
        src: ByteArray,
        srcLen: Int,
        capturedAtUs: Long,
        peak: Int,
        emit: (chunk: ByteArray, length: Int, timestampUs: Long, peak: Int) -> Unit
    ) {
        offerWhile(src, srcLen, capturedAtUs, peak) { bytes, length, timestamp, level ->
            emit(bytes, length, timestamp, level)
            true
        }
    }

    /** Returns the rejected whole chunk plus unread bytes when the session closes during emit. */
    fun offerWhile(
        src: ByteArray, srcLen: Int, capturedAtUs: Long, peak: Int,
        emit: (ByteArray, Int, Long, Int) -> Boolean
    ): Int {
        var i = 0
        while (i < srcLen) {
            if (pending == 0) {
                chunkStartedUs = capturedAtUs + bytesToMicros(i)
                chunkPeak = 0
            }

            val take = minOf(chunkBytes - pending, srcLen - i)
            System.arraycopy(src, i, chunk, pending, take)
            pending += take
            i += take
            if (peak > chunkPeak) chunkPeak = peak

            if (pending == chunkBytes) {
                pending = 0 // A callback may retire this accumulator; never republish residue after it.
                if (!emit(chunk, chunkBytes, chunkStartedUs, chunkPeak)) {
                    reset()
                    return chunkBytes + srcLen - i
                }
            }
        }
        return 0
    }

    /**
     * Drop the partial chunk and return how many bytes went with it, so the cost is reported rather
     * than assumed. At most one chunk, after the user has stopped speaking.
     */
    fun reset(): Int {
        val discarded = pending
        pending = 0
        chunkStartedUs = 0L
        chunkPeak = 0
        return discarded
    }

    private fun bytesToMicros(bytes: Int): Long =
        bytes.toLong() * 1_000_000L / MicCaptureFormat.BYTES_PER_SECOND
}
