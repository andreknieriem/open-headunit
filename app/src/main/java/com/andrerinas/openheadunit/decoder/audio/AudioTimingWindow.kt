package com.andrerinas.openheadunit.decoder.audio

/** Single-owner counters. Recording allocates nothing; only a once-per-second snapshot copies
 * arrays. Times describe call boundaries, not CPU ownership: monitor entry includes preemption,
 * and sleep overshoot includes timer delivery as well as time waiting to run. */
internal class AudioTimingWindow {
    enum class Phase {
        BANK_ENTER, BANK_RENDER, FEED_ENTER, CONVERT, BANK_WRITE_ENTER, BANK_WRITE,
        WRITE_ATTEMPT, HEAD_QUERY, NATIVE_WRITE, RETRY_SLEEP, RECEIVE
    }

    private val counts = LongArray(Phase.values().size)
    private val totals = LongArray(counts.size)
    private val maxima = LongArray(counts.size)
    private var firstTrack = -1
    private var firstWritten = -1L
    private var firstPlayed = -1L
    private var firstOutputNs = -1L
    private var lastWritten = -1L
    private var lastPlayed = -1L
    private var lastOutputNs = -1L
    private var continuous = true
    private var queueMin = Long.MAX_VALUE
    private var queueMax = -1L
    private var writes = 0L
    private var zeroWrites = 0L
    private var rejectedByAdmission = 0L
    private var errors = 0L
    private var acceptedShorts = 0L

    fun record(phase: Phase, durationNs: Long) {
        val index = phase.ordinal
        val elapsed = durationNs.coerceAtLeast(0)
        counts[index]++
        totals[index] += elapsed
        maxima[index] = maxOf(maxima[index], elapsed)
    }

    fun writeResult(result: Int, admissionRejected: Boolean) {
        writes++
        if (result == 0) zeroWrites++
        if (result < 0) errors++
        if (admissionRejected) rejectedByAdmission++
        acceptedShorts += result.coerceAtLeast(0)
    }

    /** Reuse an existing head read; never poll the track for diagnostics. A new track or a
     * flush invalidates rate deltas for this window rather than joining unrelated clocks. */
    fun output(track: Int, written: Long, played: Long, capacity: Int, nowNs: Long) {
        if (written < 0 || played < 0 || played > written) { continuous = false; return }
        if (firstTrack < 0) {
            firstTrack = track
            firstWritten = written
            firstPlayed = played
            firstOutputNs = nowNs
        } else if (track != firstTrack || written < lastWritten || played < lastPlayed) {
            continuous = false
        }
        lastWritten = written
        lastPlayed = played
        lastOutputNs = nowNs
        val queued = written - played
        if (queued > capacity) continuous = false
        queueMin = minOf(queueMin, queued)
        queueMax = maxOf(queueMax, queued)
    }

    data class Snapshot(
        val counts: LongArray, val totalNs: LongArray, val maxNs: LongArray,
        val track: Int, val continuous: Boolean, val outputSpanNs: Long,
        val writtenDelta: Long, val headDelta: Long, val queueMin: Long, val queueMax: Long,
        val writes: Long, val zeroWrites: Long, val admissionRejected: Long,
        val errors: Long, val acceptedShorts: Long
    )

    fun snapshot(): Snapshot {
        val valid = continuous && firstTrack >= 0 && lastOutputNs > firstOutputNs
        val result = Snapshot(counts.copyOf(), totals.copyOf(), maxima.copyOf(), firstTrack,
            valid, if (valid) lastOutputNs - firstOutputNs else -1,
            if (valid) lastWritten - firstWritten else -1,
            if (valid) lastPlayed - firstPlayed else -1,
            if (queueMin == Long.MAX_VALUE) -1 else queueMin, queueMax,
            writes, zeroWrites, rejectedByAdmission, errors, acceptedShorts)
        counts.fill(0); totals.fill(0); maxima.fill(0)
        firstTrack = -1; firstWritten = -1; firstPlayed = -1; firstOutputNs = -1
        lastWritten = -1; lastPlayed = -1; lastOutputNs = -1; continuous = true
        queueMin = Long.MAX_VALUE; queueMax = -1
        writes = 0; zeroWrites = 0; rejectedByAdmission = 0; errors = 0; acceptedShorts = 0
        return result
    }
}
