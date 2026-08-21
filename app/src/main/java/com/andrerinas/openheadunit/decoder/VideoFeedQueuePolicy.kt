package com.andrerinas.openheadunit.decoder

/**
 * How much video may wait between the transport and the codec, and how long the feed thread waits
 * for the codec to take it.
 *
 * These two numbers are one decision and are kept together because separating them is what caused
 * issue #830. [VideoDecoder] used to hold a flat twelve frames - "around 200ms" by its own comment -
 * in front of a feed thread that would wait [INPUT_DEQUEUE_PATIENCE_MS], a third of a second, for a
 * free input buffer. So on any codec stall between those two figures the queue was shedding frames
 * while the thread in front of it was still deliberately being patient. The 200ms had been chosen
 * against touch latency and the 300ms against codec behaviour, and nothing reconciled them.
 *
 * A shed frame is not a dropped frame in the harmless sense: everything after it predicts from it,
 * so the picture drifts washed-out and blocky until a keyframe arrives, and the phone sends those on
 * a fixed period measured at ~69s. Roughly 35 seconds of visible corruption, on average, for a stall
 * of a couple of hundred milliseconds.
 *
 * ### Why depth is cheaper than it looks
 *
 * The instinct that a deep queue is latency is right for most pipelines and wrong for this one. A
 * backlog here drains in milliseconds once the codec unblocks, and the output thread then discards
 * everything but the newest decoded frame on the way to the surface. So the cost is one catch-up
 * jump, not lasting lag - and a discarded *decoded* frame breaks no prediction, which is the whole
 * point. That is the model the protocol layer already describes: bound the backlog where it costs
 * nothing rather than by having the phone send less.
 *
 * The exception is a decoder persistently slower than the stream, where nothing ever drains and the
 * depth is real latency. That is the forced software-decoding path, which is already a deliberate
 * choice by someone whose hardware decoder does not work.
 */
object VideoFeedQueuePolicy {

    /**
     * How long the feed thread waits, in total, for the codec to free an input buffer before giving
     * up on a frame.
     *
     * Measured: 30ms reported a full queue within a second of every start on hardware that then
     * decoded at full rate. The wait also aborts as soon as the decoder stops.
     */
    const val INPUT_DEQUEUE_PATIENCE_MS = 300L

    /**
     * How much video the queue holds, in milliseconds.
     *
     * Has to clear [INPUT_DEQUEUE_PATIENCE_MS], or the queue sheds frames the feed thread has not
     * given up on yet. 500ms is that plus margin. Widening it costs memory and, on a decoder that
     * never catches up, latency; narrowing it below the patience reopens #830.
     */
    const val FRAME_QUEUE_MS = 500

    /**
     * Floor on the derived depth.
     *
     * `fpsLimit` is the user's cap, not a rate negotiated with the phone - the decoder has no access
     * to the real one - so it can understate what is actually arriving. The floor means a low cap
     * cannot produce a queue too shallow to hold the patience regardless.
     */
    const val MIN_CAPACITY = 15

    /**
     * Ceiling on the derived depth.
     *
     * Every slot carries a pooled buffer that grows to the largest frame it has held, so a queue
     * full of oversized keyframes is the worst case worth bounding. It also bounds the guarantee
     * below: past about 133fps this ceiling, not [FRAME_QUEUE_MS], decides the depth, and the queue
     * would no longer cover the patience. Android Auto negotiates 30 or 60.
     */
    const val MAX_CAPACITY = 40

    /** Frames the queue holds at [fpsLimit], clamped to [MIN_CAPACITY]..[MAX_CAPACITY]. */
    fun capacityFor(fpsLimit: Int): Int =
        (safeRate(fpsLimit) * FRAME_QUEUE_MS / 1000).coerceIn(MIN_CAPACITY, MAX_CAPACITY)

    /** How much video [capacityFor] holds at [fpsLimit], in milliseconds. */
    fun heldMsAt(fpsLimit: Int): Long =
        capacityFor(fpsLimit) * 1000L / safeRate(fpsLimit)

    /**
     * [fpsLimit] comes from stored settings, so nothing structurally stops it being zero or
     * negative - and this runs on the feed thread's start path, where an arithmetic exception would
     * take the decoder down rather than degrade it. A nonsense rate falls back to the floor.
     */
    private fun safeRate(fpsLimit: Int): Int = if (fpsLimit > 0) fpsLimit else MIN_CAPACITY
}
