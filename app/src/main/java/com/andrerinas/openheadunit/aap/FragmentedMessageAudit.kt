package com.andrerinas.openheadunit.aap

/** Checks the declared plaintext message length, including its type, after TLS unwrap. */
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
