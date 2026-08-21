package com.andrerinas.openheadunit.aap

/**
 * Checks a run of message fragments against the total size the first one declared.
 *
 * Every fragmented AAP message begins with a first fragment carrying a 4-byte total size, which both
 * readers have always read and thrown away - one into a field called `fragmentSizeBuffer`, the other
 * into one called `skipBuffer`. That number is the only cross-check the protocol offers, and without
 * it one corruption mode is completely invisible: a *middle* fragment that never arrives leaves the
 * run looking intact to [VideoFragmentAssembler] - a first, some middles, a last, in order - so the
 * frame is assembled with a hole in it and handed to the decoder as though it were whole. There is
 * no log, no keyframe request, and nothing to see afterwards except a smeared picture.
 *
 * What the declared total is counted in is not documented anywhere we can check, and it need not be:
 * this class learns the convention from the stream.
 *
 * ### The convention is per fragment, not per run
 *
 * The difference between the declared total and the sum of the fragment lengths scales with the
 * number of fragments, because each fragment carries its own framing and encryption overhead in the
 * `encLen` this class is given. Hardware measured it at a flat **29 bytes per fragment**:
 *
 * ```
 * fragments=2 -> delta=-58    fragments=5 -> delta=-145
 * fragments=3 -> delta=-87    fragments=7 -> delta=-203
 * fragments=4 -> delta=-116   fragments=8 -> delta=-232
 * ```
 *
 * — exact in all 20+ observations of a hardware round, across two codecs, two sessions and two
 * channels (VIDEO and MUSIC_PLAYBACK carried the same 29). An earlier version of this class learned
 * the *whole-run* difference instead and compared every later run against it, so every message whose
 * fragment count differed from the establishing message's tripped the warning: 10 false
 * `DELTA_CHANGED` lines in the first 200ms of every session on that rig, which then exhausted the
 * report budget and left the check silent for the rest of it. The one instrument that can see a
 * missing middle fragment was therefore muted during exactly the windows an artifact was visible.
 *
 * So the expectation is scaled to each run's own fragment count. The comparison is done by
 * cross-multiplication rather than division, so it stays exact whatever the convention turns out to
 * be - including one that does not divide evenly, which the measured 29 does but a future stream
 * might not.
 *
 * A missing fragment moves the difference by that fragment's own length, which no amount of scaling
 * explains away, and that is the deviation worth a line in a log.
 *
 * Per-channel, because video, audio and control messages interleave on one connection and a run on
 * one channel says nothing about a run on another.
 *
 * Pure: no clock, no logging, no allocation on the healthy path. How often a report may be *printed*
 * is [AuditReportPolicy]'s decision, not this class's.
 */
class FragmentedMessageAudit(channelCount: Int = DEFAULT_CHANNEL_COUNT) {

    /** Why a run is worth reporting. Runs that match the established convention are not reported. */
    enum class Outcome {
        /** The first complete run on this channel. Its difference becomes the expected one. */
        FIRST_OBSERVATION,

        /** A complete run whose difference is not the one this channel had settled on. */
        DELTA_CHANGED,

        /** A middle or last fragment arrived with no run open. */
        ORPHANED_FRAGMENT,

        /** A new message started while a run was still open, so the previous one lost its tail. */
        TRUNCATED_RUN,
    }

    /**
     * A run worth reporting.
     *
     * [delta] is `declaredTotal - observedTotal`. [expectedDelta] is what a run of *this* fragment
     * count should have shown, scaled from the establishing run, or null if the channel has not
     * settled yet or there is nothing to expect. [perFragmentDelta] is the learned constant behind
     * that expectation, present only when the establishing run divided evenly - it is the number a
     * human can sanity-check at a glance, and the reason it may be absent is that nothing here
     * requires the convention to divide.
     */
    data class Result(
        val channel: Int,
        val outcome: Outcome,
        val declaredTotal: Int,
        val observedTotal: Int,
        val fragments: Int,
        val delta: Int,
        val expectedDelta: Int?,
        val perFragmentDelta: Int?,
    ) {
        override fun toString(): String = buildString {
            append("channel=$channel ")
            append("fragments=$fragments ")
            append("declaredTotal=$declaredTotal observed=$observedTotal delta=$delta")
            expectedDelta?.let { append(" expectedDelta=$it") }
            perFragmentDelta?.let { append(" perFragment=$it") }
        }
    }

    private val open = BooleanArray(channelCount)
    private val declared = IntArray(channelCount)
    private val observed = IntArray(channelCount)
    private val fragments = IntArray(channelCount)

    /**
     * The establishing run's difference and fragment count, per channel.
     *
     * Kept as the pair rather than as a ratio so the comparison can cross-multiply and stay exact.
     * A completed fragmented run always has at least two fragments, so a zero here means "this
     * channel has not settled yet" and cannot collide with a real observation.
     */
    private val establishedDelta = IntArray(channelCount)
    private val establishedFragments = IntArray(channelCount)

