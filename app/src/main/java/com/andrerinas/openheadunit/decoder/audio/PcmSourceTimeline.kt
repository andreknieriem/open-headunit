package com.andrerinas.openheadunit.decoder.audio

/** Fixed metadata ring keyed by absolute output/input frame counters. Evicted, transformed,
 * concealed and unlabelled spans are unknown, never extrapolated across. Caller serializes. */
internal class PcmSourceTimeline(private val capacity: Int = 256, private val sampleRate: Int = 48000) {
    private val first = LongArray(capacity)
    private val end = LongArray(capacity)
    private val anchorFrames = LongArray(capacity)
    private val source = LongArray(capacity)
    private val generations = LongArray(capacity)
    private var next = 0
    private var size = 0
    var generation = NONE
        private set

    init { require(capacity > 0 && sampleRate > 0) }

    fun clear() { next = 0; size = 0; generation = NONE }

    fun append(firstFrame: Long, frames: Int, sourceUs: Long, generation: Long) {
        if (frames <= 0 || firstFrame < 0 || firstFrame > Long.MAX_VALUE - frames) return
        var anchorFrame = firstFrame
        var anchorUs = sourceUs
        if (size > 0 && sourceUs != NONE) {
            val previous = (next - 1 + capacity) % capacity
            if (end[previous] == firstFrame && generations[previous] == generation) {
                val predicted = positionFrom(previous, firstFrame)
                // Keep one sample-phase origin across contiguous packets. Re-anchoring at each
                // rounded microsecond stamp would round again at bank and playback boundaries.
                // The existing one-microsecond wire quantization allowance does not accumulate.
                if (predicted != NONE &&
                    (sourceUs == predicted || sourceUs - 1 == predicted || predicted - 1 == sourceUs)) {
                    anchorFrame = anchorFrames[previous]
                    anchorUs = source[previous]
                }
            }
        }
        first[next] = firstFrame
        end[next] = firstFrame + frames
        anchorFrames[next] = anchorFrame
        source[next] = anchorUs
        generations[next] = generation
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    fun sourceAt(frame: Long, frames: Int = 1): Long {
        generation = NONE
        if (frame < 0 || frames <= 0 || frame > Long.MAX_VALUE - frames) return NONE
        var position = frame
        val limit = frame + frames
        var result = NONE
        var owner = NONE
        var originFrame = NONE
        var originUs = NONE
        while (position < limit) {
            var found = false
            for (i in 0 until size) {
                val index = (next - 1 - i + capacity) % capacity
                if (position < first[index] || position >= end[index]) continue
                if (source[index] == NONE) return NONE
                if (result == NONE) {
                    result = positionFrom(index, position)
                    if (result == NONE) return NONE
                    owner = generations[index]
                    originFrame = anchorFrames[index]
                    originUs = source[index]
                } else if (generations[index] != owner ||
                    anchorFrames[index] != originFrame || source[index] != originUs) return NONE
                position = minOf(limit, end[index])
                found = true
                break
            }
            if (!found) return NONE
        }
        generation = owner
        return result
    }

    private fun positionFrom(index: Int, frame: Long): Long {
        val start = source[index]
        val delta = frame - anchorFrames[index]
        if (start == NONE || delta < 0 || delta > Long.MAX_VALUE / 1_000_000L) return NONE
        val us = delta * 1_000_000L / sampleRate
        return if (start > Long.MAX_VALUE - us) NONE else start + us
    }

    companion object { const val NONE = Long.MIN_VALUE }
}

/** Wire timestamps may repeat across AAC capture batches. Only decoded frames advance the
 * source cursor. After unknown loss, a repeated stamp cannot establish which samples survived. */
internal class AudioSourceCursor {
    private var previousWire = NONE
    private var anchorUs = NONE
    private var frames = 0L
    private var rate = 0
    private var generation = NONE
    private var lost = false

