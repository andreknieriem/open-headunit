package com.andrerinas.openheadunit.decoder.audio

/** Serialized-owner diagnostics. All slots/arrays are allocated with the owner, never per
 * audio block. Volatile publication transfers a complete slot even on 32-bit Android.
 * The consumer formats a slot before releasing it; a full queue loses diagnostics, not PCM. */
internal class AudioProbe(val id: Long, val label: String) {
    enum class Counter {
        OFFERED, REAL_RENDERED, COMPRESSED, DROPPED, RESET_DISCARDED, DEPTH, TARGET,
        OVERFLOWS, BANK_RESETS, STOPS, CONCEALED, REBANKS,
        POLICY_TARGET_RESET, POLICY_PACKET_RESET, POLICY_BUDGET_RESET, POLICY_GAP_RESET,
        BLOCK_NATIVE, BLOCK_ENDED, BLOCK_PREFIX, BLOCK_GAP, BLOCK_FADE, POLICY_INELIGIBLE, POLICY_RESET, PROOF_OK, PROOF_NO_INPUT, PROOF_FEW_CYCLES,
        COOLDOWN, NO_ALLOWANCE, NO_DEBT, RESERVE_GUARD, PROPOSALS, PROPOSED_FRAMES,
        MATCHED, UNMATCHED, SELECTED_FRAMES, ATTACK_DEFERRED, EXECUTED,
        DEBT, EXCESS, ALLOWANCE, RESERVE,
        HEAD_OBSERVATIONS, HEAD_EMPTY, HEAD_GAP_MAX_NS, TRACK_EPOCH, RAW_HEAD, WRITTEN, PLAYED, HEAD_AT_NS, QUEUED, INVALID_HEAD,
        MIXED_FRAMES, WRITE_CALLS, ZERO_WRITES, ADMISSION_REJECTED, WRITE_ERRORS,
        ACCEPTED_SHORTS, SLEEP_REQUEST_MS, SLEEP_1, SLEEP_2, SLEEP_4,
        ACTIVE_CYCLES, SILENT_CYCLES, WAIT_CYCLES, PAUSES, STARTS,
        INPUT_BYTES, INPUT_FRAMES,
        STARTED, ENDED, REBANKING
    }
    enum class Phase { RENDER, BANK_ENTER, BANK_RENDER, MIX_SUM, LIMITER, OVERLAP,
        FEED_ENTER, CONVERT, BANK_WRITE, HEAD_QUERY, NATIVE_WRITE, WRITE_FULL,
        RETRY_SLEEP, OWNER_DIAGNOSTICS }
    class Slot {
        var sequence = 0L; var atNs = 0L; var cpuNs = -1L; var tid = -1
        var dropped = 0L; var boundary = 0
        val values = LongArray(Counter.entries.size)
        val counts = LongArray(Phase.entries.size)
        val totalNs = LongArray(Phase.entries.size)
        val maxNs = LongArray(Phase.entries.size)
    }
    private val values = LongArray(Counter.entries.size)
    private val counts = LongArray(Phase.entries.size)
    private val totalNs = LongArray(Phase.entries.size)
    private val maxNs = LongArray(Phase.entries.size)
    private val slots = Array(4) { Slot() }
    @Volatile private var published = 0L
    @Volatile private var consumed = 0L
    @Volatile var closed = false
        private set
    private var sequence = 0L
    var dropped = 0L
        private set
    private var nextNs = 0L

    fun add(key: Counter, value: Long = 1) { values[key.ordinal] += value }
    fun set(key: Counter, value: Long) { values[key.ordinal] = value }
    fun maximum(key: Counter, value: Long) { values[key.ordinal] = maxOf(values[key.ordinal], value) }
    fun phase(key: Phase, ns: Long) {
        val i = key.ordinal
        counts[i]++; totalNs[i] += ns.coerceAtLeast(0); maxNs[i] = maxOf(maxNs[i], ns)
    }
    fun due(nowNs: Long) = !closed && nowNs >= nextNs
    /** Values and timing counts are cumulative. Never sum nested phase totals. Boundary:
     * 0=periodic, 1=owner close, 2=protocol Stop, 3=bank reset. */
    fun publish(nowNs: Long, cpuNs: Long = -1, tid: Int = -1, boundary: Int = 0) {
        if (closed) return
        sequence++
        nextNs = nowNs + 5_000_000_000L
        if (published - consumed >= slots.size) { dropped++; return }
        val slot = slots[(published % slots.size).toInt()]
        slot.sequence = sequence; slot.atNs = nowNs; slot.cpuNs = cpuNs; slot.tid = tid
        slot.dropped = dropped; slot.boundary = boundary
        values.copyInto(slot.values); counts.copyInto(slot.counts)
        totalNs.copyInto(slot.totalNs); maxNs.copyInto(slot.maxNs)
        published++
    }
    fun close(nowNs: Long, cpuNs: Long = -1, tid: Int = -1) {
        if (closed) return
        publish(nowNs, cpuNs, tid, 1)
        closed = true
    }
    /** Exactly one collector. A consumer must not keep references after this returns. */
    fun drain(accept: (Slot) -> Unit) {
        while (consumed < published) {
            val slot = slots[(consumed % slots.size).toInt()]
            try { accept(slot) } finally { consumed++ }
        }
    }
}
