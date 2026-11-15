package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.MaintenanceDuePolicy.State

/**
 * Which items deserve the driver's attention.
 *
 * Only items set to remind take part: "track only" keeps its history and due date in the list
 * but never asks for attention, and "off" is not tracked at all.
 */
object MaintenanceReminderPolicy {

    data class Entry(val item: MaintenanceItem, val due: MaintenanceDuePolicy.Due)

    data class Summary(
        /** Past due, the furthest past first. */
        val overdue: List<Entry>,
        /** Inside their warning window, the closest first. */
        val comingUp: List<Entry>,
    ) {
        val needsAttention: Int get() = overdue.size + comingUp.size
    }

    fun summary(items: List<MaintenanceItem>, odometerMeters: Long, nowMs: Long): Summary {
        val evaluated = items.filter { it.mode == MaintenanceMode.REMIND }
            .map { Entry(it, MaintenanceDuePolicy.evaluate(it, odometerMeters, nowMs)) }
        val overdue = evaluated.filter { it.due.state == State.OVERDUE }.sortedByDescending { it.due.progress }
        val comingUp = evaluated.filter { it.due.state == State.COMING_UP }.sortedByDescending { it.due.progress }
        return Summary(overdue, comingUp)
    }
}
