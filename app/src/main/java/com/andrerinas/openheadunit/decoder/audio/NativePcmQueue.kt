package com.andrerinas.openheadunit.decoder.audio

/** Bounded ownership transfer for converted PCM and ordered Stop markers. Producer storage
 * can be reused immediately after offer. A full queue rejects the new packet atomically;
 * it never overwrites an older segment's tail or silently removes a Stop boundary. */
internal class NativePcmQueue(private val capacityFrames: Int = 72_000) {
    private val pcm = ShortArray(capacityFrames * 2)
    private val lengths = IntArray(256)
    private val arrivals = LongArray(256)
    private val packetFrames = IntArray(256)
    private var read = 0
    private var count = 0
    private var eventRead = 0
    private var events = 0
    private var eventStarted = false
    @Volatile var rejectedFrames = 0L
        private set

    class Read {
        var frames = 0 // -1 is an ordered Stop; 0 means empty
        var packetFrames = 0
        var arrivalMs = 0L
    }

    @Synchronized fun offer(source: ShortArray, samples: Int, nowMs: Long): Boolean {
        require(samples in 0..source.size && samples % 2 == 0)
        if (samples == 0) return true
        // Reserve a descriptor for Stop even when no more PCM can be accepted.
        if (samples > pcm.size - count || events >= lengths.size - 1) {
            rejectedFrames += samples / 2
            return false
        }
        val tail = (read + count) % pcm.size
        val first = minOf(samples, pcm.size - tail)
        System.arraycopy(source, 0, pcm, tail, first)
        System.arraycopy(source, first, pcm, 0, samples - first)
        val event = (eventRead + events) % lengths.size
        lengths[event] = samples
        arrivals[event] = nowMs
        packetFrames[event] = samples / 2
        count += samples
        events++
        return true
    }

    @Synchronized fun stop() {
        if (events > 0 && lengths[(eventRead + events - 1) % lengths.size] == -1) return
        // offer reserves this slot; consecutive Stops coalesce rather than growing the queue.
        check(events < lengths.size)
        val event = (eventRead + events) % lengths.size
        lengths[event] = -1
        events++
    }

    @Synchronized fun poll(destination: ShortArray, result: Read) {
        poll(destination.size, result) { sourceOffset, destinationOffset, length ->
            System.arraycopy(pcm, sourceOffset, destination, destinationOffset, length)
        }
    }

    @Synchronized fun poll(destination: TimeStretchBlock, result: Read) {
        poll(destination.size, result) { sourceOffset, destinationOffset, length ->
            destination.put(pcm, sourceOffset, length, destinationOffset)
        }
    }

    // The queue owns PCM until this bounded transfer finishes. Only the worker's
    // private direct block crosses JNI; the producer can keep filling this ring.
    private inline fun poll(capacity: Int, result: Read, copy: (Int, Int, Int) -> Unit) {
        result.frames = 0
        result.packetFrames = 0
        if (events == 0) return
        val length = lengths[eventRead]
        if (length == -1) {
            result.frames = -1
            advanceEvent()
            return
        }
        val samples = minOf(capacity / 2 * 2, length)
        // A destination smaller than one stereo frame must not consume the packet's
        // one-shot arrival metadata before any PCM can be delivered.
        if (samples == 0) return
        if (!eventStarted) {
            result.packetFrames = packetFrames[eventRead]
            result.arrivalMs = arrivals[eventRead]
            eventStarted = true
        }
        val first = minOf(samples, pcm.size - read)
        copy(read, 0, first)
        if (samples > first) copy(0, first, samples - first)
        read = (read + samples) % pcm.size
        count -= samples
        lengths[eventRead] -= samples
        result.frames = samples / 2
        if (lengths[eventRead] == 0) advanceEvent()
    }

    private fun advanceEvent() {
        eventRead = (eventRead + 1) % lengths.size
        events--
        eventStarted = false
    }
    @Synchronized fun reject(samples: Int) { rejectedFrames += samples / 2 }
    @Synchronized fun isEmpty() = events == 0
    @Synchronized fun depthFrames() = count / 2
}
