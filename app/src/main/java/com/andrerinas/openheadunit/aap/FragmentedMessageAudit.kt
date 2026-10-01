package com.andrerinas.openheadunit.aap

/**
 * Cross-checks a fragment run against the total plaintext length declared by its first fragment.
 * A missing middle fragment otherwise leaves a plausible FIRST, middle, LAST sequence: the video
 * assembler can hand a damaged access unit to the decoder without noticing the missing bytes.
 *
 * Count bytes after TLS unwrap, including the message type, but excluding AAP headers and TLS
 * overhead. Comparing an encrypted whole-run delta across different fragment counts once caused
 * false alarms; the previous audit compensated by learning overhead per fragment (29 bytes in
 * captured sessions). Plaintext accounting removes that uncertainty, so no learned baseline or tolerance
 * should be reintroduced here. Even a one-byte difference means the completed run is not intact.
 *
 * State is per channel because audio, video and control messages interleave on one connection.
 * This class reports findings only; [AapRead] owns logging and [AuditRecoveryPolicy] owns repair.
 * A log budget must never decide whether corrupted data is accepted or recovery is requested.
 */
class FragmentedMessageAudit(channelCount: Int = DEFAULT_CHANNEL_COUNT) {
    enum class Outcome { DELTA_CHANGED, ORPHANED_FRAGMENT, TRUNCATED_RUN }

    data class Result(
        val channel: Int,
        val outcome: Outcome,
        val declaredTotal: Int,
        val observedTotal: Long,
        val fragments: Int
    ) {
        val delta: Long get() = declaredTotal.toLong() - observedTotal
    }

    private val open = BooleanArray(channelCount)
    private val declared = IntArray(channelCount)
    private val observed = LongArray(channelCount)
    private val fragments = IntArray(channelCount)

    /**
     * [declaredTotal] is meaningful only on FIRST without LAST. [plaintextLength] is the actual
     * delivered TLS output length, never backing-array capacity. A new FIRST retires an unfinished
     * run and reports it; the replacement run still starts, so one fault cannot poison the channel.
     */
    fun onMessage(channel: Int, flags: Int, plaintextLength: Int, declaredTotal: Int): Result? {
        if (channel !in open.indices) return null
        val first = flags and FLAG_BIT_FIRST != 0
        val last = flags and FLAG_BIT_LAST != 0
        if (first) {
            val previous = if (open[channel]) result(channel, Outcome.TRUNCATED_RUN) else null
            open[channel] = !last
            declared[channel] = declaredTotal
            observed[channel] = plaintextLength.toLong()
            fragments[channel] = 1
            return previous
        }
        if (!open[channel]) return Result(channel, Outcome.ORPHANED_FRAGMENT, 0, plaintextLength.toLong(), 0)
        observed[channel] = observed[channel] + plaintextLength.toLong()
        fragments[channel]++
        if (!last) return null
        open[channel] = false
        return if (observed[channel] != declared[channel].toLong()) result(channel, Outcome.DELTA_CHANGED) else null
    }

    private fun result(channel: Int, outcome: Outcome) =
        Result(channel, outcome, declared[channel], observed[channel], fragments[channel])

    fun reset() {
        open.fill(false)
        declared.fill(0)
        observed.fill(0)
        fragments.fill(0)
    }

    companion object {
        const val DEFAULT_CHANNEL_COUNT = 256
        const val FLAG_BIT_FIRST = 0x01
        const val FLAG_BIT_LAST = 0x02
    }
}
