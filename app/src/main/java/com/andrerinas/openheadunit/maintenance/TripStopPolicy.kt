package com.andrerinas.openheadunit.maintenance

/**
 * Notices when the car has come to a stop after a real drive, which is when the full screen
 * reminder can show without getting in the driver's way.
 *
 * A red light is not a stop: the car has to stand still for [STOP_MS], and it has to have
 * driven at least [MIN_DRIVE_METERS] since the previous stop, so creeping in a parking lot does
 * not fire it again and again.
 */
object TripStopPolicy {

    const val STOP_MS = 30_000L
    const val MIN_DRIVE_METERS = 500.0

    data class State(
        /** Distance driven since the last stop was reported. */
        val drivenMeters: Double = 0.0,
        /** When the car started standing still, or null while it moves. */
        val stillSinceMs: Long? = null,
    )

    data class Result(val state: State, val stopped: Boolean)

    fun onFix(state: State, addedMeters: Double, speedMps: Float?, nowMs: Long): Result {
        val driven = state.drivenMeters + addedMeters
        val still = speedMps != null && speedMps < OdometerStepPolicy.STILL_SPEED_MPS
        val stillSince = if (still) state.stillSinceMs ?: nowMs else null
        val stopped = stillSince != null && driven >= MIN_DRIVE_METERS && nowMs - stillSince >= STOP_MS
        return if (stopped) {
            Result(State(0.0, stillSince), true)
        } else {
            Result(State(driven, stillSince), false)
        }
    }
}
