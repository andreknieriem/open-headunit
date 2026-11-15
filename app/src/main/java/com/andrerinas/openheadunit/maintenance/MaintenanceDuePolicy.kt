package com.andrerinas.openheadunit.maintenance

import java.util.Calendar

/**
 * Where an item stands: due by distance, by date, or by whichever comes first when it has both.
 *
 * Calendar months, not 30-day blocks, so "every 12 months" from March 14 lands on March 14.
 */
object MaintenanceDuePolicy {

    enum class State {
        /** Not near either limit. */
        OK,
        /** Inside the warning window of at least one limit. */
        COMING_UP,
        /** Past at least one limit. */
        OVERDUE,
        /** No last service is known for any limit it uses, so nothing can be worked out. */
        UNKNOWN,
    }

    data class Due(
        val state: State,
        /** Odometer reading when it falls due, or null when not due by distance. */
        val dueMeters: Long?,
        /** Distance left; negative once overdue. */
        val remainingMeters: Long?,
        val dueMs: Long?,
        /** Time left; negative once overdue. */
        val remainingMs: Long?,
        /** How much of the interval is used, the larger of the two; 1.0 means due now. */
        val progress: Double,
    )

    const val DAY_MS = 24L * 60 * 60 * 1000

    fun evaluate(item: MaintenanceItem, odometerMeters: Long, nowMs: Long): Due {
        val dueMeters = if (item.intervalMeters != null && item.lastMeters != null) {
            item.lastMeters + item.intervalMeters
        } else null
        val remainingMeters = dueMeters?.let { it - odometerMeters }

        val dueMs = if (item.intervalMonths != null && item.lastMs != null) {
            addMonths(item.lastMs, item.intervalMonths)
        } else null
        val remainingMs = dueMs?.let { it - nowMs }

        if (remainingMeters == null && remainingMs == null) {
            return Due(State.UNKNOWN, null, null, null, null, 0.0)
        }

        val distanceProgress = if (item.intervalMeters != null && item.lastMeters != null && item.intervalMeters > 0) {
            (odometerMeters - item.lastMeters).toDouble() / item.intervalMeters
        } else 0.0
        val timeProgress = if (dueMs != null && item.lastMs != null && dueMs > item.lastMs) {
            (nowMs - item.lastMs).toDouble() / (dueMs - item.lastMs)
        } else 0.0

        val overdue = (remainingMeters != null && remainingMeters <= 0) ||
            (remainingMs != null && remainingMs <= 0)
        val comingUp = (remainingMeters != null && remainingMeters <= item.warnMeters) ||
            (remainingMs != null && remainingMs <= item.warnDays * DAY_MS)
        val state = when {
            overdue -> State.OVERDUE
            comingUp -> State.COMING_UP
            else -> State.OK
        }
        return Due(state, dueMeters, remainingMeters, dueMs, remainingMs, Math.max(distanceProgress, timeProgress))
    }

    fun addMonths(fromMs: Long, months: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = fromMs
        calendar.add(Calendar.MONTH, months)
        return calendar.timeInMillis
    }
}
