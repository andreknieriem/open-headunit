package com.andrerinas.openheadunit.decoder.video

/** Local receiver admission policy, not a recovered sender/platform clock conversion.
 * Some surfaces return a buffer timestamp instead of a usable local presentation clock.
 * Such raw callback values cannot be used as A/V observations merely because a PTS matched.
 *
 * Causality requires presentation after receipt of the access unit and before callback receipt.
 * The one-second pipeline bound is deliberately wider than the 250ms presentation window plus
 * codec queuing; stalled output beyond that bound is not useful correction evidence. Callback
 * delivery may be batched/delayed by up to five seconds: preserve the original presentation stamp,
 * never substitute callback receipt time. These tests establish plausibility, not clock identity.
 */
internal object VideoFrameClockAdmission {
    const val MAX_PIPELINE_NS = 1_000_000_000L
    const val MAX_CALLBACK_DELAY_NS = 5_000_000_000L

    fun accepts(arrivalNs: Long, requestedNs: Long, actualNs: Long, receiptNs: Long): Boolean =
        actualNs != Long.MIN_VALUE && actualNs != Long.MAX_VALUE &&
            boundedElapsed(actualNs, arrivalNs, MAX_PIPELINE_NS) &&
            boundedElapsed(requestedNs, arrivalNs, MAX_PIPELINE_NS) &&
            boundedElapsed(receiptNs, actualNs, MAX_CALLBACK_DELAY_NS)

    private fun boundedElapsed(later: Long, earlier: Long, bound: Long): Boolean =
        later >= earlier && later - earlier in 0..bound
}
