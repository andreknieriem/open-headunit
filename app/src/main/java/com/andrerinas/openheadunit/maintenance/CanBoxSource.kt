package com.andrerinas.openheadunit.maintenance

/**
 * The trip counters a CAN box reports, as the dashboard shows them, in tenths of the dashboard's
 * unit. Either counter can be missing: not every box sends both.
 */
data class TripReading(
    val tripATenths: Long?,
    val tripBTenths: Long?,
    /** The dashboard counts in miles. */
    val miles: Boolean,
)

/**
 * Where a head unit maker's CAN box data comes from. Every maker exposes it differently, so each
 * one gets its own source; the distance logic after it ([CanBoxDistancePolicy]) is shared.
 */
interface CanBoxSource {

    /** A short name for the logs. */
    val name: String

    /** Starts delivering readings to [listener] on the main thread. Harmless to call twice. */
    fun start(listener: (TripReading) -> Unit)

    fun stop()
}
