package com.andrerinas.openheadunit.decoder.video

/** Fixed storage keyed by codec-local PTS, never by phone uptime. Overflow loses measurement,
 * not ownership: an evicted/repeated key cannot be guessed from a neighbouring frame.
 * Caller supplies scratch so feed/output/render callbacks allocate nothing per lookup.
 */
internal class VideoTimingMetadata(private val capacity: Int = 256) {
    class Sample {
        var generation = 0L
        var sourceTimeUs = Long.MIN_VALUE
        var arrivalNs = 0L
        var requestedNs = 0L
    }
    private val occupied = BooleanArray(capacity)
    private val keys = LongArray(capacity)
    private val generations = LongArray(capacity)
    private val sources = LongArray(capacity)
    private val arrivals = LongArray(capacity)
    private val requests = LongArray(capacity)
    private var next = 0

    init { require(capacity > 0) }

    @Synchronized fun clear() {
        occupied.fill(false)
        next = 0
    }

    @Synchronized fun remove(key: Long) {
        for (i in 0 until capacity) if (occupied[i] && keys[i] == key) occupied[i] = false
    }

    @Synchronized fun put(key: Long, generation: Long, sourceTimeUs: Long, arrivalNs: Long, requestedNs: Long = 0L) {
        // Duplicate local timestamps are ambiguous (some codecs rewrite all output to zero).
        // Invalidate both rather than attributing the wrong wire timestamp to the picture.
        for (i in 0 until capacity) {
            if (occupied[i] && keys[i] == key) {
                sources[i] = Long.MIN_VALUE
                arrivals[i] = 0L
                requests[i] = 0L
                return
            }
        }
        val slot = next
        next = (next + 1) % capacity
        keys[slot] = key
        generations[slot] = generation
        sources[slot] = sourceTimeUs
        arrivals[slot] = arrivalNs
        requests[slot] = requestedNs
        occupied[slot] = true
    }

    @Synchronized fun take(key: Long, target: Sample): Boolean {
        for (i in 0 until capacity) {
            if (occupied[i] && keys[i] == key) {
                occupied[i] = false
                target.generation = generations[i]
                target.sourceTimeUs = sources[i]
                target.arrivalNs = arrivals[i]
                target.requestedNs = requests[i]
                return arrivals[i] != 0L
            }
        }
        return false
    }
}

/** Bounded polling is only used on a decoder worker, never the socket/ACK worker. */
internal object VideoPresentationWait {
    const val SLICE_NS = 5_000_000L
    fun sliceNs(deadlineNs: Long, nowNs: Long): Long = when {
        deadlineNs == 0L || deadlineNs == Long.MIN_VALUE || deadlineNs <= nowNs -> 0L
        nowNs <= Long.MAX_VALUE - SLICE_NS && deadlineNs > nowNs + SLICE_NS -> SLICE_NS
        else -> (deadlineNs - nowNs).coerceIn(0L, SLICE_NS)
    }
}
