package com.andrerinas.openheadunit.maintenance

/**
 * Turns a stream of GPS fixes into distance driven.
 *
 * GPS on a head unit is noisy in two ways that would inflate an odometer: a parked car "moves"
 * a few meters every second, and a poor fix can land tens of meters away. So the reference
 * point only moves once the car has really gone further than the fix's own error, fixes with a
 * poor accuracy are skipped, and a jump no car could make is dropped rather than counted.
 *
 * After losing the signal (a tunnel, a garage) the straight line to the first fix back is added
 * only when the speed it implies is believable. It undercounts a curved road a little, which is
 * the safe side for an odometer.
 */
object OdometerStepPolicy {

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Monotonic time of the fix, so a clock change cannot fake a gap. */
        val atMs: Long,
        /** Null when the chip did not say: such a fix is not trusted for distance. */
        val accuracyM: Float?,
        /** Null when the chip did not report a speed. */
        val speedMps: Float?,
    )

    enum class Kind {
        /** The first usable fix: it becomes the reference, nothing is added. */
        FIRST,
        /** Moved far enough: the distance is added. */
        ADDED,
        /** Back after a gap at a believable speed: the straight line is added. */
        BRIDGED,
        /** Not far enough from the reference yet to tell movement from noise. */
        WAITING,
        /** Reported as standing still: drift is ignored. */
        STILL,
        /** Accuracy too poor, or not reported. */
        INACCURATE,
        /** A jump no car makes, or a gap too long to trust: restart from here without adding. */
        JUMP,
    }

    data class Step(val kind: Kind, val addMeters: Double, val reference: Fix?)

    /** A fix worse than this is skipped. */
    const val MAX_ACCURACY_M = 25f

    /** Never count a step shorter than this, whatever the fix's accuracy says. */
    const val MIN_STEP_M = 10.0

    /** Below this speed the car is taken as standing still. */
    const val STILL_SPEED_MPS = 0.8f

    /** A silence longer than this is a lost signal, not just a slow update. */
    const val GAP_MS = 5_000L

    /** Longer than this without a fix, a straight line says nothing about the road taken. */
    const val MAX_BRIDGE_MS = 20 * 60_000L

    /** About 250 km/h: anything faster between two fixes is a bad fix. */
    const val MAX_SPEED_MPS = 70.0

    /** About 160 km/h averaged over a gap: faster than that, the gap is not trusted. */
    const val MAX_BRIDGE_SPEED_MPS = 45.0

    fun step(reference: Fix?, fix: Fix): Step {
        val accuracy = fix.accuracyM
        if (accuracy == null || accuracy > MAX_ACCURACY_M) return Step(Kind.INACCURATE, 0.0, reference)
        if (reference == null) return Step(Kind.FIRST, 0.0, fix)

        val elapsedMs = fix.atMs - reference.atMs
        if (elapsedMs <= 0) return Step(Kind.WAITING, 0.0, reference)

        val meters = distanceMeters(reference.latitude, reference.longitude, fix.latitude, fix.longitude)
        val speed = meters / (elapsedMs / 1000.0)

        if (elapsedMs > MAX_BRIDGE_MS) return Step(Kind.JUMP, 0.0, fix)
        if (elapsedMs > GAP_MS) {
            return if (speed <= MAX_BRIDGE_SPEED_MPS) {
                Step(Kind.BRIDGED, meters, fix)
            } else {
                Step(Kind.JUMP, 0.0, fix)
            }
        }
        if (speed > MAX_SPEED_MPS) return Step(Kind.JUMP, 0.0, fix)

        // Keep the reference position but carry its time forward, so a long wait in traffic is
        // not later mistaken for a lost signal.
        val held = reference.copy(atMs = fix.atMs)
        val threshold = Math.max(MIN_STEP_M, accuracy.toDouble())
        if (fix.speedMps != null && fix.speedMps < STILL_SPEED_MPS && meters < threshold * 2) {
            return Step(Kind.STILL, 0.0, held)
        }
        if (meters < threshold) return Step(Kind.WAITING, 0.0, held)
        return Step(Kind.ADDED, meters, fix)
    }

    /** Great-circle distance on a spherical Earth: well under a meter off at these step lengths. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
