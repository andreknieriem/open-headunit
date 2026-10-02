package com.andrerinas.openheadunit.aap

/**
 * Compare PCM source and delivery intervals without assuming synchronized device clocks.
 * These are diagnostic observations, not one-way latency or proof of packet loss. Capture
 * scheduling and phone-side pruning can both move source timestamps. Never drives playback.
 */
internal class AudioTimestampMonitor(private val windowUs: Long = 10_000_000L) {
    private var windowStartUs = -1L
    private var previousSourceUs = -1L
    private var previousArrivalUs = -1L
    private var previousDurationUs = 0L
    private var packets = 0
    private var comparablePairs = 0
    private var durationChanges = 0
    private var discontinuities = 0
    private var missingTimestamps = 0
    private var maxSourceGapUs = 0L
    private var maxArrivalGapUs = 0L
    private var maxSourceExcessUs = 0L
    private var maxArrivalExcessUs = 0L
    private var maxDeliveryIncreaseUs = 0L
    private var spikeArrivalUs = -1L
    private var spikeArrivalGapUs = 0L
    private var spikeSourceGapUs = -1L
    private var spikePreviousDurationUs = 0L
    private var spikeDurationUs = 0L

    @Synchronized
    fun onPacket(sourceUs: Long, arrivalUs: Long, durationUs: Long): Report? {
        if (arrivalUs < 0 || durationUs <= 0) return null
        if (previousArrivalUs > arrivalUs) reset()
        if (windowStartUs < 0) windowStartUs = arrivalUs
        packets++
        if (sourceUs <= 0) missingTimestamps++
        if (previousArrivalUs >= 0) {
            val arrivalGap = arrivalUs - previousArrivalUs
            val sourceGap = sourceUs - previousSourceUs
            // Start/Stop reset this monitor. Also rebase unannounced pauses/discontinuities.
            if (arrivalGap > MAX_INTERVAL_US) {
                discontinuities++
            } else {
                maxArrivalGapUs = maxOf(maxArrivalGapUs, arrivalGap)
                // Older senders stamp capture completion; newer ones subtract the captured
                // block's duration. A size change therefore has no single expected PTS delta.
                // Use a conservative arrival budget and exclude the ambiguous PTS comparison.
                val sameDuration = durationUs == previousDurationUs
                if (!sameDuration) durationChanges++
                val arrivalExcess = arrivalGap - maxOf(previousDurationUs, durationUs)
                if (arrivalExcess > maxArrivalExcessUs) {
                    maxArrivalExcessUs = arrivalExcess
                    // Keep the source interval from this exact pair, not the independent
                    // window maximum. Primitive fields avoid per-packet diagnostic allocation.
                    spikeArrivalUs = arrivalUs
                    spikeArrivalGapUs = arrivalGap
                    spikeSourceGapUs = if (sourceUs > 0 && previousSourceUs > 0 &&
                        sourceGap in 1..MAX_INTERVAL_US) sourceGap else -1L
                    spikePreviousDurationUs = previousDurationUs
                    spikeDurationUs = durationUs
                }
                if (sourceUs > 0 && previousSourceUs > 0) {
                    if (sourceGap in 1..MAX_INTERVAL_US) {
                        maxSourceGapUs = maxOf(maxSourceGapUs, sourceGap)
                        if (sameDuration) {
                            comparablePairs++
                            maxSourceExcessUs = maxOf(maxSourceExcessUs, sourceGap - previousDurationUs)
                            maxDeliveryIncreaseUs = maxOf(maxDeliveryIncreaseUs, arrivalGap - sourceGap)
                        }
                    } else {
                        discontinuities++
                    }
                }
            }
        }
        previousSourceUs = sourceUs
        previousArrivalUs = arrivalUs
        previousDurationUs = durationUs
        if (arrivalUs - windowStartUs < windowUs) return null
        val report = Report(packets, comparablePairs, durationChanges, missingTimestamps, discontinuities,
            maxSourceGapUs, maxArrivalGapUs, maxSourceExcessUs, maxArrivalExcessUs, maxDeliveryIncreaseUs,
            if (maxArrivalExcessUs >= 20_000L) ArrivalSpike(spikeArrivalUs, spikeArrivalGapUs,
                spikeSourceGapUs.takeIf { it >= 0 }, spikePreviousDurationUs, spikeDurationUs) else null)
        clearWindow()
        windowStartUs = arrivalUs
        return report
    }

    @Synchronized
    fun reset() {
        windowStartUs = -1L
        previousSourceUs = -1L
        previousArrivalUs = -1L
        previousDurationUs = 0L
        clearWindow()
    }

    private fun clearWindow() {
        packets = 0
        comparablePairs = 0
        durationChanges = 0
        discontinuities = 0
        missingTimestamps = 0
        maxSourceGapUs = 0
        maxArrivalGapUs = 0
        maxSourceExcessUs = 0
        maxArrivalExcessUs = 0
        maxDeliveryIncreaseUs = 0
        spikeArrivalUs = -1L
        spikeArrivalGapUs = 0
        spikeSourceGapUs = -1L
        spikePreviousDurationUs = 0
        spikeDurationUs = 0
    }

    /** One receiver-clock event, suitable for correlation with deferred transport logs.
     * A valid source interval is still not proof of lost samples or one-way latency. */
    data class ArrivalSpike(
        val arrivalUs: Long,
        val arrivalGapUs: Long,
        val sourceGapUs: Long?,
        val previousDurationUs: Long,
        val durationUs: Long
    ) {
        val comparable: Boolean get() = sourceGapUs != null && previousDurationUs == durationUs

        override fun toString(): String = "arrivalSpikeElapsedMs=${arrivalUs / 1000}, " +
            "pairedArrivalGapMs=${arrivalGapUs / 1000}, " +
            "pairedSourceGapMs=${sourceGapUs?.div(1000) ?: "unknown"}, " +
            "pairedPreviousDurationUs=$previousDurationUs, pairedDurationUs=$durationUs, " +
            "pairedComparable=$comparable"
    }

    data class Report(
        val packets: Int,
        val comparablePairs: Int,
        val durationChanges: Int,
        val missingTimestamps: Int,
        val discontinuities: Int,
        val maxSourceGapUs: Long,
        val maxArrivalGapUs: Long,
        val maxSourceExcessUs: Long,
        val maxArrivalExcessUs: Long,
        val maxDeliveryIncreaseUs: Long,
        val arrivalSpike: ArrivalSpike?
    ) {
        val hasGap: Boolean get() = maxArrivalExcessUs >= 20_000L || maxSourceExcessUs >= 20_000L

        override fun toString(): String = "PCM timing: packets=$packets, pairs=$comparablePairs, " +
            "durationChanges=$durationChanges, missingPts=$missingTimestamps, discontinuities=$discontinuities, " +
            "sourceGapMaxMs=${maxSourceGapUs / 1000}, arrivalGapMaxMs=${maxArrivalGapUs / 1000}, " +
            "sourceExcessMaxMs=${maxSourceExcessUs / 1000}, " +
            "arrivalExcessMaxMs=${maxArrivalExcessUs / 1000}, " +
            "deliveryIncreaseMaxMs=${maxDeliveryIncreaseUs / 1000}" +
            (arrivalSpike?.let { ", $it" } ?: "")
    }

    companion object { private const val MAX_INTERVAL_US = 5_000_000L }
}
