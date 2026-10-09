package com.andrerinas.openheadunit.aap

/**
 * Cross-checks a fragment run against the total plaintext length declared by its first fragment.
 * A missing middle fragment otherwise leaves a plausible FIRST, middle, LAST sequence: the video
 * assembler can hand a damaged access unit to the decoder without noticing the missing bytes.
 *
 * Count bytes after TLS unwrap, including the message type, but excluding AAP headers and TLS
 * overhead. This contract requires FIRST's declared total to describe that same plaintext.
 * [AapRead] supplies the delivered buffer limit, not encrypted length or backing-array capacity.
 *
 * ### Measurement context
 *
 * The earlier UNISOC MT50 round recorded these encrypted-length deltas (declared minus observed):
 *
 * | fragments | 2 | 3 | 4 | 5 | 7 | 8 |
 * |---|---|---|---|---|---|---|
 * | delta in bytes | -58 | -87 | -116 | -145 | -203 | -232 |
 *
 * That was 29 extra bytes per fragment across two codecs, two sessions, and both VIDEO and
 * MUSIC_PLAYBACK. Comparing a fixed whole-run delta produced ten false DELTA_CHANGED reports
 * in the first 200 ms and exhausted the print budget. Learning a per-fragment delta addressed
 * that encrypted-input problem. The plaintext audit instead excludes the TLS bytes at unwrap;
 * 29 is a historical observation, not a TLS constant to subtract from future packets.
 *
 * FragmentedMessageAuditTest carries forward the recorded fragment-count sequence, missing-middle
 * case, channel isolation and reset scenarios at the new input boundary. AapReadPlaintextAuditTest
 * exercises both readers with synthetic unwrap results of different lengths. Neither is a fresh
 * hardware measurement or proof of the declared-length convention on every sender.
 *
 * To compare a sender capture, record channel, FIRST's declared total, fragment count, encrypted
 * body lengths and actual unwrap limits for the same run. Compare the plaintext sum including the
 * type/timestamp once; do not use buffer capacity or assume a fixed encrypted overhead. Repeat
 * with different frame sizes and codecs before attributing a mismatch to missing payload.
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

        // Keep the byte discrepancy explicit in exported logs, alongside the two measured totals.
        override fun toString(): String =
            "channel=$channel fragments=$fragments declaredTotal=$declaredTotal observed=$observedTotal delta=$delta"
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
