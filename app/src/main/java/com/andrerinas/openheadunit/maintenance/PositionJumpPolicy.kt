package com.andrerinas.openheadunit.maintenance

/**
 * Spots driving the app did not see: the first good fix of a session lands far from where the
 * previous session ended, so the car moved while the app was closed (killed by a memory saver,
 * a long sleep, a tow truck or a ferry). The app cannot know the road taken, so it only suggests
 * correcting the mileage, with the straight-line distance as a lower bound.
 */
object PositionJumpPolicy {

    /** GPS error and parking somewhere else on the same street stay well under this. */
    const val THRESHOLD_METERS = 2_000.0

    /** The straight-line gap when it is worth mentioning, or null. */
    fun missedMeters(
        lastLatitude: Double?,
        lastLongitude: Double?,
        latitude: Double,
        longitude: Double,
    ): Double? {
        if (lastLatitude == null || lastLongitude == null) return null
        val meters = OdometerStepPolicy.distanceMeters(lastLatitude, lastLongitude, latitude, longitude)
        return meters.takeIf { it >= THRESHOLD_METERS }
    }
}
