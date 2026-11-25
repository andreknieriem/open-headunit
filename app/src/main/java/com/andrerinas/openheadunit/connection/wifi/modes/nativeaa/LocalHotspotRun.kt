package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * What one local hotspot run holds between its start and its stop. Every field goes back to a
 * fresh value in [reset], so a stop followed by a start leaves nothing of the first run behind.
 *
 * The restart ledger is not here: it is process-level, so a launcher swap does not reset it.
 */
data class LocalHotspotRun(
    /** Counts runs, so a late callback of an older run can be told from the current one. */
    @Volatile var generation: Int = 0,
    /** The host that holds the reservation, or null. Kept as `Any` so this class loads below API 26. */
    @Volatile var reservation: Any? = null,
    @Volatile var startInFlight: Boolean = false,
    /** The "stored manual values are not applied" line is said once per run. */
    @Volatile var notAppliedLogged: Boolean = false,
) {
    /** A run of an older generation fails alone: the current run keeps its reservation and its mark. */
    fun finish(gen: Int): Boolean {
        if (gen != generation) return false
        reservation = null
        startInFlight = false
        return true
    }

    /** The generation moves on, so a run from before the stop reads as stale after the next launch. */
    fun reset() {
        generation += 1
        reservation = null
        startInFlight = false
        notAppliedLogged = false
    }
}
