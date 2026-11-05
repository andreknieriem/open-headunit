package com.andrerinas.openheadunit.maintenance

/**
 * What happens when the user types the number on the dashboard.
 *
 * The odometer takes that number as is. Separately, the stretch since an earlier typed number
 * (the base) compares what the dashboard and the GPS counted, and once the GPS has counted enough
 * of it, the ratio becomes a correction factor for the distance from then on. GPS tends to count
 * a little short and car odometers a little long, so a small factor is expected; it is kept
 * between [MIN_FACTOR] and [MAX_FACTOR] so one mistyped digit cannot wreck every reading after it.
 *
 * A short stretch is not thrown away: the base stays, so a user who checks the number every few
 * thousand km still builds up the distance the factor needs.
 */
object OdometerCorrectionPolicy {

    const val MIN_FACTOR = 0.90
    const val MAX_FACTOR = 1.10

    /** Below this much GPS distance the ratio is mostly noise, so the factor stays as it was. */
    const val MIN_BASE_METERS = 50_000.0

    data class Result(
        val factor: Double,
        /** The ratio fell outside the allowed range and was cut to its edge. */
        val clamped: Boolean,
        /** A new factor was worked out; false when there was too little distance to tell. */
        val updated: Boolean,
        /** The typed number becomes the new base and the GPS count starts again from zero. */
        val restartStretch: Boolean,
    )

    /**
     * @param baseMeters the dashboard number the stretch started from, or null when there is no
     *   usable stretch (never typed, or driving went uncounted since).
     * @param rawGpsMeters the uncorrected GPS distance counted since [baseMeters].
     * @param typedMeters the number the user just typed.
     */
    fun correct(currentFactor: Double, baseMeters: Long?, rawGpsMeters: Double, typedMeters: Long): Result {
        val dashboardMeters = if (baseMeters == null) 0.0 else (typedMeters - baseMeters).toDouble()
        if (baseMeters == null || dashboardMeters <= 0) {
            // No stretch, or a number lower than the base: the base itself was wrong. Start over.
            return Result(currentFactor, clamped = false, updated = false, restartStretch = true)
        }
        if (rawGpsMeters < MIN_BASE_METERS) {
            return Result(currentFactor, clamped = false, updated = false, restartStretch = false)
        }
        val ratio = dashboardMeters / rawGpsMeters
        val factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, ratio))
        return Result(factor, clamped = factor != ratio, updated = true, restartStretch = true)
    }

    /** Guards a stored factor that predates these limits or was edited by hand. */
    fun sanitize(factor: Double): Double =
        if (factor.isNaN() || factor <= 0) 1.0 else Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor))
}