    /**
     * Applies one message.
     *
     * [flags] is the raw header flags byte: bit 0 marks a first fragment, bit 1 a last one, so an
     * unfragmented message has both and a middle fragment has neither. [encLen] is the message's own
     * encrypted body length, and [declaredTotal] is the 4-byte total that accompanies a first
     * fragment - ignored for every other kind.
     *
     * Returns null when there is nothing to report, which is the overwhelming majority of calls.
     */
    fun onMessage(channel: Int, flags: Int, encLen: Int, declaredTotal: Int): Result? {
        if (channel < 0 || channel >= open.size) return null

        val isFirst = flags and FLAG_BIT_FIRST != 0
        val isLast = flags and FLAG_BIT_LAST != 0

        if (isFirst) {
            val truncated = if (open[channel]) {
                val seen = fragments[channel]
                Result(
                    channel = channel,
                    outcome = Outcome.TRUNCATED_RUN,
                    declaredTotal = declared[channel],
                    observedTotal = observed[channel],
                    fragments = seen,
                    delta = declared[channel] - observed[channel],
                    expectedDelta = expectedFor(channel, seen),
                    perFragmentDelta = perFragmentFor(channel),
                )
            } else {
                null
            }

            if (isLast) {
                // Unfragmented: nothing to audit, and no run to leave open.
                open[channel] = false
            } else {
                open[channel] = true
                declared[channel] = declaredTotal
                observed[channel] = encLen
                fragments[channel] = 1
            }
            return truncated
        }

        if (!open[channel]) {
            return Result(
                channel = channel,
                outcome = Outcome.ORPHANED_FRAGMENT,
                declaredTotal = 0,
                observedTotal = encLen,
                fragments = 0,
                delta = 0,
                // Nothing arrived that a convention could have predicted, so quoting one here would
                // only invite a comparison that means nothing.
                expectedDelta = null,
                perFragmentDelta = null,
            )
        }

        observed[channel] += encLen
        fragments[channel]++

        if (!isLast) return null

        open[channel] = false
        val runFragments = fragments[channel]
        val delta = declared[channel] - observed[channel]

        if (establishedFragments[channel] == 0) {
            establishedDelta[channel] = delta
            establishedFragments[channel] = runFragments
            return Result(
                channel = channel,
                outcome = Outcome.FIRST_OBSERVATION,
                declaredTotal = declared[channel],
                observedTotal = observed[channel],
                fragments = runFragments,
                delta = delta,
                expectedDelta = null,
                perFragmentDelta = perFragmentFor(channel),
            )
        }

        // Exact: delta/runFragments == establishedDelta/establishedFragments, without dividing.
        // Long because a corrupt declaredTotal can be arbitrarily large and the product must not wrap.
        if (delta.toLong() * establishedFragments[channel] ==
            establishedDelta[channel].toLong() * runFragments
        ) {
            return null
        }

        return Result(
            channel = channel,
            outcome = Outcome.DELTA_CHANGED,
            declaredTotal = declared[channel],
            observedTotal = observed[channel],
            fragments = runFragments,
            delta = delta,
            expectedDelta = expectedFor(channel, runFragments),
            perFragmentDelta = perFragmentFor(channel),
        )
    }

    /**
     * What a run of [runFragments] fragments should have shown on this channel, or null if the
     * channel has not settled.
     *
     * Truncating division, and for display only - every decision is made by the exact
     * cross-multiplication above.
     */
    private fun expectedFor(channel: Int, runFragments: Int): Int? {
        val establishedCount = establishedFragments[channel]
        if (establishedCount == 0) return null
        return (establishedDelta[channel].toLong() * runFragments / establishedCount).toInt()
    }

    /** The learned per-fragment constant, or null when the establishing run did not divide evenly. */
    private fun perFragmentFor(channel: Int): Int? {
        val establishedCount = establishedFragments[channel]
        if (establishedCount == 0) return null
        val total = establishedDelta[channel]
        if (total % establishedCount != 0) return null
        return total / establishedCount
    }

    /** Forgets every channel's run and learned convention. For a new session. */
    fun reset() {
        open.fill(false)
        declared.fill(0)
        observed.fill(0)
        fragments.fill(0)
        establishedDelta.fill(0)
        establishedFragments.fill(0)
    }

    companion object {
        /** Channel ids run 0..13 (see `protocol/Channel`); a little headroom costs nothing. */
        const val DEFAULT_CHANNEL_COUNT = 16

        /** Header flags bit marking the first fragment of a message. */
        const val FLAG_BIT_FIRST = 0x01

        /** Header flags bit marking the last fragment of a message. */
        const val FLAG_BIT_LAST = 0x02
    }
}