    fun reset() { previousWire = NONE; anchorUs = NONE; frames = 0; rate = 0; generation = NONE; lost = false }
    fun invalidate() { anchorUs = NONE; lost = true }
    /** The first surviving stamp may itself belong to a partially lost capture batch. */
    fun skipBatch(wireUs: Long, owner: Long) {
        generation = owner
        previousWire = wireUs
        anchorUs = NONE
        frames = 0
        lost = true
    }

    fun advance(wireUs: Long, decodedFrames: Int, sampleRate: Int, owner: Long): Long {
        if (wireUs < 0 || wireUs > Long.MAX_VALUE / 1000 || decodedFrames <= 0 || sampleRate <= 0 || owner == NONE) {
            invalidate()
            return NONE
        }
        if (owner != generation) { reset(); generation = owner }
        if (sampleRate != rate) { anchorUs = NONE; rate = sampleRate; frames = 0 }
        if (lost && wireUs == previousWire) return NONE
        if (anchorUs == NONE || wireUs != previousWire) {
            val predicted = position()
            // Only one microsecond of integer timestamp quantization is equivalent.
            // A genuinely different wire offset remains visible instead of being aligned away.
            if (predicted == NONE || wireUs - predicted !in -1L..1L) {
                anchorUs = wireUs
                frames = 0
            }
            lost = false
        }
        previousWire = wireUs
        val result = position()
        if (frames > Long.MAX_VALUE - decodedFrames) { invalidate(); return NONE }
        frames += decodedFrames
        return result
    }

    private fun position(): Long {
        if (anchorUs == NONE || rate <= 0 || frames > Long.MAX_VALUE / 1_000_000) return NONE
        val duration = frames * 1_000_000L / rate
        return if (anchorUs > Long.MAX_VALUE - duration) NONE else anchorUs + duration
    }
    companion object { const val NONE = Long.MIN_VALUE }
}

/** Codec PTS remains decoder-local; this exact-key ring carries separate original wire PTS.
 * Output may split an AU; repeated output keys continue the decoded sample cursor. */
internal class AacSourceTimeline(private val capacity: Int = 256) {
    private val codec = LongArray(capacity)
    private val wire = LongArray(capacity)
    private val owners = LongArray(capacity)
    private val inputSequences = LongArray(capacity)
    private var next = 0
    private var size = 0
    private var lastInputSequence = Long.MIN_VALUE
    private var lastOwner = Long.MIN_VALUE
    private val cursor = AudioSourceCursor()
    var generation = Long.MIN_VALUE
        private set
    @Volatile private var bypassedOutput = false

    @Synchronized fun clear() {
        if (size > 0) bypassedOutput = true
        next = 0; size = 0; cursor.invalidate(); generation = Long.MIN_VALUE
    }
    /** Disabled playback must not bridge uncounted decoded duration on re-enable.
     * The disabled path only sets a primitive gap latch; it does not search/lock a ring. */
    fun outputBypassed() { bypassedOutput = true }
    @Synchronized fun append(codecUs: Long, wireUs: Long, owner: Long, inputSequence: Long) {
        codec[next] = codecUs; wire[next] = wireUs; owners[next] = owner; inputSequences[next] = inputSequence
        next = (next + 1) % capacity
        if (size < capacity) size++
    }
    @Synchronized fun decoded(codecUs: Long, frames: Int, rate: Int): Long {
        generation = Long.MIN_VALUE
        for (i in 0 until size) {
            val index = (next - 1 - i + capacity) % capacity
            if (codec[index] == codecUs) {
                val sequence = inputSequences[index]
                val owner = owners[index]
                val sameStart = lastOwner == Long.MIN_VALUE || lastOwner == owner
                val missingInput = if (lastOwner != owner) sequence != 0L else
                    sequence != lastInputSequence && sequence != lastInputSequence + 1
                if (missingInput || (sameStart && bypassedOutput)) cursor.skipBatch(wire[index], owner)
                bypassedOutput = false
                lastInputSequence = sequence
                lastOwner = owner
                generation = owner
                return cursor.advance(wire[index], frames, rate, owner)
            }
        }
        cursor.invalidate()
        bypassedOutput = true
        return Long.MIN_VALUE
    }
}
