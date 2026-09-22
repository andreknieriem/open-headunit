package com.andrerinas.openheadunit.secondscreen

/**
 * One receiver's queue of forwarded units, bounded so a slow receiver never stalls the video lane.
 *
 * On overflow it empties and waits for the next keyframe, since a delta frame without its
 * reference only smears. Not thread-safe: the owner synchronises.
 */
class StreamBacklog(private val maxUnits: Int, private val maxBytes: Int) {

    /** [config] marks SPS/PPS, which always pass: a keyframe is undecodable without them. */
    class Chunk(val bytes: ByteArray, val keyframe: Boolean, val config: Boolean = false)

    enum class Offer { QUEUED, SKIPPED_UNTIL_KEYFRAME, OVERFLOWED }

    private val queue = ArrayDeque<Chunk>()
    private var queuedBytes = 0

    /** A receiver that joins mid-stream starts at a keyframe, like one that just overflowed. */
    var awaitingKeyframe = true
        private set

    val size: Int get() = queue.size

    fun offer(unit: Chunk): Offer {
        if (awaitingKeyframe && !unit.keyframe && !unit.config) return Offer.SKIPPED_UNTIL_KEYFRAME
        if (queue.size + 1 > maxUnits || queuedBytes + unit.bytes.size > maxBytes) {
            clear()
            awaitingKeyframe = true
            if ((unit.keyframe || unit.config) && unit.bytes.size <= maxBytes) push(unit)
            return Offer.OVERFLOWED
        }
        push(unit)
        return Offer.QUEUED
    }

    fun poll(): Chunk? = queue.removeFirstOrNull()?.also { queuedBytes -= it.bytes.size }

    private fun push(unit: Chunk) {
        queue.addLast(unit)
        queuedBytes += unit.bytes.size
        if (unit.keyframe) awaitingKeyframe = false
    }

    private fun clear() {
        queue.clear()
        queuedBytes = 0
    }
}
