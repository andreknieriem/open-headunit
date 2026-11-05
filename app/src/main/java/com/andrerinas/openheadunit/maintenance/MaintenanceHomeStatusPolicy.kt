package com.andrerinas.openheadunit.maintenance

/**
 * The one line the home shows about maintenance, in the corner with the date and the speed.
 *
 * An overdue item comes first: it is the reason the line exists. Then what stops the mileage
 * from being right (no location, or driving the app did not see), because every distance shown
 * after that is wrong. Then what is coming up, and when nothing needs anything, a calm line.
 */
object MaintenanceHomeStatusPolicy {

    enum class Kind {
        OVERDUE,
        /** Coming up, and the distance is what brings it close. */
        COMING_UP_DISTANCE,
        /** Coming up, and the date is what brings it close. */
        COMING_UP_TIME,
        LOCATION_OFF,
        CHECK_MILEAGE,
        ALL_GOOD,
    }

    data class Status(
        val kind: Kind,
        /** The item named on the line, for the overdue and coming up kinds. */
        val entry: MaintenanceReminderPolicy.Entry?,
        /** How many other items also need attention, shown as "+N". */
        val more: Int,
    )

    fun status(
        summary: MaintenanceReminderPolicy.Summary,
        locationGranted: Boolean,
        mileageCheckPending: Boolean,
    ): Status {
        val more = Math.max(0, summary.needsAttention - 1)
        summary.overdue.firstOrNull()?.let { return Status(Kind.OVERDUE, it, more) }
        if (!locationGranted) return Status(Kind.LOCATION_OFF, null, 0)
        if (mileageCheckPending) return Status(Kind.CHECK_MILEAGE, null, 0)
        summary.comingUp.firstOrNull()?.let { return Status(comingUpKind(it), it, more) }
        return Status(Kind.ALL_GOOD, null, 0)
    }

    /** By distance when the distance is inside its warning window, otherwise by date. */
    private fun comingUpKind(entry: MaintenanceReminderPolicy.Entry): Kind {
        val remaining = entry.due.remainingMeters
        return if (remaining != null && remaining <= entry.item.warnMeters) Kind.COMING_UP_DISTANCE else Kind.COMING_UP_TIME
    }
}
